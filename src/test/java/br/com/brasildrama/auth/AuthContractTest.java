package br.com.brasildrama.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.liquibase.contexts=base", "security.jwt.secret=test-secret-for-brasil-drama-must-have-32-bytes"})
@AutoConfigureMockMvc
class AuthContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void registerTokenProfileAndPreferencesRoundTrip() throws Exception {
        var email = "auth-" + System.nanoTime() + "@example.com";
        var register = mvc.perform(post("/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"displayName":"Teste Drama","email":"%s","password":"senha-segura-123"}
                    """.formatted(email)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.user.email").value(email))
            .andReturn().getResponse().getContentAsString();

        JsonNode payload = objectMapper.readTree(register);
        var token = payload.get("accessToken").asText();

        mvc.perform(get("/v1/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value(email))
            .andExpect(jsonPath("$.displayName").value("Teste Drama"));

        mvc.perform(put("/v1/me/playback-preferences")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"autoplay\":false,\"allowMobileData\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.autoplay").value(false))
            .andExpect(jsonPath("$.allowMobileData").value(false));

        mvc.perform(post("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"senha-segura-123"}
                    """.formatted(email)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void protectedProfileRejectsMissingTokenAndInvalidPassword() throws Exception {
        mvc.perform(get("/v1/me")).andExpect(status().isForbidden());

        mvc.perform(post("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nobody@example.com\",\"password\":\"wrong-password\"}"))
            .andExpect(status().isUnauthorized());
    }
}
