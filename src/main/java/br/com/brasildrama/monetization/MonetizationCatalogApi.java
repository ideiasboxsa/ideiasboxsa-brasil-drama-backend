package br.com.brasildrama.monetization;

import jakarta.persistence.*;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

@Entity
@Table(name = "commercial_product")
class CommercialProductEntity {
    @Id
    @Column(name = "product_id", length = 120)
    String productId;

    @Column(name = "product_type", nullable = false, length = 30)
    String type;

    @Column(nullable = false, length = 120)
    String title;

    @Column(nullable = false, length = 500)
    String description;

    @Column(nullable = false)
    int coins;

    @Column(nullable = false)
    boolean active;

    @Column(name = "display_order", nullable = false)
    int displayOrder;

    @Column(name = "updated_at", nullable = false)
    OffsetDateTime updatedAt;

    protected CommercialProductEntity() {}
}

interface CommercialProductRepository extends JpaRepository<CommercialProductEntity, String> {
    List<CommercialProductEntity> findAllByOrderByTypeAscDisplayOrderAsc();
}

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
record AdminMonetizationCatalogView(
    List<CommercialProduct> subscriptions,
    List<CommercialProduct> coinPacks,
    boolean googlePlayConfigured,
    String googlePlayPackageName
) {}
record CommercialProductUpdate(boolean active, @Min(1) int displayOrder) {}

@RestController
class MonetizationCatalogApi {
    private final CommercialProductRepository products;
    private final GooglePlayVerifier googlePlay;

    MonetizationCatalogApi(CommercialProductRepository products, GooglePlayVerifier googlePlay) {
        this.products = products;
        this.googlePlay = googlePlay;
    }

    @GetMapping("/v1/monetization/catalog")
    MonetizationCatalogView publicCatalog() {
        return catalog(true);
    }

    @GetMapping("/v1/admin/monetization/catalog")
    AdminMonetizationCatalogView adminCatalog() {
        var catalog = catalog(false);
        return new AdminMonetizationCatalogView(
            catalog.subscriptions(),
            catalog.coinPacks(),
            googlePlay.isConfigured(),
            googlePlay.expectedPackageName()
        );
    }

    @PutMapping("/v1/admin/monetization/catalog/{productId}")
    @Transactional
    public ResponseEntity<?> update(
        @PathVariable String productId,
        @Valid @RequestBody CommercialProductUpdate request
    ) {
        var product = products.findById(productId).orElse(null);
        if (product == null) return ResponseEntity.notFound().build();
        product.active = request.active();
        product.displayOrder = request.displayOrder();
        product.updatedAt = OffsetDateTime.now();
        products.save(product);
        return ResponseEntity.ok(view(product));
    }

    private MonetizationCatalogView catalog(boolean activeOnly) {
        var all = products.findAllByOrderByTypeAscDisplayOrderAsc().stream()
            .filter(product -> !activeOnly || product.active)
            .map(this::view)
            .toList();
        return new MonetizationCatalogView(
            all.stream().filter(product -> "SUBSCRIPTION".equals(product.type())).toList(),
            all.stream().filter(product -> "COIN_PACK".equals(product.type())).toList()
        );
    }

    private CommercialProduct view(CommercialProductEntity product) {
        return new CommercialProduct(
            product.productId,
            product.type,
            product.title,
            product.description,
            product.coins,
            product.active,
            product.displayOrder
        );
    }
}
