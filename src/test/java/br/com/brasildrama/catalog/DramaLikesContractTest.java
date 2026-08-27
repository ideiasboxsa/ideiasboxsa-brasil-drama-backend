package br.com.brasildrama.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.liquibase.contexts=base",
    "security.jwt.secret=test-secret-for-brasil-drama-must-have-32-bytes"
})
@AutoConfigureMockMvc
class DramaLikesContractTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void visitorCanLikeIdempotentlyAndUnlike() throws Exception {
        UUID dramaId = UUID.randomUUID();
        jdbc.update("insert into drama(id,title,synopsis,genre) values (?,?,?,?)", dramaId, "Like Test", "Synopsis", "Drama");
        String visitor = "visitor-contract-1234567890";

        mvc.perform(put("/v1/catalog/dramas/{id}/likes", dramaId).header("X-Visitor-ID", visitor))
            .andExpect(status().isOk()).andExpect(jsonPath("$.liked").value(true)).andExpect(jsonPath("$.count").value(1));
        mvc.perform(put("/v1/catalog/dramas/{id}/likes", dramaId).header("X-Visitor-ID", visitor))
            .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));
        mvc.perform(delete("/v1/catalog/dramas/{id}/likes", dramaId).header("X-Visitor-ID", visitor))
            .andExpect(status().isOk()).andExpect(jsonPath("$.liked").value(false)).andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void rejectsInvalidAnonymousPrincipal() throws Exception {
        UUID dramaId = UUID.randomUUID();
        jdbc.update("insert into drama(id,title,synopsis,genre) values (?,?,?,?)", dramaId, "Invalid Visitor", "Synopsis", "Drama");

        mvc.perform(put("/v1/catalog/dramas/{id}/likes", dramaId).header("X-Visitor-ID", "short"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400));
    }
}
