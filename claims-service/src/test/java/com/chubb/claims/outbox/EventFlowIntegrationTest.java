package com.chubb.claims.outbox;

import com.chubb.claims.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Claim command -> outbox -> relay -> Kafka -> notification consumer, on real containers. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EventFlowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    // the app's own factory: it points at the Testcontainers broker (via @ServiceConnection)
    @Autowired ConsumerFactory<String, String> consumerFactory;

    @Test
    void submittedClaimIsPublishedKeyedByClaimIdAndNotifiesTheClaimant() throws Exception {
        String token = login("mei.tan@example.com");
        String claimId = json.readTree(mvc.perform(post("/api/claims")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "policyId", "50000000-0000-0000-0000-000000000002",
                                "incidentDate", "2026-11-10",
                                "description", "Storm damage to roof",
                                "claimedAmount", "7000.00",
                                "property", Map.of("propertyAddress", "12 Holland Rd", "peril", "STORM", "habitable", true)))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).at("/summary/id").asText();

        // 1. The relay publishes it: keyed by claimId, with eventType/eventId headers, outbox row marked.
        ConsumerRecord<String, String> record = awaitRecord(claimId);
        assertThat(new String(record.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8))
                .isEqualTo("ClaimSubmitted");
        JsonNode payload = json.readTree(record.value());
        assertThat(payload.at("/claim/status").asText()).isEqualTo("SUBMITTED");
        assertThat(payload.at("/claim/currentReserve").decimalValue()).isEqualByComparingTo("6000"); // 7000 - 1000
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id = ?::uuid AND published_at IS NULL",
                Integer.class, claimId) == 0);

        // 2. The notification consumer turns it into an inbox entry for the claimant.
        await().atMost(Duration.ofSeconds(15)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM notification WHERE claim_id = ?::uuid", Integer.class, claimId) == 1);
        String inbox = mvc.perform(get("/api/notifications").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(inbox).contains("We have received your claim");
    }

    private ConsumerRecord<String, String> awaitRecord(String key) {
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("test-" + UUID.randomUUID(), "test")) {
            consumer.subscribe(List.of("claims.events"));
            List<ConsumerRecord<String, String>> found = new ArrayList<>();
            await().atMost(Duration.ofSeconds(20)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (key.equals(r.key())) {
                        found.add(r);
                    }
                });
                return !found.isEmpty();
            });
            return found.get(0);
        }
    }

    private String login(String email) throws Exception {
        String res = mvc.perform(post("/api/auth/dev-login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email))))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("token").asText();
    }
}
