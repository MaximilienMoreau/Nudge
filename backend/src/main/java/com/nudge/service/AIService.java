package com.nudge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nudge.dto.FollowUpRequest;
import com.nudge.dto.FollowUpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Generates AI-powered follow-up emails using the OpenAI Chat Completions API.
 *
 * The prompt is tailored with engagement context (score, open count, days elapsed)
 * so the AI can produce relevant, timely follow-ups.
 */
@Service
public class AIService {

    private static final Logger log = LoggerFactory.getLogger(AIService.class);
    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";

    @Value("${openai.api.key}")
    private String apiKey;

    @Value("${openai.model}")
    private String model;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Generate a follow-up email given context about the original email and engagement.
     *
     * @param request follow-up context
     * @return AI-generated follow-up text and suggested subject
     */
    public FollowUpResponse generateFollowUp(FollowUpRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("OpenAI API key not configured — returning placeholder follow-up");
            return buildFallback(request);
        }

        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(request);

        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user",   "content", userPrompt)
                    ),
                    "max_tokens", 400,
                    "temperature", 0.7
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            HttpEntity<Map<String, Object>> httpRequest = new HttpEntity<>(body, headers);
            String rawResponse = restTemplate.postForObject(OPENAI_URL, httpRequest, String.class);

            return parseResponse(rawResponse, request.getSubject());

        } catch (Exception e) {
            log.error("OpenAI API call failed: {}", e.getMessage());
            return buildFallback(request);
        }
    }

    // ── Prompt builders ──────────────────────────────────────────

    private String buildSystemPrompt() {
        return """
                You are an expert sales and business communication coach who specializes in writing \
                concise, professional follow-up emails that get replies. \
                You write in a warm, human tone — never robotic or pushy. \
                Always keep the follow-up to 2-4 sentences. \
                Include a clear, single call-to-action at the end. \
                Return your response as JSON with two fields: "followUpText" and "suggestedSubject".""";
    }

    private String buildUserPrompt(FollowUpRequest request) {
        String engagementContext = buildEngagementContext(request);

        return String.format("""
                Write a follow-up email for the following situation:

                Original subject: %s
                Original email content: %s
                Recipient: %s
                Days since sent: %d
                Number of times email was opened: %d
                Engagement score (0-100): %d
                %s

                Write the follow-up email body and suggest a subject line. \
                Return ONLY valid JSON with fields "followUpText" and "suggestedSubject".""",
                request.getSubject(),
                request.getOriginalContent(),
                request.getRecipientEmail() != null ? request.getRecipientEmail() : "the recipient",
                request.getDaysSinceSent(),
                request.getOpenCount(),
                request.getEngagementScore(),
                engagementContext
        );
    }

    /** Provide additional tone guidance based on engagement signals. */
    private String buildEngagementContext(FollowUpRequest request) {
        if (request.getOpenCount() == 0) {
            return "Context: The email was never opened. Be gentle and non-pushy — they may not have seen it.";
        }
        if (request.getOpenCount() >= 3) {
            return "Context: The recipient opened the email " + request.getOpenCount() +
                   " times — they are clearly interested. Be confident and push for a response.";
        }
        if (request.getEngagementScore() > 60) {
            return "Context: High engagement detected. They opened it recently. Strike while the iron is hot.";
        }
        return "Context: Moderate engagement. Keep the tone friendly and curious.";
    }

    // ── Response parsing ──────────────────────────────────────────

    private FollowUpResponse parseResponse(String rawResponse, String originalSubject) throws Exception {
        JsonNode root = objectMapper.readTree(rawResponse);
        String content = root.path("choices").get(0)
                .path("message").path("content").asText();

        // The model returns JSON — parse it
        JsonNode parsed = objectMapper.readTree(content);
        String followUpText = parsed.path("followUpText").asText();
        String suggestedSubject = parsed.path("suggestedSubject").asText("Re: " + originalSubject);

        return new FollowUpResponse(followUpText, suggestedSubject);
    }

    /** Returns a sensible fallback when OpenAI is unavailable. */
    private FollowUpResponse buildFallback(FollowUpRequest request) {
        String text = String.format(
                "Hi,\n\nI wanted to follow up on my previous email regarding \"%s\". " +
                "I'd love to connect and discuss this further. " +
                "Would you have 15 minutes this week?\n\nLooking forward to hearing from you.",
                request.getSubject()
        );
        return new FollowUpResponse(text, "Re: " + request.getSubject());
    }
}
