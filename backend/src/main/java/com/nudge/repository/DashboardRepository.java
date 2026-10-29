package com.nudge.repository;

import com.nudge.dto.EmailDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/** Aggregate in PostgreSQL: never materialize event histories or decrypt list contents. */
@Repository
@Transactional(readOnly = true)
public class DashboardRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final String baseUrl;

    public DashboardRepository(NamedParameterJdbcTemplate jdbc, @Value("${app.base.url}") String baseUrl) {
        this.jdbc = jdbc;
        this.baseUrl = baseUrl;
    }

    private static final String METRICS = """
        WITH metrics AS (
          SELECT te.id, te.subject, te.recipient_email, te.tracking_id, te.created_at, te.archived_at,
            count(e.id) FILTER (WHERE e.type = 'OPEN') AS opens,
            count(e.id) FILTER (WHERE e.type = 'CLICK') AS clicks,
            max(e.timestamp) FILTER (WHERE e.type = 'OPEN') AS last_open,
            max(e.timestamp) FILTER (WHERE e.type = 'CLICK') AS last_click
          FROM tracked_emails te JOIN users u ON u.id = te.user_id
          LEFT JOIN tracking_events e ON e.email_id = te.id AND e.suspected_bot = false
          WHERE u.email = :owner AND (te.archived_at IS NOT NULL) = :archived
          GROUP BY te.id
        ), scored AS (
          SELECT metrics.*, CAST(least(100,
            least(opens * 15, 40) +
            CASE WHEN opens > 0 THEN
              round(40 * exp(-ln(2.0) / 6 * greatest(
                trunc(extract(epoch FROM (CAST(:now AS timestamp) - last_open)) / 60) / 60, 0))) +
              CASE WHEN opens > 5 THEN 20 WHEN opens > 3 THEN 15 WHEN opens > 1 THEN 10 ELSE 0 END
            ELSE 0 END +
            CASE WHEN clicks >= 2 THEN 20 WHEN clicks >= 1 THEN 10 ELSE 0 END
          ) AS integer) AS score FROM metrics
        )
        """;

    private static final Map<String, String> SORTS = Map.of(
            "subject", "lower(subject)", "recipientEmail", "lower(recipient_email)",
            "createdAt", "created_at", "archivedAt", "archived_at", "openCount", "opens",
            "clickCount", "clicks", "lastOpenedAt", "last_open", "leadScore", "score",
            "status", "CASE WHEN opens = 0 THEN 'Not Opened' WHEN opens = 1 THEN 'Opened' ELSE 'Opened Multiple Times' END");

    public Page<EmailDTO> list(String owner, boolean archived, String search, String sort, boolean ascending, Pageable page) {
        String column = SORTS.get(sort);
        if (column == null) throw new IllegalArgumentException("Unsupported sort column");
        var params = parameters(owner, archived).addValue("search", search.toLowerCase(java.util.Locale.ROOT))
                .addValue("limit", page.getPageSize()).addValue("offset", page.getOffset());
        String where = " WHERE strpos(lower(subject), :search) > 0 OR strpos(lower(coalesce(recipient_email, '')), :search) > 0";
        Long total = jdbc.queryForObject(METRICS + "SELECT count(*) FROM scored" + where, params, Long.class);
        var rows = jdbc.query(METRICS + "SELECT * FROM scored" + where + " ORDER BY " + column +
                (ascending ? " ASC" : " DESC") + " NULLS LAST, id DESC LIMIT :limit OFFSET :offset", params, (rs, index) -> {
            EmailDTO dto = new EmailDTO();
            dto.setId(rs.getLong("id"));
            dto.setSubject(rs.getString("subject"));
            dto.setRecipientEmail(rs.getString("recipient_email"));
            dto.setTrackingId(rs.getString("tracking_id"));
            dto.setCreatedAt(rs.getObject("created_at", LocalDateTime.class));
            dto.setArchivedAt(rs.getObject("archived_at", LocalDateTime.class));
            dto.setOpenCount(rs.getInt("opens"));
            dto.setClickCount(rs.getInt("clicks"));
            dto.setLastOpenedAt(rs.getObject("last_open", LocalDateTime.class));
            dto.setLastClickedAt(rs.getObject("last_click", LocalDateTime.class));
            dto.setLeadScore(rs.getInt("score"));
            dto.setStatus(dto.getOpenCount() == 0 ? "Not Opened" : dto.getOpenCount() == 1 ? "Opened" : "Opened Multiple Times");
            dto.setTrackingPixelUrl(baseUrl + "/track/open/" + dto.getTrackingId());
            dto.setClickTrackingBaseUrl(baseUrl + "/track/click/" + dto.getTrackingId() + "?url=");
            return dto;
        });
        return new PageImpl<>(rows, page, total == null ? 0 : total);
    }

    public Map<String, Object> stats(String owner) {
        return jdbc.queryForMap(METRICS + """
            SELECT count(*) AS total, count(*) FILTER (WHERE opens > 0) AS opened,
              count(*) FILTER (WHERE score >= 70) AS hot, coalesce(round(avg(score)), 0) AS average
            FROM scored
            """, parameters(owner, false));
    }

    private MapSqlParameterSource parameters(String owner, boolean archived) {
        return new MapSqlParameterSource().addValue("owner", owner).addValue("archived", archived)
                .addValue("now", LocalDateTime.now());
    }
}
