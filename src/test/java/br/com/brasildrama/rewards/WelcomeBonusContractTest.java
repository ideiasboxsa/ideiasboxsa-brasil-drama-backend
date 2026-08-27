package br.com.brasildrama.rewards;

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

@SpringBootTest(properties = {
    "spring.liquibase.contexts=base",
    "security.jwt.secret=test-secret-for-brasil-drama-must-have-32-bytes",
    "rewards.welcome.enabled=true",
    "rewards.welcome.bonus=100"
})
@AutoConfigureMockMvc
class WelcomeBonusContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void registrationGrantsWelcomeBonusExactlyOnce() throws Exception {
        var email = "welcome-" + System.nanoTime() + "@example.com";
        var body = mvc.perform(post("/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"displayName":"Welcome Test","email":"%s","password":"senha-segura-123"}
                    """.formatted(email)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(body);
        var token = json.get("accessToken").asText();
        org.junit.jupiter.api.Assertions.assertTrue(json.get("welcomeBonusGranted").asBoolean());
        org.junit.jupiter.api.Assertions.assertEquals(100, json.get("welcomeBonusAmount").asLong());

        mvc.perform(get("/v1/rewards/overview").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bonusBalance").value(100));

        var secondLogin = mvc.perform(post("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"senha-segura-123"}
                    """.formatted(email)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode secondJson = objectMapper.readTree(secondLogin);
        var secondToken = secondJson.get("accessToken").asText();
        org.junit.jupiter.api.Assertions.assertFalse(secondJson.get("welcomeBonusGranted").asBoolean());
        org.junit.jupiter.api.Assertions.assertEquals(0, secondJson.get("welcomeBonusAmount").asLong());

        mvc.perform(get("/v1/rewards/overview").header("Authorization", "Bearer " + secondToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bonusBalance").value(100));

        mvc.perform(post("/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"displayName":"Welcome Test","email":"%s","password":"senha-segura-123"}
                    """.formatted(email)))
            .andExpect(status().isConflict());

        mvc.perform(get("/v1/rewards/overview").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bonusBalance").value(100));
    }
}
