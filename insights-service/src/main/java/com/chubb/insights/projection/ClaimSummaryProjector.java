package com.chubb.insights.projection;

import com.chubb.insights.projection.ClaimEventMessage.Snapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * Maintains one claim_summary row per claim from the snapshot each event carries.
 *
 * Two guards make it safe under at-least-once, possibly re-ordered delivery:
 *  - processed_event: the same eventId is applied at most once;
 *  - last_event_version: a snapshot older than (or equal to) what we hold is ignored.
 * Together they make replaying the whole topic a safe way to rebuild the table.
 */
@Component
public class ClaimSummaryProjector {

    private static final String UPSERT = """
            INSERT INTO claim_summary (claim_id, claim_number, market_code, product_line, currency, status, team_id,
                assigned_handler_id, assigned_handler_name, claimed_amount, current_reserve, paid_amount,
                reported_at, decided_at, sla_due_at, sla_paused, last_event_version, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (claim_id) DO UPDATE SET
                status = EXCLUDED.status,
                team_id = EXCLUDED.team_id,
                assigned_handler_id = EXCLUDED.assigned_handler_id,
                assigned_handler_name = EXCLUDED.assigned_handler_name,
                claimed_amount = EXCLUDED.claimed_amount,
                current_reserve = EXCLUDED.current_reserve,
                paid_amount = EXCLUDED.paid_amount,
                decided_at = EXCLUDED.decided_at,
                sla_due_at = EXCLUDED.sla_due_at,
                sla_paused = EXCLUDED.sla_paused,
                last_event_version = EXCLUDED.last_event_version,
                updated_at = now()
            WHERE claim_summary.last_event_version < EXCLUDED.last_event_version
            """;

    private final JdbcTemplate jdbc;

    public ClaimSummaryProjector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if the summary changed, false for a duplicate or stale event */
    @Transactional
    public boolean apply(ClaimEventMessage event) {
        int fresh = jdbc.update("INSERT INTO processed_event (event_id, processed_at) VALUES (?, now()) "
                + "ON CONFLICT DO NOTHING", event.eventId());
        if (fresh == 0) {
            return false;
        }
        Snapshot c = event.claim();
        return jdbc.update(UPSERT,
                c.claimId(), c.claimNumber(), c.marketCode(), c.productLine(), c.currency(), c.status(), c.teamId(),
                c.assignedHandlerId(), c.assignedHandlerName(), orZero(c.claimedAmount()), orZero(c.currentReserve()),
                orZero(c.paidAmount()), ts(c.reportedAt()), ts(c.decidedAt()), ts(c.slaDueAt()), c.slaPaused(),
                c.version()) > 0;
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
