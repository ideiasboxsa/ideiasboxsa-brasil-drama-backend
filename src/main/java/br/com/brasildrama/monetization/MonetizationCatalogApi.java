package br.com.brasildrama.monetization;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

record CommercialProduct(
    String productId,
    String type,
    String title,
    String description,
    int coins,
    boolean active,
    int displayOrder
) {}

record MonetizationCatalogView(List<CommercialProduct> subscriptions, List<CommercialProduct> coinPacks) {}

@RestController
class MonetizationCatalogApi {
    private static final MonetizationCatalogView CATALOG = new MonetizationCatalogView(
        List.of(
            subscription("brasil_drama_daily", "Plano diário", 1),
            subscription("brasil_drama_weekly", "Plano semanal", 2),
            subscription("brasil_drama_monthly", "Plano mensal", 3),
            subscription("brasil_drama_annual", "Plano anual", 4)
        ),
        List.of(
            coins("brasil_drama_coins_100", "100 moedas", 100, 1),
            coins("brasil_drama_coins_300", "300 moedas", 300, 2),
            coins("brasil_drama_coins_700", "700 moedas", 700, 3),
            coins("brasil_drama_coins_1500", "1.500 moedas", 1500, 4)
        )
    );

    @GetMapping("/v1/monetization/catalog")
    MonetizationCatalogView publicCatalog() {
        return CATALOG;
    }

    @GetMapping("/v1/admin/monetization/catalog")
    MonetizationCatalogView adminCatalog() {
        return CATALOG;
    }

    private static CommercialProduct subscription(String id, String title, int order) {
        return new CommercialProduct(id, "SUBSCRIPTION", title,
            "Acesso Premium Brasil Drama. O preço final é informado pela Google Play.", 0, true, order);
    }

    private static CommercialProduct coins(String id, String title, int amount, int order) {
        return new CommercialProduct(id, "COIN_PACK", title,
            "Pacote consumível creditado após validação da compra.", amount, true, order);
    }
}
