package com.chubb.insights.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Aggregates over claim_summary. Every figure is computed at query time from one table, so
 * there are no running totals to drift out of step with the events.
 */
@Service
public class InsightsQueries {

    /** Being worked on: counts towards workload. */
    private static final String OPEN = "('SUBMITTED','IN_REVIEW','AWAITING_INFO')";
    /** Liability still outstanding: open claims plus approved-but-unpaid. */
    private static final String OUTSTANDING = "('SUBMITTED','IN_REVIEW','AWAITING_INFO','APPROVED')";
    /** Breached = deadline passed and the clock is running (paused while waiting on the claimant). */
    private static final String BREACHED = "(NOT sla_paused AND sla_due_at < now())";
    private static final String TEAM = "(CAST(? AS uuid) IS NULL OR team_id = CAST(? AS uuid))";

    private final JdbcTemplate jdbc;

    public InsightsQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ workload

    public record HandlerLoad(UUID handlerId, String handlerName, int open, int inReview, int awaitingInfo,
                              int slaBreached, int oldestOpenDays) {}

    public record Ageing(int days0to7, int days8to30, int over30) {}

    public record Workload(UUID teamId, int openClaims, int unassigned, int slaBreached, Ageing ageing,
                           List<HandlerLoad> handlers, Instant generatedAt) {}

    public Workload workload(UUID teamId) {
        List<HandlerLoad> handlers = jdbc.query("""
                SELECT assigned_handler_id, max(assigned_handler_name),
                       count(*),
                       count(*) FILTER (WHERE status = 'IN_REVIEW'),
                       count(*) FILTER (WHERE status = 'AWAITING_INFO'),
                       count(*) FILTER (WHERE %s),
                       coalesce(floor(max(extract(epoch FROM now() - reported_at)) / 86400), 0)
                FROM claim_summary
                WHERE status IN %s AND assigned_handler_id IS NOT NULL AND %s
                GROUP BY assigned_handler_id
                ORDER BY count(*) DESC, max(assigned_handler_name)
                """.formatted(BREACHED, OPEN, TEAM),
                (rs, i) -> new HandlerLoad(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getInt(4),
                        rs.getInt(5), rs.getInt(6), rs.getInt(7)),
                teamId, teamId);

        return jdbc.queryForObject("""
                SELECT count(*),
                       count(*) FILTER (WHERE assigned_handler_id IS NULL),
                       count(*) FILTER (WHERE %s),
                       count(*) FILTER (WHERE reported_at >= now() - interval '7 days'),
                       count(*) FILTER (WHERE reported_at <  now() - interval '7 days'
                                          AND reported_at >= now() - interval '30 days'),
                       count(*) FILTER (WHERE reported_at <  now() - interval '30 days')
                FROM claim_summary
                WHERE status IN %s AND %s
                """.formatted(BREACHED, OPEN, TEAM),
                (rs, i) -> new Workload(teamId, rs.getInt(1), rs.getInt(2), rs.getInt(3),
                        new Ageing(rs.getInt(4), rs.getInt(5), rs.getInt(6)), handlers, Instant.now()),
                teamId, teamId);
    }

    // ------------------------------------------------------------------ throughput

    public record HandlerThroughput(UUID handlerId, String handlerName, int approved, int rejected,
                                    BigDecimal avgDaysToDecision) {}

    public record Throughput(UUID teamId, Instant from, Instant to, int decided, int approved, int rejected,
                             BigDecimal avgDaysToDecision, List<HandlerThroughput> handlers) {}

    public Throughput throughput(UUID teamId, Instant from, Instant to) {
        List<HandlerThroughput> rows = jdbc.query("""
                SELECT assigned_handler_id, max(assigned_handler_name),
                       count(*) FILTER (WHERE status IN ('APPROVED','SETTLED')),
                       count(*) FILTER (WHERE status = 'REJECTED'),
                       avg(extract(epoch FROM decided_at - reported_at)) / 86400
                FROM claim_summary
                WHERE decided_at >= ? AND decided_at < ? AND %s
                GROUP BY assigned_handler_id
                ORDER BY max(assigned_handler_name)
                """.formatted(TEAM),
                (rs, i) -> new HandlerThroughput(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3),
                        rs.getInt(4), days(rs.getBigDecimal(5))),
                Timestamp.from(from), Timestamp.from(to), teamId, teamId);

        return jdbc.queryForObject("""
                SELECT count(*),
                       count(*) FILTER (WHERE status IN ('APPROVED','SETTLED')),
                       count(*) FILTER (WHERE status = 'REJECTED'),
                       avg(extract(epoch FROM decided_at - reported_at)) / 86400
                FROM claim_summary
                WHERE decided_at >= ? AND decided_at < ? AND %s
                """.formatted(TEAM),
                (rs, i) -> new Throughput(teamId, from, to, rs.getInt(1), rs.getInt(2), rs.getInt(3),
                        days(rs.getBigDecimal(4)), rows),
                Timestamp.from(from), Timestamp.from(to), teamId, teamId);
    }

    // ------------------------------------------------------------------ exposure

    public record Segment(String marketCode, String productLine, String currency, int openClaims,
                          BigDecimal outstandingReserve, BigDecimal paid, BigDecimal incurred,
                          BigDecimal outstandingUsd) {}

    public record LargeClaim(UUID claimId, String claimNumber, String marketCode, String productLine,
                             String status, String currency, BigDecimal currentReserve, BigDecimal reserveUsd) {}

    public record Exposure(List<Segment> segments, BigDecimal totalOutstandingUsd, BigDecimal totalPaidUsd,
                           List<LargeClaim> largestOpenReserves, String fxNote, Instant generatedAt) {}

    public Exposure exposure() {
        List<Segment> segments = jdbc.query("""
                SELECT cs.market_code, cs.product_line, cs.currency,
                       count(*) FILTER (WHERE cs.status IN %1$s),
                       coalesce(sum(cs.current_reserve) FILTER (WHERE cs.status IN %1$s), 0),
                       coalesce(sum(cs.paid_amount), 0),
                       fx.usd_rate
                FROM claim_summary cs LEFT JOIN fx_rate fx ON fx.currency = cs.currency
                GROUP BY cs.market_code, cs.product_line, cs.currency, fx.usd_rate
                ORDER BY cs.market_code, cs.product_line
                """.formatted(OUTSTANDING),
                (rs, i) -> {
                    BigDecimal outstanding = rs.getBigDecimal(5);
                    BigDecimal paid = rs.getBigDecimal(6);
                    return new Segment(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                            outstanding, paid, outstanding.add(paid), usd(outstanding, rs.getBigDecimal(7)));
                });

        // Paid totals need the rate too, so compute both totals from the same rows.
        BigDecimal totalOutstandingUsd = BigDecimal.ZERO;
        for (Segment s : segments) {
            if (s.outstandingUsd() != null) {
                totalOutstandingUsd = totalOutstandingUsd.add(s.outstandingUsd());
            }
        }
        BigDecimal totalPaidUsd = jdbc.queryForObject("""
                SELECT coalesce(sum(cs.paid_amount * fx.usd_rate), 0)
                FROM claim_summary cs JOIN fx_rate fx ON fx.currency = cs.currency
                """, BigDecimal.class).setScale(2, RoundingMode.HALF_UP);

        List<LargeClaim> largest = jdbc.query("""
                SELECT cs.claim_id, cs.claim_number, cs.market_code, cs.product_line, cs.status, cs.currency,
                       cs.current_reserve, cs.current_reserve * fx.usd_rate AS usd
                FROM claim_summary cs LEFT JOIN fx_rate fx ON fx.currency = cs.currency
                WHERE cs.status IN %s AND cs.current_reserve > 0
                ORDER BY usd DESC NULLS LAST
                LIMIT 5
                """.formatted(OUTSTANDING),
                (rs, i) -> new LargeClaim(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getBigDecimal(7),
                        rs.getBigDecimal(8) == null ? null : rs.getBigDecimal(8).setScale(2, RoundingMode.HALF_UP)));

        return new Exposure(segments, totalOutstandingUsd.setScale(2, RoundingMode.HALF_UP), totalPaidUsd, largest,
                "USD figures use static illustrative rates (fx_rate table); per-currency amounts are authoritative.",
                Instant.now());
    }

    private static BigDecimal usd(BigDecimal amount, BigDecimal rate) {
        return rate == null ? null : amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal days(BigDecimal v) {
        return v == null ? null : v.setScale(1, RoundingMode.HALF_UP);
    }
}
