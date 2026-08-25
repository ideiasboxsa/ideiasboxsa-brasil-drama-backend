package br.com.brasildrama.rewards;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.liquibase.contexts=base",
    "security.jwt.secret=test-secret-for-brasil-drama-must-have-32-bytes",
    "rewards.ads.daily-limit=2",
    "rewards.ads.bonus=20",
    "rewards.welcome.enabled=false"
})
@AutoConfigureMockMvc
class RewardedAdContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void claimRequiresVerifiedSsvAndIsIdempotent() throws Exception {
        var user = register("ad-owner");
        var operationKey = createSession(user.token());

        mvc.perform(post("/v1/rewards/ads/claim")
                .header("Authorization", bearer(user.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationKey\":\"" + operationKey + "\"}"))
            .andExpect(status().isConflict());

        verifySession(operationKey, "tx-" + System.nanoTime());

        mvc.perform(post("/v1/rewards/ads/claim")
                .header("Authorization", bearer(user.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationKey\":\"" + operationKey + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accepted").value(true))
            .andExpect(jsonPath("$.bonusBalance").value(20));

        mvc.perform(post("/v1/rewards/ads/claim")
                .header("Authorization", bearer(user.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationKey\":\"" + operationKey + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accepted").value(false))
            .andExpect(jsonPath("$.bonusBalance").value(20));
    }

    @Test
    void sessionIsBoundToOwnerAndDailyLimitStopsFurtherSessions() throws Exception {
        var owner = register("ad-owner2");
        var attacker = register("ad-attacker");
        var first = createSession(owner.token());
        verifySession(first, "tx-first-" + System.nanoTime());

        mvc.perform(post("/v1/rewards/ads/claim")
                .header("Authorization", bearer(attacker.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationKey\":\"" + first + "\"}"))
            .andExpect(status().isForbidden());

        claim(owner.token(), first, 20);

        var second = createSession(owner.token());
        verifySession(second, "tx-second-" + System.nanoTime());
        claim(owner.token(), second, 40);

        mvc.perform(post("/v1/rewards/ads/session").header("Authorization", bearer(owner.token())))
            .andExpect(status().isTooManyRequests());
    }

    private void claim(String token, String operationKey, int expectedBonus) throws Exception {
        mvc.perform(post("/v1/rewards/ads/claim")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationKey\":\"" + operationKey + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bonusBalance").value(expectedBonus));
    }

    private String createSession(String token) throws Exception {
        var response = mvc.perform(post("/v1/rewards/ads/session").header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.operationKey").isNotEmpty())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("operationKey").asText();
    }

    private void verifySession(String operationKey, String transactionId) {
        jdbc.update(
            "update rewarded_ad_session set verified_at=now(), transaction_id=? where operation_key=?",
            transactionId, operationKey
        );
    }

    private Session register(String prefix) throws Exception {
        var email = prefix + "-" + System.nanoTime() + "@example.com";
        var response = mvc.perform(post("/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"displayName":"Ad Test","email":"%s","password":"senha-segura-123"}
                    """.formatted(email)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        return new Session(json.get("accessToken").asText());
    }

    private static String bearer(String token) { return "Bearer " + token; }
    private record Session(String token) {}
}
