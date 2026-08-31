package br.com.brasildrama.monetization;

import br.com.brasildrama.wallet.WalletCreditService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * A Google reembolsa automaticamente compras não confirmadas em 3 dias.
 *
 * Antes destas correções a confirmação existia apenas no cliente, sem
 * retentativa: se a chamada falhasse logo após a compra — o momento mais provável
 * de instabilidade em rede móvel — a receita era estornada e o servidor não tinha
 * como perceber. E {@code restore} chamava {@code verify} por auto-invocação, o
 * que anulava o {@code @Transactional}: falhar na 47ª de 100 compras deixava as 46
 * anteriores gravadas e as demais não, sem compensação.
 */
@SpringBootTest(properties = {
    "spring.liquibase.contexts=base",
    "security.jwt.secret=test-secret-for-brasil-drama-must-have-32-bytes",
    "google.play.package-name=br.com.brasildrama.app"
})
@AutoConfigureMockMvc
class GooglePurchaseAcknowledgeContractTest {
    private static final String PACKAGE = "br.com.brasildrama.app";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired WalletCreditService wallet;

    @MockBean GooglePlayVerifier verifier;

    @BeforeEach
    void configureVerifier() {
        when(verifier.expectedPackageName()).thenReturn(PACKAGE);
        when(verifier.isConfigured()).thenReturn(true);
    }

    @Test
    void unacknowledgedPurchaseIsConfirmedByTheServer() throws Exception {
        var session = register("ack-server-" + System.nanoTime() + "@example.com");
        String token = "token-" + UUID.randomUUID();

        // A Google diz que a compra é válida mas ainda NÃO está confirmada:
        // exatamente o caso em que o estorno automático acontece em 3 dias.
        when(verifier.verify(eq(PACKAGE), any(CommercialProductEntity.class), eq(token)))
            .thenReturn(new PlayVerification(true, false, "GPA.ACK-1", null));
        when(verifier.acknowledge(eq(PACKAGE), eq("brasil_drama_coins_100"), eq("COIN_PACK"), eq(token)))
            .thenReturn(true);

        mvc.perform(post("/v1/purchases/google/verify")
                .header("Authorization", bearer(session.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(request("brasil_drama_coins_100", token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.valid").value(true))
            .andExpect(jsonPath("$.acknowledged").value(true));

        verify(verifier, times(1)).acknowledge(eq(PACKAGE), eq("brasil_drama_coins_100"), eq("COIN_PACK"), eq(token));
    }

    @Test
    void alreadyAcknowledgedPurchaseDoesNotCallGoogleAgain() throws Exception {
        var session = register("ack-skip-" + System.nanoTime() + "@example.com");
        String token = "token-" + UUID.randomUUID();

        when(verifier.verify(eq(PACKAGE), any(CommercialProductEntity.class), eq(token)))
            .thenReturn(new PlayVerification(true, true, "GPA.ACK-2", null));

        mvc.perform(post("/v1/purchases/google/verify")
                .header("Authorization", bearer(session.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(request("brasil_drama_coins_100", token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.acknowledged").value(true));

        verify(verifier, never()).acknowledge(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void failedAcknowledgementDoesNotRevokeTheGrantedRight() throws Exception {
        var session = register("ack-fail-" + System.nanoTime() + "@example.com");
        int initialBalance = wallet.balance(session.userId());
        String token = "token-" + UUID.randomUUID();

        when(verifier.verify(eq(PACKAGE), any(CommercialProductEntity.class), eq(token)))
            .thenReturn(new PlayVerification(true, false, "GPA.ACK-3", null));
        when(verifier.acknowledge(anyString(), anyString(), anyString(), anyString())).thenReturn(false);

        // O usuário pagou: a moeda tem de ser creditada mesmo que a confirmação
        // falhe. O recibo fica pendente e a rotina de retentativa cuida do resto.
        mvc.perform(post("/v1/purchases/google/verify")
                .header("Authorization", bearer(session.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(request("brasil_drama_coins_100", token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.valid").value(true))
            .andExpect(jsonPath("$.acknowledged").value(false));

        assertThat(wallet.balance(session.userId())).isEqualTo(initialBalance + 100);
    }

    @Test
    void restoreKeepsGoingWhenOnePurchaseFails() throws Exception {
        var session = register("restore-partial-" + System.nanoTime() + "@example.com");
        int initialBalance = wallet.balance(session.userId());
        String good = "token-good-" + UUID.randomUUID();
        String bad = "token-bad-" + UUID.randomUUID();
        String alsoGood = "token-also-good-" + UUID.randomUUID();

        when(verifier.verify(eq(PACKAGE), any(CommercialProductEntity.class), eq(good)))
            .thenReturn(new PlayVerification(true, true, "GPA.R1", null));
        when(verifier.verify(eq(PACKAGE), any(CommercialProductEntity.class), eq(bad)))
            .thenThrow(new IllegalStateException("Google Play fora do ar"));
        when(verifier.verify(eq(PACKAGE), any(CommercialProductEntity.class), eq(alsoGood)))
            .thenReturn(new PlayVerification(true, true, "GPA.R2", null));

        // A falha no meio da lista não pode abortar a restauração nem deixar as
        // compras seguintes por processar.
        mvc.perform(post("/v1/purchases/google/restore")
                .header("Authorization", bearer(session.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"purchases":[%s,%s,%s]}
                    """.formatted(
                        request("brasil_drama_coins_100", good),
                        request("brasil_drama_coins_300", bad),
                        request("brasil_drama_coins_700", alsoGood)
                    )))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.restored").value(2));

        // As duas compras válidas foram creditadas; a que falhou, não.
        assertThat(wallet.balance(session.userId())).isEqualTo(initialBalance + 100 + 700);
    }

    private String request(String productId, String token) {
        return """
            {"productIds":["%s"],"purchaseToken":"%s","packageName":"%s"}
            """.formatted(productId, token, PACKAGE).strip();
    }

    private Session register(String email) throws Exception {
        var response = mvc.perform(post("/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"displayName":"Ack Test","email":"%s","password":"senha-segura-123"}
                    """.formatted(email)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        return new Session(json.get("accessToken").asText(), UUID.fromString(json.get("user").get("id").asText()));
    }

    private static String bearer(String token) { return "Bearer " + token; }
    private record Session(String token, UUID userId) {}
}
