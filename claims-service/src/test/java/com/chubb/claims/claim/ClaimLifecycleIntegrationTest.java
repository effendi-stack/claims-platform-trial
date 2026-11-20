package com.chubb.claims.claim;

import com.chubb.claims.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end through HTTP, security, JPA, Postgres and the outbox (real containers).
 * Seeded users: see V2__seed_reference_data.sql.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ClaimLifecycleIntegrationTest {

    static final String MEI = "mei.tan@example.com";         // claimant, SG policies
    static final String JACK = "jack.wilson@example.com";    // claimant, AU policies
    static final String PRIYA = "priya.nair@example.com";    // SG handler, authority 20,000
    static final String DANIEL = "daniel.lim@example.com";   // SG handler
    static final String SOPHIE = "sophie.brown@example.com"; // AU handler
    static final String MEI_MOTOR_POLICY = "50000000-0000-0000-0000-000000000001"; // SGD 80k, deductible 500

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    void fullLifecycle_submitPickUpInfoLoopApproveSettle() throws Exception {
        String mei = login(MEI), priya = login(PRIYA), daniel = login(DANIEL);

        // Claimant reports: 201, no reserve or SLA exposed to the claimant
        JsonNode submitted = body(call(mei, post("/api/claims"), motorClaim("12000.00"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.summary.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.summary.currentReserve").doesNotExist())
                .andExpect(jsonPath("$.allowedActions", contains("WITHDRAW"))));
        String id = submitted.at("/summary/id").asText();
        assertThat(submitted.at("/summary/claimNumber").asText()).matches("SG-MOT-\\d{4}-\\d{6}");

        // Handler sees it in the unassigned team queue with the initial reserve (12000 - 500)
        JsonNode queue = body(call(priya, get("/api/claims?assignedTo=unassigned"), null).andExpect(status().isOk()));
        JsonNode row = find(queue, id);
        assertThat(row.get("currentReserve").decimalValue()).isEqualByComparingTo("11500");

        // Pick up; a second handler racing for it gets 409
        call(priya, post("/api/claims/" + id + "/assign"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.status").value("IN_REVIEW"))
                .andExpect(jsonPath("$.summary.assignedHandlerName").value("Priya Nair"));
        call(daniel, post("/api/claims/" + id + "/assign"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLAIM_ALREADY_ASSIGNED"));

        // Information loop
        JsonNode awaiting = body(call(priya, post("/api/claims/" + id + "/information-requests"),
                Map.of("question", "Please upload the repair quote"))
                .andExpect(jsonPath("$.summary.status").value("AWAITING_INFO")));
        String requestId = awaiting.at("/informationRequests/0/id").asText();
        call(mei, get("/api/claims/" + id), null)
                .andExpect(jsonPath("$.allowedActions", contains("RESPOND_TO_INFO", "WITHDRAW")));
        call(mei, post("/api/claims/" + id + "/information-requests/" + requestId + "/response"),
                Map.of("responseText", "Quote from ABC Motors: SGD 9,800"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.status").value("IN_REVIEW"))
                .andExpect(jsonPath("$.informationRequests[0].status").value("ANSWERED"));

        // Reserve revision, then approval above the handler's authority is refused
        call(priya, post("/api/claims/" + id + "/reserve"), Map.of("amount", "9300.00", "reason", "Repair quote received"))
                .andExpect(jsonPath("$.summary.currentReserve").value(9300.00));
        call(priya, post("/api/claims/" + id + "/approve"), Map.of("amount", "25000.00"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AUTHORITY_LIMIT_EXCEEDED"));

        // Settling before approval is an invalid transition
        call(priya, post("/api/claims/" + id + "/settle"), Map.of("paymentReference", "PAY-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));

        call(priya, post("/api/claims/" + id + "/approve"), Map.of("amount", "9300.00", "notes", "Repair quote verified"))
                .andExpect(jsonPath("$.summary.status").value("APPROVED"))
                .andExpect(jsonPath("$.decision.outcome").value("APPROVED"));
        call(priya, post("/api/claims/" + id + "/settle"), Map.of("paymentReference", "PAY-2026-0001"))
                .andExpect(jsonPath("$.summary.status").value("SETTLED"))
                .andExpect(jsonPath("$.summary.currentReserve").value(0))
                .andExpect(jsonPath("$.payments[0].amount").value(9300.00))
                .andExpect(jsonPath("$.allowedActions").isEmpty());

        // Claimant sees the outcome and the timeline, never the reserve history
        call(mei, get("/api/claims/" + id), null)
                .andExpect(jsonPath("$.summary.status").value("SETTLED"))
                .andExpect(jsonPath("$.decision.approvedAmount").value(9300.00))
                .andExpect(jsonPath("$.reserveHistory").doesNotExist())
                .andExpect(jsonPath("$.timeline.length()").value(6));

        // Every change produced exactly one outbox event, in order, with increasing claim versions
        List<Map<String, Object>> events = jdbc.queryForList(
                "SELECT event_type, (payload->'claim'->>'version')::bigint AS v FROM outbox_event "
                        + "WHERE aggregate_id = ?::uuid ORDER BY created_at", id);
        assertThat(events).extracting(e -> e.get("event_type")).containsExactly(
                "ClaimSubmitted", "ClaimAssigned", "InformationRequested", "InformationProvided",
                "ReserveChanged", "ClaimApproved", "ClaimSettled");
        assertThat(events).extracting(e -> ((Number) e.get("v")).longValue()).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void claimsAreInvisibleOutsideOwnershipAndTeam() throws Exception {
        String id = submitAsMei("1000.00", null);
        call(login(JACK), get("/api/claims/" + id), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
        call(login(SOPHIE), get("/api/claims/" + id), null).andExpect(status().isNotFound());
        call(login(SOPHIE), post("/api/claims/" + id + "/assign"), null).andExpect(status().isNotFound());
    }

    @Test
    void claimantCannotUseStaffActions() throws Exception {
        String id = submitAsMei("1000.00", null);
        call(login(MEI), post("/api/claims/" + id + "/approve"), Map.of("amount", "10.00"))
                .andExpect(status().isForbidden());
    }

    @Test
    void retryWithSameIdempotencyKeyReturnsTheSameClaim() throws Exception {
        String key = UUID.randomUUID().toString();
        String mei = login(MEI);
        String first = body(call(mei, post("/api/claims").header("Idempotency-Key", key), motorClaim("500.00"))
                .andExpect(status().isCreated())).at("/summary/id").asText();
        String second = body(call(mei, post("/api/claims").header("Idempotency-Key", key), motorClaim("500.00"))
                .andExpect(status().isOk())).at("/summary/id").asText();
        assertThat(second).isEqualTo(first);
    }

    @Test
    void submissionRulesAreEnforced() throws Exception {
        String mei = login(MEI);
        // someone else's policy looks like it doesn't exist
        Map<String, Object> jacksPolicy = new java.util.HashMap<>(motorClaim("100.00"));
        jacksPolicy.put("policyId", "50000000-0000-0000-0000-000000000003");
        call(mei, post("/api/claims"), jacksPolicy)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("POLICY_NOT_FOUND"));

        // motor policy with property details
        Map<String, Object> wrongDetails = new java.util.HashMap<>(motorClaim("100.00"));
        wrongDetails.remove("motor");
        wrongDetails.put("property", Map.of("propertyAddress", "1 Orchard Rd", "peril", "FIRE", "habitable", true));
        call(mei, post("/api/claims"), wrongDetails)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DETAILS_MISMATCH"));

        // structural validation
        call(mei, post("/api/claims"), Map.of("description", ""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/claims")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ helpers

    private String submitAsMei(String amount, String key) throws Exception {
        var req = post("/api/claims");
        if (key != null) {
            req.header("Idempotency-Key", key);
        }
        return body(call(login(MEI), req, motorClaim(amount)).andExpect(status().isCreated())).at("/summary/id").asText();
    }

    private static Map<String, Object> motorClaim(String amount) {
        return Map.of(
                "policyId", MEI_MOTOR_POLICY,
                "incidentDate", "2026-11-01",
                "description", "Rear-ended at traffic lights on Orchard Road",
                "claimedAmount", amount,
                "motor", Map.of("vehicleRegistration", "SGX1234A", "location", "Orchard Road",
                        "thirdPartyInvolved", true, "injuries", false));
    }

    private String login(String email) throws Exception {
        String res = mvc.perform(post("/api/auth/dev-login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("token").asText();
    }

    private ResultActions call(String token, MockHttpServletRequestBuilder req, Object body) throws Exception {
        req.header("Authorization", "Bearer " + token);
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(req);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static JsonNode find(JsonNode page, String id) {
        for (JsonNode n : page.get("content")) {
            if (n.get("id").asText().equals(id)) {
                return n;
            }
        }
        throw new AssertionError("claim " + id + " not in page");
    }
}
