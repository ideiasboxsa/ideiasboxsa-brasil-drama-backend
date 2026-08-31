package br.com.brasildrama.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.liquibase.contexts=dev",
    "security.jwt.secret=test-secret-for-brasil-drama-must-have-32-bytes"
})
@AutoConfigureMockMvc
class PublicCatalogContractTest {
    @Autowired MockMvc mvc;

    @Test
    void homeIsPublicAndContainsDirectPlaybackEpisodeId() throws Exception {
        mvc.perform(get("/v1/home"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.heroDramaId").isNotEmpty())
            .andExpect(jsonPath("$.sections[*].type", hasItem("NEW_RELEASES")))
            .andExpect(jsonPath("$.sections[0].items[0].dramaId").isNotEmpty())
            .andExpect(jsonPath("$.sections[0].items[0].episodeId").isNotEmpty());
    }

    @Test
    void dramaDetailMatchesAndroidEpisodeContract() throws Exception {
        mvc.perform(get("/v1/catalog/dramas/11111111-1111-1111-1111-111111111111"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("11111111-1111-1111-1111-111111111111"))
            .andExpect(jsonPath("$.episodes[0].number").value(1))
            .andExpect(jsonPath("$.episodes[0].free").value(true))
            .andExpect(jsonPath("$.episodes[0].videoUrl").isNotEmpty())
            .andExpect(jsonPath("$.episodes[1].coinPrice").value(30))
            .andExpect(jsonPath("$.episodes[1].free").value(false))
            .andExpect(jsonPath("$.episodes[1].videoUrl").isNotEmpty());
    }

    @Test
    void searchAndCategoriesArePublic() throws Exception {
        mvc.perform(get("/v1/catalog/search").param("q", "Família").param("limit", "20").param("offset", "0"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].title").value("Segredo de Família"));

        mvc.perform(get("/v1/catalog/categories"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].slug").exists())
            .andExpect(jsonPath("$[0].name").exists());
    }

    @Test
    void categoryDramaListingIsPublicAndReturnsPublishedCatalogShape() throws Exception {
        mvc.perform(get("/v1/catalog/categories/drama/dramas"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").isNotEmpty())
            .andExpect(jsonPath("$[0].title").isNotEmpty())
            .andExpect(jsonPath("$[0].genre").value("Drama"));
    }
}
