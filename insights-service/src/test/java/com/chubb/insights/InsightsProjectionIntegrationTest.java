package com.chubb.insights;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Feeds hand-built claims.events messages through real Kafka and Postgres and checks the
 * projection and the aggregates. Each test uses its own markets/teams so they don't interfere.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InsightsProjectionIntegrationTest {

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    // the app's own factory: it points at the Testcontainers broker (via @ServiceConnection)
    @Autowired ConsumerFactory<String, String> consumerFactory;

    static final UUID HK_TEAM = UUID.randomUUID();
    static final UUID MY_TEAM = UUID.randomUUID();
    static final UUID TH_TEAM = UUID.randomUUID();
    static final UUID SG_TEAM = UUID.randomUUID();

    @Test
    void appliesNewerSnapshotsAndIgnoresDuplicatesAndStaleOnes() throws Exception {
        UUID claim = UUID.randomUUID();
        UUID handler = UUID.randomUUID();
        send(event(claim, 0, "SUBMITTED", "HK", "PROPERTY", "HKD", HK_TEAM, null, "1000.00", "0", future()));
        Map<String, Object> v2 = event(claim, 2, "IN_REVIEW", "HK", "PROPERTY", "HKD", HK_TEAM, handler, "1500.00", "0", future());
        send(v2);
        send(v2);                                      // duplicate delivery (same eventId)
        Map<String, Object> stale = event(claim, 1, "SUBMITTED", "HK", "PROPERTY", "HKD", HK_TEAM, null, "1000.00", "0", future());
        send(stale);                                   // arrives late, older version

        await().atMost(Duration.ofSeconds(15)).until(() -> processed(stale.get("eventId")));
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, current_reserve, last_event_version FROM claim_summary WHERE claim_id = ?", claim);
        assertThat(row.get("status")).isEqualTo("IN_REVIEW");
        assertThat(row.get("current_reserve").toString()).isEqualTo("1500.00");
        assertThat(((Number) row.get("last_event_version")).longValue()).isEqualTo(2L);
    }

    @Test
    void exposureAndWorkloadAggregateTheProjection() throws Exception {
        UUID handler = UUID.randomUUID();
        // MY: one in review (assigned), one unassigned and past its SLA, one settled
        send(event(UUID.randomUUID(), 1, "IN_REVIEW", "MY", "PROPERTY", "MYR", MY_TEAM, handler, "10000.00", "0", future()));
        send(event(UUID.randomUUID(), 0, "SUBMITTED", "MY", "PROPERTY", "MYR", MY_TEAM, null, "3000.00", "0", past()));
        Map<String, Object> settled = event(UUID.randomUUID(), 5, "SETTLED", "MY", "PROPERTY", "MYR", MY_TEAM, handler, "0", "4000.00", past());
        // TH: waiting on the claimant past the due date -> paused, so NOT breached
        Map<String, Object> paused = event(UUID.randomUUID(), 2, "AWAITING_INFO", "TH", "MOTOR", "THB", TH_TEAM, handler, "50000.00", "0", past());
        ((Map<String, Object>) paused.get("claim")).put("slaPaused", true);
        send(settled);
        send(paused);
        await().atMost(Duration.ofSeconds(15)).until(() -> processed(settled.get("eventId")) && processed(paused.get("eventId")));
        await().atMost(Duration.ofSeconds(15)).until(() -> count("market_code = 'MY'") == 3);

        // MYR 10000 + 3000 outstanding at 0.22 = USD 2860; THB 50000 at 0.029 = USD 1450
        mvc.perform(get("/api/insights/exposure").with(as("MANAGER", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[?(@.marketCode=='MY')].outstandingReserve").value(13000.00))
                .andExpect(jsonPath("$.segments[?(@.marketCode=='MY')].paid").value(4000.00))
                .andExpect(jsonPath("$.segments[?(@.marketCode=='MY')].incurred").value(17000.00))
                .andExpect(jsonPath("$.segments[?(@.marketCode=='MY')].outstandingUsd").value(2860.00))
                .andExpect(jsonPath("$.segments[?(@.marketCode=='TH')].outstandingUsd").value(1450.00));

        mvc.perform(get("/api/insights/workload").param("teamId", MY_TEAM.toString()).with(as("MANAGER", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openClaims").value(2))
                .andExpect(jsonPath("$.unassigned").value(1))
                .andExpect(jsonPath("$.slaBreached").value(1))
                .andExpect(jsonPath("$.handlers", hasSize(1)))
                .andExpect(jsonPath("$.handlers[0].open").value(1));

        mvc.perform(get("/api/insights/workload").param("teamId", TH_TEAM.toString()).with(as("MANAGER", null)))
                .andExpect(jsonPath("$.slaBreached").value(0))
                .andExpect(jsonPath("$.handlers[0].awaitingInfo").value(1));
    }

    @Test
    void handlersAreLimitedToTheirOwnTeamAndCannotSeeExposure() throws Exception {
        mvc.perform(get("/api/insights/workload").param("teamId", MY_TEAM.toString()).with(as("HANDLER", SG_TEAM)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/insights/workload").with(as("HANDLER", SG_TEAM)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamId").value(SG_TEAM.toString()));
        mvc.perform(get("/api/insights/exposure").with(as("HANDLER", SG_TEAM)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/insights/exposure").with(as("CLAIMANT", null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void malformedEventGoesToTheDeadLetterTopicWithoutBlockingThePartition() throws Exception {
        String key = UUID.randomUUID().toString();
        kafka.send("claims.events", key, "{not json").get();

        try (Consumer<String, String> consumer = consumerFactory.createConsumer("dlt-test-" + UUID.randomUUID(), "test")) {
            consumer.subscribe(List.of("claims.events.insights.DLT"));
            AtomicBoolean seen = new AtomicBoolean();
            await().atMost(Duration.ofSeconds(20)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> seen.compareAndSet(false, key.equals(r.key())));
                return seen.get();
            });
        }

        // A valid event on the same key is still processed afterwards.
        UUID claim = UUID.fromString(key);
        Map<String, Object> ok = event(claim, 0, "SUBMITTED", "ID", "MOTOR", "IDR", UUID.randomUUID(), null, "1.00", "0", future());
        send(ok);
        await().atMost(Duration.ofSeconds(15)).until(() -> processed(ok.get("eventId")));
    }

    // ------------------------------------------------------------------ helpers

    private static RequestPostProcessor as(String role, UUID teamId) {
        return jwt().jwt(j -> {
            j.subject(UUID.randomUUID().toString()).claim("role", role);
            if (teamId != null) {
                j.claim("teamId", teamId.toString());
            }
        }).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private Map<String, Object> event(UUID claimId, long version, String status, String market, String line,
                                      String currency, UUID team, UUID handler, String reserve, String paid,
                                      Instant slaDue) {
        Map<String, Object> claim = new HashMap<>();
        claim.put("claimId", claimId);
        claim.put("claimNumber", market + "-TEST-" + version);
        claim.put("version", version);
        claim.put("status", status);
        claim.put("marketCode", market);
        claim.put("productLine", line);
        claim.put("currency", currency);
        claim.put("teamId", team);
        claim.put("assignedHandlerId", handler);
        claim.put("assignedHandlerName", handler == null ? null : "Test Handler");
        claim.put("claimedAmount", reserve);
        claim.put("currentReserve", reserve);
        claim.put("paidAmount", paid);
        claim.put("reportedAt", Instant.now().minus(Duration.ofDays(3)).toString());
        claim.put("decidedAt", "SETTLED".equals(status) ? Instant.now().toString() : null);
        claim.put("slaDueAt", slaDue.toString());
        claim.put("slaPaused", false);
        claim.put("someFieldInsightsDoesNotKnow", "ignored"); // tolerant reader
        Map<String, Object> e = new HashMap<>();
        e.put("eventId", UUID.randomUUID().toString());
        e.put("eventType", "Test");
        e.put("occurredAt", Instant.now().toString());
        e.put("claim", claim);
        e.put("data", Map.of());
        return e;
    }

    private void send(Map<String, Object> event) throws Exception {
        String key = ((Map<?, ?>) event.get("claim")).get("claimId").toString();
        kafka.send("claims.events", key, json.writeValueAsString(event)).get();
    }

    private boolean processed(Object eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?::uuid",
                Integer.class, eventId.toString()) == 1;
    }

    private int count(String where) {
        return jdbc.queryForObject("SELECT count(*) FROM claim_summary WHERE " + where, Integer.class);
    }

    private static Instant future() {
        return Instant.now().plus(Duration.ofDays(5));
    }

    private static Instant past() {
        return Instant.now().minus(Duration.ofDays(1));
    }
}
