package br.com.brasildrama.monetization;

import br.com.brasildrama.wallet.WalletCreditService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.liquibase.contexts=base",
    "security.jwt.secret=test-secret-for-brasil-drama-must-have-32-bytes",
    "google.play.package-name=br.com.brasildrama.app"
})
@AutoConfigureMockMvc
class GooglePurchaseContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired WalletCreditService wallet;

    @MockBean GooglePlayVerifier verifier;

    @Test
    void coinPurchaseCreditsExactlyOnceAcrossRetries() throws Exception {
        var session = register("purchase-" + System.nanoTime() + "@example.com");
        int initialBalance = wallet.balance(session.userId());
        String token = "google-token-" + UUID.randomUUID();

        when(verifier.expectedPackageName()).thenReturn("br.com.brasildrama.app");
        when(verifier.verify(eq("br.com.brasildrama.app"), any(CommercialProductEntity.class), eq(token)))
            .thenReturn(new PlayVerification(true, true, "GPA.TEST-COINS", null));

        var body = request("brasil_drama_coins_100", token);

        mvc.perform(post("/v1/purchases/google/verify")
                .header("Authorization", bearer(session.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.valid").value(true))
            .andExpect(jsonPath("$.consumable").value(true))
            .andExpect(jsonPath("$.balance").value(initialBalance + 100));

        mvc.perform(post("/v1/purchases/google/verify")
                .header("Authorization", bearer(session.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").value(initialBalance + 100));

        assertThat(wallet.balance(session.userId())).isEqualTo(initialBalance + 100);
        verify(verifier, times(1)).verify(eq("br.com.brasildrama.app"), any(CommercialProductEntity.class), eq(token));
    }

    @Test
    void purchaseTokenCannotBeAttachedToAnotherUser() throws Exception {
        var owner = register("purchase-owner-" + System.nanoTime() + "@example.com");
        var attacker = register("purchase-attacker-" + System.nanoTime() + "@example.com");
        String token = "shared-google-token-" + UUID.randomUUID();

        when(verifier.expectedPackageName()).thenReturn("br.com.brasildrama.app");
        when(verifier.verify(eq("br.com.brasildrama.app"), any(CommercialProductEntity.class), eq(token)))
            .thenReturn(new PlayVerification(true, true, "GPA.TEST-OWNER", null));

        mvc.perform(post("/v1/purchases/google/verify")
                .header("Authorization", bearer(owner.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(request("brasil_drama_coins_300", token)))
            .andExpect(status().isOk());

        mvc.perform(post("/v1/purchases/google/verify")
                .header("Authorization", bearer(attacker.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(request("brasil_drama_coins_300", token)))
            .andExpect(status().isConflict());

        assertThat(wallet.balance(attacker.userId())).isLessThan(wallet.balance(owner.userId()));
        verify(verifier, times(1)).verify(eq("br.com.brasildrama.app"), any(CommercialProductEntity.class), eq(token));
    }

    @Test
    void invalidAndroidPackageFailsBeforeCallingGoogle() throws Exception {
        var session = register("purchase-package-" + System.nanoTime() + "@example.com");
        when(verifier.expectedPackageName()).thenReturn("br.com.brasildrama.app");

        mvc.perform(post("/v1/purchases/google/verify")
                .header("Authorization", bearer(session.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productIds":["brasil_drama_coins_100"],"purchaseToken":"token","packageName":"com.fake.app"}
                    """))
            .andExpect(status().isBadRequest());

        verify(verifier, never()).verify(anyString(), any(CommercialProductEntity.class), anyString());
    }

    private String request(String productId, String token) {
        return """
            {"productIds":["%s"],"purchaseToken":"%s","packageName":"br.com.brasildrama.app"}
            """.formatted(productId, token);
    }

    private Session register(String email) throws Exception {
        var response = mvc.perform(post("/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"displayName":"Purchase Test","email":"%s","password":"senha-segura-123"}
                    """.formatted(email)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        return new Session(json.get("accessToken").asText(), UUID.fromString(json.get("user").get("id").asText()));
    }

    private static String bearer(String token) { return "Bearer " + token; }
    private record Session(String token, UUID userId) {}
}
