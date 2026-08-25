package br.com.brasildrama.library;

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
    "spring.liquibase.contexts=base,dev",
    "security.jwt.secret=test-secret-for-brasil-drama-must-have-32-bytes"
})
@AutoConfigureMockMvc
class LibraryContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    private static final String DRAMA = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_DRAMA = "22222222-2222-2222-2222-222222222222";
    private static final String EP1 = "11111111-1111-1111-1111-111111111101";
    private static final String EP2 = "11111111-1111-1111-1111-111111111102";
    private static final String OTHER_EP = "22222222-2222-2222-2222-222222222201";

    @Test
    void favoritesAreIdempotentAndIsolatedByAuthenticatedUser() throws Exception {
        var first = token("favorite-a-");
        var second = token("favorite-b-");

        mvc.perform(put("/v1/me/favorites/" + DRAMA).header("Authorization", bearer(first)))
            .andExpect(status().isNoContent());
        mvc.perform(put("/v1/me/favorites/" + DRAMA).header("Authorization", bearer(first)))
            .andExpect(status().isNoContent());

        mvc.perform(get("/v1/me/favorites").header("Authorization", bearer(first)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].dramaId").value(DRAMA));

        mvc.perform(get("/v1/me/favorites").header("Authorization", bearer(second)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(delete("/v1/me/favorites/" + DRAMA).header("Authorization", bearer(first)))
            .andExpect(status().isNoContent());
    }

    @Test
    void historyUpsertsCurrentDramaAndFeedsContinueWatching() throws Exception {
        var token = token("history-");

        mvc.perform(put("/v1/me/history/" + DRAMA)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"episodeId\":\"" + EP1 + "\",\"positionMs\":12000,\"durationMs\":60000}"))
            .andExpect(status().isNoContent());

        mvc.perform(get("/v1/me/history").header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].dramaId").value(DRAMA))
            .andExpect(jsonPath("$[0].episodeId").value(EP1))
            .andExpect(jsonPath("$[0].positionMs").value(12000));

        mvc.perform(get("/v1/me/continue-watching").header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].dramaId").value(DRAMA))
            .andExpect(jsonPath("$.items[0].episodeId").value(EP1))
            .andExpect(jsonPath("$.items[0].dramaTitle").value("Segredo de Família"));

        mvc.perform(put("/v1/me/history/" + DRAMA)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"episodeId\":\"" + EP2 + "\",\"positionMs\":59000,\"durationMs\":60000}"))
            .andExpect(status().isNoContent());

        mvc.perform(get("/v1/me/history").header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].episodeId").value(EP2));

        mvc.perform(get("/v1/me/continue-watching").header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void progressRejectsEpisodeFromAnotherDrama() throws Exception {
        var token = token("invalid-progress-");

        mvc.perform(put("/v1/me/history/" + DRAMA)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"episodeId\":\"" + OTHER_EP + "\",\"positionMs\":1000,\"durationMs\":60000}"))
            .andExpect(status().isBadRequest());
    }

    private String token(String prefix) throws Exception {
        var email = prefix + System.nanoTime() + "@example.com";
        var body = mvc.perform(post("/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"displayName":"Library Test","email":"%s","password":"senha-segura-123"}
                    """.formatted(email)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
