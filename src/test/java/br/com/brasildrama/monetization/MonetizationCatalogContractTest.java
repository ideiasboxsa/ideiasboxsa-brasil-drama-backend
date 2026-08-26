package br.com.brasildrama.monetization;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.liquibase.contexts=base",
    "security.jwt.secret=test-secret-for-brasil-drama-must-have-32-bytes"
})
@AutoConfigureMockMvc
class MonetizationCatalogContractTest {
    @Autowired MockMvc mvc;
    @Autowired CommercialProductRepository products;

    @Test
    void publicCatalogContainsOnlyActiveProducts() throws Exception {
        mvc.perform(get("/v1/monetization/catalog"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.subscriptions.length()").value(4))
            .andExpect(jsonPath("$.coinPacks.length()").value(4))
            .andExpect(jsonPath("$.subscriptions[0].productId").value("brasil_drama_daily"))
            .andExpect(jsonPath("$.coinPacks[0].productId").value("brasil_drama_coins_100"));
    }

    @Test
    @Transactional
    @Rollback
    void disabledProductIsImmediatelyRemovedFromPublicCatalog() throws Exception {
        var product = products.findById("brasil_drama_coins_100").orElseThrow();
        product.active = false;
        products.saveAndFlush(product);

        mvc.perform(get("/v1/monetization/catalog"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.coinPacks.length()").value(3))
            .andExpect(jsonPath("$.coinPacks[?(@.productId == 'brasil_drama_coins_100')]").isEmpty());
    }

    @Test
    void administrativeCatalogRequiresAdminToken() throws Exception {
        mvc.perform(get("/v1/admin/monetization/catalog"))
            .andExpect(status().isForbidden());
    }
}
