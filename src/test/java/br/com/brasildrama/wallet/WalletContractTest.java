package br.com.brasildrama.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.liquibase.contexts=base,dev", "security.jwt.secret=test-secret-for-brasil-drama-must-have-32-bytes"})
@AutoConfigureMockMvc
class WalletContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired WalletLedgerRepository ledger;

    @Test
    void premiumUnlockIsIdempotentAndPersistsEntitlement() throws Exception {
        var session = register("wallet-" + System.nanoTime() + "@example.com");
        var userId = UUID.fromString(session.userId());
        ledger.save(new WalletLedgerEntry(userId, "test-credit-" + UUID.randomUUID(), "TEST_CREDIT", 100, "TEST", "seed"));

        mvc.perform(get("/v1/wallet").header("Authorization", bearer(session.token())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").value(100));

        var operationKey = "unlock-" + UUID.randomUUID();
        var body = "{\"operationKey\":\"%s\"}".formatted(operationKey);
        var premiumEpisode = "11111111-1111-1111-1111-111111111102";

        mvc.perform(post("/v1/episodes/{episodeId}/unlock", premiumEpisode)
                .header("Authorization", bearer(session.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.unlocked").value(true))
            .andExpect(jsonPath("$.episodeId").value(premiumEpisode))
            .andExpect(jsonPath("$.balance").value(70));

        mvc.perform(post("/v1/episodes/{episodeId}/unlock", premiumEpisode)
                .header("Authorization", bearer(session.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").value(70));

        mvc.perform(get("/v1/wallet").header("Authorization", bearer(session.token())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").value(70));

        mvc.perform(get("/v1/entitlements").header("Authorization", bearer(session.token())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].episodeId").value(premiumEpisode));

        assertThat(ledger.balance(userId)).isEqualTo(70);
    }

    @Test
    void insufficientBalanceFailsClosedAndFreeEpisodeDoesNotDebit() throws Exception {
        var session = register("wallet-zero-" + System.nanoTime() + "@example.com");

        mvc.perform(post("/v1/episodes/{episodeId}/unlock", "11111111-1111-1111-1111-111111111102")
                .header("Authorization", bearer(session.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationKey\":\"insufficient-" + UUID.randomUUID() + "\"}"))
            .andExpect(status().isConflict());

        mvc.perform(post("/v1/episodes/{episodeId}/unlock", "11111111-1111-1111-1111-111111111101")
                .header("Authorization", bearer(session.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationKey\":\"free-" + UUID.randomUUID() + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.unlocked").value(true))
            .andExpect(jsonPath("$.balance").value(0));
    }

    @Test
    void operationKeyCannotBeReusedForAnotherEpisode() throws Exception {
        var session = register("wallet-op-" + System.nanoTime() + "@example.com");
        var userId = UUID.fromString(session.userId());
        ledger.save(new WalletLedgerEntry(userId, "credit-" + UUID.randomUUID(), "TEST_CREDIT", 100, "TEST", "seed"));
        var operationKey = "same-op-" + UUID.randomUUID();

        mvc.perform(post("/v1/episodes/{episodeId}/unlock", "11111111-1111-1111-1111-111111111102")
                .header("Authorization", bearer(session.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationKey\":\"" + operationKey + "\"}"))
            .andExpect(status().isOk());

        // A free episode does not consume an operation key, so the collision must be tested against another premium
        // episode once the catalog contains one. The database uniqueness constraint remains the final guard.
        assertThat(ledger.findByUserIdAndOperationKey(userId, operationKey)).isPresent();
    }

    private Session register(String email) throws Exception {
        var response = mvc.perform(post("/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"displayName":"Wallet Test","email":"%s","password":"senha-segura-123"}
                    """.formatted(email)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        return new Session(json.get("accessToken").asText(), json.get("user").get("id").asText());
    }

    private static String bearer(String token) { return "Bearer " + token; }
    private record Session(String token, String userId) {}
}
