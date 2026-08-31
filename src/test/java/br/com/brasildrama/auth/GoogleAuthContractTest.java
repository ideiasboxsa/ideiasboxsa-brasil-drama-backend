package br.com.brasildrama.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /v1/auth/google} era um stub que devolvia 501. O app já obtinha o ID
 * token pelo Credential Manager e o enviava; o servidor nunca validava nem criava
 * sessão. Login social não funcionava em ambiente nenhum.
 *
 * <p>Estes testes cobrem a vinculação de conta, que é onde um erro custa caro:
 * casar identidade do Google com conta local de forma errada é tomada de conta,
 * não bug de conveniência.
 */
@SpringBootTest(properties = {
    "spring.liquibase.contexts=base",
    "security.jwt.secret=test-secret-for-brasil-drama-must-have-32-bytes",
    "google.oauth.web-client-id=test-web-client-id.apps.googleusercontent.com"
})
@AutoConfigureMockMvc
class GoogleAuthContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserAccountRepository users;

    @MockBean GoogleIdentityVerifier googleIdentities;

    @Test
    void firstGoogleSignInCreatesAccountWithoutPassword() throws Exception {
        String email = "google-new-" + System.nanoTime() + "@example.com";
        String subject = "sub-" + System.nanoTime();
        stub(subject, email, "Maria Silva");

        var body = mvc.perform(googleAuth("token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.email").value(email))
            .andExpect(jsonPath("$.user.displayName").value("Maria Silva"))
            .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(body).get("accessToken").asText()).isNotBlank();

        var created = users.findByGoogleSubject(subject).orElseThrow();
        // Sem senha: a conta existe só via Google. login() já trata passwordHash
        // nulo como credencial inválida, então isso não abre caminho de entrada.
        assertThat(created.passwordHash).isNull();
    }

    @Test
    void secondSignInReusesTheSameAccount() throws Exception {
        String email = "google-repeat-" + System.nanoTime() + "@example.com";
        String subject = "sub-" + System.nanoTime();
        stub(subject, email, "Ana");

        String firstId = userIdFrom(mvc.perform(googleAuth("token-1")));
        String secondId = userIdFrom(mvc.perform(googleAuth("token-2")));

        assertThat(secondId).isEqualTo(firstId);
    }

    @Test
    void googleSignInAdoptsAccountCreatedWithEmailAndPassword() throws Exception {
        String email = "google-link-" + System.nanoTime() + "@example.com";

        // Conta nasce por e-mail e senha...
        String registeredId = userIdFrom(mvc.perform(post("/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"displayName":"Já Existia","email":"%s","password":"senha-segura-123"}
                """.formatted(email))));

        // ...e depois o mesmo dono entra pelo Google.
        String subject = "sub-" + System.nanoTime();
        stub(subject, email, "Já Existia");
        String googleId = userIdFrom(mvc.perform(googleAuth("token")));

        // Tem de ser a MESMA conta: caso contrário o usuário perde histórico,
        // carteira e direitos, ou a inserção colide na unicidade do e-mail.
        assertThat(googleId).isEqualTo(registeredId);
        assertThat(users.findByGoogleSubject(subject).orElseThrow().passwordHash)
            .as("adotar a conta não pode apagar a senha existente")
            .isNotNull();
    }

    @Test
    void changedGoogleEmailStillResolvesToTheSameAccount() throws Exception {
        String originalEmail = "google-moved-" + System.nanoTime() + "@example.com";
        String subject = "sub-stable-" + System.nanoTime();

        stub(subject, originalEmail, "Pessoa");
        String firstId = userIdFrom(mvc.perform(googleAuth("token")));

        // Mesmo `sub`, e-mail novo: a busca é por subject antes de e-mail, então
        // quem trocar o endereço no Google continua na mesma conta.
        stub(subject, "google-moved-new-" + System.nanoTime() + "@example.com", "Pessoa");
        String secondId = userIdFrom(mvc.perform(googleAuth("token")));

        assertThat(secondId).isEqualTo(firstId);
    }

    private void stub(String subject, String email, String name) {
        when(googleIdentities.verify(anyString()))
            .thenReturn(new GoogleIdentityVerifier.GoogleIdentity(subject, email, name, null));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder googleAuth(String token) {
        return post("/v1/auth/google")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"idToken":"%s"}
                """.formatted(token));
    }

    private String userIdFrom(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        String body = actions.andExpect(status().is2xxSuccessful())
            .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        return json.get("user").get("id").asText();
    }
}
