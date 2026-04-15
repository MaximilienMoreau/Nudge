package com.nudge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nudge.dto.FollowUpRequest;
import com.nudge.dto.FollowUpResponse;
import com.nudge.dto.SendTimeResponse;
import com.nudge.model.EventType;
import com.nudge.model.TrackedEmail;
import com.nudge.model.TrackingEvent;
import com.nudge.repository.TrackedEmailRepository;
import com.nudge.repository.TrackingEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.DayOfWeek;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Generates AI-powered follow-up emails and send-time recommendations.
 *
 * Q2: RestTemplate injected as a Spring bean (no inline instantiation).
 * Q3: ObjectMapper injected as a Spring bean.
 * Q7: suggestSendTime uses a native SQL aggregation query rather than
 *     loading all open events into JVM memory.
 * P5: Null check on choices[0] before accessing nested fields.
 * S9: generateFollowUp looks up real openCount/score from DB (see AIController).
 */
@Service
public class AIService {

    private static final Logger log = LoggerFactory.getLogger(AIService.class);
    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";

    @Value("${openai.api.key}")
    private String apiKey;

    @Value("${openai.model}")
    private String model;

    private final TrackingEventRepository eventRepo;
    private final TrackedEmailRepository  emailRepo;
    private final LeadScoringService      leadScoringService;
    private final EncryptionService       encryptionService;
    private final RestTemplate            restTemplate;   // Q2: injected bean
    private final ObjectMapper            objectMapper;   // Q3: injected bean

    public AIService(TrackingEventRepository eventRepo,
                     TrackedEmailRepository emailRepo,
                     LeadScoringService leadScoringService,
                     EncryptionService encryptionService,
                     RestTemplate restTemplate,
                     ObjectMapper objectMapper) {
        this.eventRepo         = eventRepo;
        this.emailRepo         = emailRepo;
        this.leadScoringService = leadScoringService;
        this.encryptionService  = encryptionService;
        this.restTemplate       = restTemplate;
        this.objectMapper       = objectMapper;
    }

    /**
     * Generate a follow-up email.
     *
     * S9: openCount and engagementScore are computed server-side from the DB
     *     to prevent client-supplied value tampering.
     *
     * @param request  contains only emailId and daysSinceSent
     * @param ownerEmail  the authenticated user's email (for ownership check)
     */
    public FollowUpResponse generateFollowUp(FollowUpRequest request, String ownerEmail) {
        // S9: Look up real engagement data from the database
        TrackedEmail email = emailRepo.findById(request.getEmailId())
                .orElseThrow(() -> new IllegalArgumentException("Email not found: " + request.getEmailId()));

        if (!email.getUser().getEmail().equals(ownerEmail)) {
            throw new SecurityException("Access denied");
        }

        List<TrackingEvent> events   = eventRepo.findByEmailOrderByTimestampDesc(email);
        int openCount       = (int) events.stream().filter(e -> e.getType() == EventType.OPEN).count();
        int engagementScore = leadScoringService.computeScore(events);

        // S8: Decrypt content before sending to the AI
        String decryptedContent = encryptionService.decrypt(email.getContent());

        // Build an enriched internal request
        InternalFollowUpContext ctx = new InternalFollowUpContext(
                email.getSubject(),
                decryptedContent,
                email.getRecipientEmail(),
                request.getDaysSinceSent(),
                openCount,
                engagementScore
        );

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("OpenAI API key not configured — returning placeholder follow-up");
            return buildFallback(ctx);
        }

        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system", "content", buildSystemPrompt()),
                            Map.of("role", "user",   "content", buildUserPrompt(ctx))
                    ),
                    "max_tokens", 400,
                    "temperature", 0.7
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            String rawResponse = restTemplate.postForObject(
                    OPENAI_URL, new HttpEntity<>(body, headers), String.class);

            return parseResponse(rawResponse, ctx.subject);
        } catch (Exception e) {
            log.error("OpenAI API call failed: {}", e.getMessage());
            return buildFallback(ctx);
        }
    }

    // ── Prompt builders ──────────────────────────────────────────────────────

    private String buildSystemPrompt() {
        return """
                You are an expert sales and business communication coach who specializes in writing \
                concise, professional follow-up emails that get replies. \
                You write in a warm, human tone — never robotic or pushy. \
                Always keep the follow-up to 2-4 sentences. \
                Include a clear, single call-to-action at the end. \
                Return your response as JSON with two fields: "followUpText" and "suggestedSubject".""";
    }

    private String buildUserPrompt(InternalFollowUpContext ctx) {
        return String.format("""
                Write a follow-up email for the following situation:

                Original subject: %s
                Original email content: %s
                Recipient: %s
                Days since sent: %d
                Number of times email was opened: %d
                Engagement score (0-100): %d
                %s

                Return ONLY valid JSON with fields "followUpText" and "suggestedSubject".""",
                ctx.subject,
                ctx.content != null ? ctx.content : "(no content provided)",
                ctx.recipientEmail != null ? ctx.recipientEmail : "the recipient",
                ctx.daysSinceSent,
                ctx.openCount,
                ctx.engagementScore,
                buildEngagementContext(ctx)
        );
    }

    private String buildEngagementContext(InternalFollowUpContext ctx) {
        if (ctx.openCount == 0) {
            return "Context: The email was never opened. Be gentle and non-pushy.";
        }
        if (ctx.openCount >= 3) {
            return "Context: The recipient opened the email " + ctx.openCount +
                   " times — they are clearly interested. Be confident and push for a response.";
        }
        if (ctx.engagementScore > 60) {
            return "Context: High engagement detected. They opened it recently. Strike while the iron is hot.";
        }
        return "Context: Moderate engagement. Keep the tone friendly and curious.";
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    private FollowUpResponse parseResponse(String rawResponse, String originalSubject) throws Exception {
        JsonNode root = objectMapper.readTree(rawResponse);

        // P5: null-safe access on choices[0]
        JsonNode choices = root.path("choices");
        if (choices.isMissingNode() || !choices.isArray() || choices.isEmpty()) {
            log.warn("OpenAI response has no choices — falling back");
            return buildFallback(new InternalFollowUpContext(originalSubject, null, null, 0, 0, 0));
        }

        String content = choices.get(0).path("message").path("content").asText();
        JsonNode parsed = objectMapper.readTree(content);

        String followUpText     = parsed.path("followUpText").asText();
        String suggestedSubject = parsed.path("suggestedSubject").asText("Re: " + originalSubject);

        return new FollowUpResponse(followUpText, suggestedSubject);
    }

    private FollowUpResponse buildFallback(InternalFollowUpContext ctx) {
        String text = String.format(
                "Hi,\n\nI wanted to follow up on my previous email regarding \"%s\". " +
                "I'd love to connect and discuss this further. " +
                "Would you have 15 minutes this week?\n\nLooking forward to hearing from you.",
                ctx.subject);
        return new FollowUpResponse(text, "Re: " + ctx.subject);
    }

    // ── Send-time optimization ─────────────────────────────────────────────────

    /**
     * Q7: Analyse historical opens via a single SQL aggregation query
     * (GROUP BY day, hour) instead of loading every event into JVM memory.
     */
    public SendTimeResponse suggestSendTime(String userEmail) {
        long totalOpens = eventRepo.countOpensByUserEmail(userEmail);

        if (totalOpens == 0) {
            return new SendTimeResponse(
                    "No data yet — send more tracked emails to unlock insights",
                    null, null, "No open events recorded", false
            );
        }

        Object[] row = eventRepo.findBestSendSlot(userEmail);
        if (row == null || row.length < 3) {
            return new SendTimeResponse("Insufficient data", null, null, "Not enough data", false);
        }

        int dayOfWeek = ((Number) row[0]).intValue();
        int hour      = ((Number) row[1]).intValue();
        long count    = ((Number) row[2]).longValue();

        String dayName   = DayOfWeek.of(dayOfWeek).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        String hourLabel = formatHour(hour);
        String suggestion = dayName + " at " + hourLabel;

        return new SendTimeResponse(
                suggestion,
                dayName,
                hourLabel,
                "Based on " + count + " open" + (count == 1 ? "" : "s") + " (from " + totalOpens + " total)",
                true
        );
    }

    private static String formatHour(int hour) {
        if (hour == 0)  return "12:00 AM";
        if (hour < 12)  return hour + ":00 AM";
        if (hour == 12) return "12:00 PM";
        return (hour - 12) + ":00 PM";
    }

    // ── Internal context record ───────────────────────────────────────────────

    private record InternalFollowUpContext(
            String subject,
            String content,
            String recipientEmail,
            int daysSinceSent,
            int openCount,
            int engagementScore
    ) {}
}
