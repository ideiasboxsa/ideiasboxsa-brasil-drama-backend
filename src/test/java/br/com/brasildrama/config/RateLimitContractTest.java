package br.com.brasildrama.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Antes desta proteção {@code /v1/auth/register} era criação de conta em massa sem
 * limite, e cada conta levava o bônus de boas-vindas: emitir um milhão de moedas
 * era um laço de dez linhas. O custo não é só o conteúdo liberado — é a corrupção
 * das métricas de conversão e ARPU, que depois não dá para separar do sinal real.
 *
 * <p>A suíte roda com o filtro desligado por padrão (ver
 * {@code src/test/resources/application.properties}), porque os cadastros
 * espalhados pelos outros testes estourariam o limite. Esta classe o religa com
 * limites mínimos, para exercitar o comportamento de verdade.
 */
@SpringBootTest(properties = {
    "spring.liquibase.contexts=base",
    "security.jwt.secret=test-secret-for-brasil-drama-must-have-32-bytes",
    "brasil-drama.rate-limit.enabled=true",
    "brasil-drama.rate-limit.register-per-hour=2",
    "brasil-drama.rate-limit.auth-per-minute=3"
})
@AutoConfigureMockMvc
class RateLimitContractTest {
    @Autowired MockMvc mvc;

    @Test
    void registrationIsCappedPerClient() throws Exception {
        for (int attempt = 1; attempt <= 2; attempt++) {
            mvc.perform(register("rate-limit-" + System.nanoTime() + "-" + attempt + "@example.com"))
                .andExpect(status().isCreated());
        }

        // A terceira tentativa é barrada antes de chegar ao serviço, mesmo sendo
        // um cadastro perfeitamente válido.
        mvc.perform(register("rate-limit-blocked-" + System.nanoTime() + "@example.com"))
            .andExpect(status().is(429))
            .andExpect(header().exists("Retry-After"));
    }

    @Test
    void loginAttemptsAreCappedIndependentlyOfOutcome() throws Exception {
        // Credencial errada de propósito: o limite tem de valer para a tentativa,
        // não para o sucesso — senão não protege contra força bruta.
        for (int attempt = 1; attempt <= 3; attempt++) {
            mvc.perform(post("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"nao-existe@example.com","password":"senha-errada-123"}
                    """));
        }

        mvc.perform(post("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"nao-existe@example.com","password":"senha-errada-123"}
                    """))
            .andExpect(status().is(429));
    }

    @Test
    void publicCatalogIsNotRateLimited() throws Exception {
        // O limite existe para endpoints que custam dinheiro ou emitem crédito.
        // Navegação de catálogo é o caminho quente do app e não pode ser barrada.
        for (int attempt = 1; attempt <= 30; attempt++) {
            mvc.perform(get("/v1/catalog/dramas")).andExpect(status().isOk());
        }
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder register(String email) {
        return post("/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"displayName":"Rate Limit","email":"%s","password":"senha-segura-123"}
                """.formatted(email));
    }
}
