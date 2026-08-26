package br.com.brasildrama.monetization;

import br.com.brasildrama.wallet.WalletCreditService;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.auth.oauth2.GoogleCredentials;
import jakarta.persistence.*;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Entity
@Table(name = "google_play_purchase")
class GooglePlayPurchase {
    @Id
    @Column(name = "token_hash", length = 64)
    String tokenHash;

    @Column(name = "user_id", nullable = false)
    UUID userId;

    @Column(name = "product_id", nullable = false, length = 120)
    String productId;

    @Column(name = "product_type", nullable = false, length = 30)
    String productType;

    @Column(name = "order_id", length = 160)
    String orderId;

    @Column(nullable = false)
    boolean acknowledged;

    @Column(name = "expires_at")
    OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    OffsetDateTime updatedAt;

    protected GooglePlayPurchase() {}
}

interface GooglePlayPurchaseRepository extends JpaRepository<GooglePlayPurchase, String> {
    Optional<GooglePlayPurchase> findFirstByUserIdAndProductTypeAndExpiresAtAfterOrderByExpiresAtDesc(
        UUID userId, String productType, OffsetDateTime now
    );
}

record GooglePurchaseVerifyRequest(
    @NotEmpty @Size(max = 1) List<@NotBlank String> productIds,
    @NotBlank @Size(max = 4096) String purchaseToken,
    @NotBlank @Size(max = 240) String packageName
) {}

record GoogleRestoreRequest(@NotEmpty @Size(max = 100) List<@Valid GooglePurchaseVerifyRequest> purchases) {}
record GooglePurchaseVerifyResponse(
    boolean valid,
    boolean acknowledged,
    boolean consumable,
    Integer balance,
    String activeSubscriptionProductId
) {}
record GoogleRestoreResponse(int restored, int balance, String activeSubscriptionProductId) {}
record SubscriptionStatusResponse(boolean active, String productId, OffsetDateTime expiresAt) {}

record PlayVerification(boolean valid, boolean acknowledged, String orderId, OffsetDateTime expiresAt) {}

@Service
class GooglePlayVerifier {
    private static final String SCOPE = "https://www.googleapis.com/auth/androidpublisher";
    private final String credentialsBase64;
    private final RestClient client = RestClient.builder()
        .baseUrl("https://androidpublisher.googleapis.com")
        .build();

    GooglePlayVerifier(@Value("${google.play.service-account-json-base64:}") String credentialsBase64) {
        this.credentialsBase64 = credentialsBase64 == null ? "" : credentialsBase64.trim();
    }

    PlayVerification verify(String packageName, CommercialProductEntity product, String token) {
        var accessToken = accessToken();
        return switch (product.type) {
            case "COIN_PACK" -> verifyProduct(accessToken, packageName, product.productId, token);
            case "SUBSCRIPTION" -> verifySubscription(accessToken, packageName, product.productId, token);
            default -> throw new ResponseStatusException(HttpStatus.CONFLICT, "Tipo comercial inválido");
        };
    }

    private PlayVerification verifyProduct(String accessToken, String packageName, String productId, String token) {
        JsonNode body = client.get()
            .uri(builder -> builder.pathSegment("androidpublisher", "v3", "applications", packageName,
                "purchases", "products", productId, "tokens", token).build())
            .headers(headers -> headers.setBearerAuth(accessToken))
            .retrieve().body(JsonNode.class);
        if (body == null) throw unavailable("Resposta vazia da Google Play", null);
        boolean valid = body.path("purchaseState").asInt(-1) == 0;
        boolean acknowledged = body.path("acknowledgementState").asInt(0) == 1;
        return new PlayVerification(valid, acknowledged, text(body, "orderId"), null);
    }

    private PlayVerification verifySubscription(String accessToken, String packageName, String productId, String token) {
        JsonNode body = client.get()
            .uri(builder -> builder.pathSegment("androidpublisher", "v3", "applications", packageName,
                "purchases", "subscriptionsv2", "tokens", token).build())
            .headers(headers -> headers.setBearerAuth(accessToken))
            .retrieve().body(JsonNode.class);
        if (body == null) throw unavailable("Resposta vazia da Google Play", null);
        String state = text(body, "subscriptionState");
        boolean activeState = "SUBSCRIPTION_STATE_ACTIVE".equals(state)
            || "SUBSCRIPTION_STATE_IN_GRACE_PERIOD".equals(state);
        JsonNode matching = null;
        for (JsonNode item : body.path("lineItems")) {
            if (productId.equals(text(item, "productId"))) {
                matching = item;
                break;
            }
        }
        OffsetDateTime expiry = matching == null ? null : parseTime(text(matching, "expiryTime"));
        boolean valid = activeState && matching != null && expiry != null && expiry.isAfter(OffsetDateTime.now());
        boolean acknowledged = "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED".equals(text(body, "acknowledgementState"));
        return new PlayVerification(valid, acknowledged, text(body, "latestOrderId"), expiry);
    }

    private String accessToken() {
        if (credentialsBase64.isBlank()) {
            throw unavailable("Credencial Google Play não configurada", null);
        }
        try {
            byte[] json = Base64.getDecoder().decode(credentialsBase64);
            GoogleCredentials credentials = GoogleCredentials
                .fromStream(new ByteArrayInputStream(json))
                .createScoped(SCOPE);
            credentials.refreshIfExpired();
            if (credentials.getAccessToken() == null) credentials.refresh();
            return credentials.getAccessToken().getTokenValue();
        } catch (Exception exception) {
            throw unavailable("Não foi possível autenticar na Google Play", exception);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static OffsetDateTime parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        try { return OffsetDateTime.ofInstant(Instant.parse(value), ZoneOffset.UTC); }
        catch (DateTimeException ignored) { return null; }
    }

    private static ResponseStatusException unavailable(String message, Throwable cause) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message, cause);
    }
}

@Service
class GooglePurchaseService {
    private final CommercialProductRepository products;
    private final GooglePlayPurchaseRepository receipts;
    private final GooglePlayVerifier verifier;
    private final WalletCreditService wallet;
    private final String expectedPackageName;

    GooglePurchaseService(
        CommercialProductRepository products,
        GooglePlayPurchaseRepository receipts,
        GooglePlayVerifier verifier,
        WalletCreditService wallet,
        @Value("${google.play.package-name:br.com.brasildrama.app}") String expectedPackageName
    ) {
        this.products = products;
        this.receipts = receipts;
        this.verifier = verifier;
        this.wallet = wallet;
        this.expectedPackageName = expectedPackageName;
    }

    @Transactional
    GooglePurchaseVerifyResponse verify(UUID userId, GooglePurchaseVerifyRequest request) {
        if (!expectedPackageName.equals(request.packageName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pacote Android inválido");
        }
        String productId = request.productIds().getFirst();
        var product = products.findById(productId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Produto comercial não encontrado"));
        String tokenHash = sha256(request.purchaseToken());

        var existing = receipts.findById(tokenHash).orElse(null);
        if (existing != null) {
            if (!existing.userId.equals(userId) || !existing.productId.equals(productId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Purchase token já vinculado");
            }
            return response(existing, wallet.balance(userId));
        }
        if (!product.active) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Produto comercial inativo");
        }

        PlayVerification verified;
        try {
            verified = verifier.verify(request.packageName(), product, request.purchaseToken());
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Google Play indisponível", exception);
        }
        if (!verified.valid()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Compra inválida ou sem direito ativo");
        }

        var receipt = new GooglePlayPurchase();
        receipt.tokenHash = tokenHash;
        receipt.userId = userId;
        receipt.productId = product.productId;
        receipt.productType = product.type;
        receipt.orderId = verified.orderId();
        receipt.acknowledged = verified.acknowledged();
        receipt.expiresAt = verified.expiresAt();
        receipt.createdAt = OffsetDateTime.now();
        receipt.updatedAt = receipt.createdAt;
        receipts.save(receipt);

        int balance = wallet.balance(userId);
        if ("COIN_PACK".equals(product.type)) {
            balance = wallet.creditOnce(userId, "google-play:" + tokenHash, product.coins, "GOOGLE_PLAY", product.productId);
        }
        return response(receipt, balance);
    }

    GoogleRestoreResponse restore(UUID userId, GoogleRestoreRequest request) {
        int restored = 0;
        String activeSubscription = null;
        for (GooglePurchaseVerifyRequest purchase : request.purchases()) {
            var result = verify(userId, purchase);
            if (result.valid()) restored++;
            if (result.activeSubscriptionProductId() != null) activeSubscription = result.activeSubscriptionProductId();
        }
        return new GoogleRestoreResponse(restored, wallet.balance(userId), activeSubscription);
    }

    SubscriptionStatusResponse subscription(UUID userId) {
        return receipts.findFirstByUserIdAndProductTypeAndExpiresAtAfterOrderByExpiresAtDesc(
                userId, "SUBSCRIPTION", OffsetDateTime.now())
            .map(receipt -> new SubscriptionStatusResponse(true, receipt.productId, receipt.expiresAt))
            .orElseGet(() -> new SubscriptionStatusResponse(false, null, null));
    }

    private GooglePurchaseVerifyResponse response(GooglePlayPurchase receipt, int balance) {
        boolean subscription = "SUBSCRIPTION".equals(receipt.productType);
        boolean active = !subscription || (receipt.expiresAt != null && receipt.expiresAt.isAfter(OffsetDateTime.now()));
        return new GooglePurchaseVerifyResponse(
            active,
            receipt.acknowledged,
            "COIN_PACK".equals(receipt.productType),
            balance,
            active && subscription ? receipt.productId : null
        );
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 indisponível", exception);
        }
    }
}

@RestController
class GooglePurchaseApi {
    private final GooglePurchaseService purchases;

    GooglePurchaseApi(GooglePurchaseService purchases) {
        this.purchases = purchases;
    }

    @PostMapping("/v1/purchases/google/verify")
    GooglePurchaseVerifyResponse verify(
        java.security.Principal principal,
        @Valid @RequestBody GooglePurchaseVerifyRequest request
    ) {
        return purchases.verify(userId(principal), request);
    }

    @PostMapping("/v1/purchases/google/restore")
    GoogleRestoreResponse restore(
        java.security.Principal principal,
        @Valid @RequestBody GoogleRestoreRequest request
    ) {
        return purchases.restore(userId(principal), request);
    }

    @GetMapping("/v1/subscriptions/status")
    SubscriptionStatusResponse subscription(java.security.Principal principal) {
        return purchases.subscription(userId(principal));
    }

    private static UUID userId(java.security.Principal principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        try { return UUID.fromString(principal.getName()); }
        catch (RuntimeException exception) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED); }
    }
}
