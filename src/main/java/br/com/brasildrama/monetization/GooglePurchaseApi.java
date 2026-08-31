package br.com.brasildrama.monetization;

import br.com.brasildrama.wallet.WalletCreditService;
import br.com.brasildrama.rewards.VipAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.auth.oauth2.GoogleCredentials;
import jakarta.persistence.*;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
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

    /**
     * Guardado apenas enquanto a confirmação estiver pendente, e apagado assim que
     * ela ocorre. Sem o token em claro é impossível reconfirmar depois — e a
     * Google estorna compras não confirmadas em 3 dias. A chave primária continua
     * sendo o hash; este campo é material temporário de retentativa, não índice.
     */
    @Column(name = "purchase_token", length = 4096)
    String purchaseToken;

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
    List<GooglePlayPurchase> findTop100ByOrderByCreatedAtDesc();
    List<GooglePlayPurchase> findTop50ByUserIdOrderByCreatedAtDesc(UUID userId);
    List<GooglePlayPurchase> findTop200ByAcknowledgedFalseAndPurchaseTokenIsNotNullAndCreatedAtAfter(OffsetDateTime since);
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
record PurchaseHistoryView(
    UUID userId,
    String productId,
    String productType,
    String orderId,
    boolean acknowledged,
    OffsetDateTime expiresAt,
    OffsetDateTime createdAt
) {}

record PlayVerification(boolean valid, boolean acknowledged, String orderId, OffsetDateTime expiresAt) {}

@Service
class GooglePlayVerifier {
    private static final Logger LOG = LoggerFactory.getLogger(GooglePlayVerifier.class);
    private static final String SCOPE = "https://www.googleapis.com/auth/androidpublisher";
    private final String credentialsBase64;
    private final String expectedPackageName;
    private final RestClient client = RestClient.builder()
        .baseUrl("https://androidpublisher.googleapis.com")
        .build();

    GooglePlayVerifier(
        @Value("${google.play.service-account-json-base64:}") String credentialsBase64,
        @Value("${google.play.package-name:br.com.brasildrama.app}") String expectedPackageName
    ) {
        this.credentialsBase64 = credentialsBase64 == null ? "" : credentialsBase64.trim();
        this.expectedPackageName = expectedPackageName;
    }

    boolean isConfigured() {
        return !credentialsBase64.isBlank();
    }

    String expectedPackageName() {
        return expectedPackageName;
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

    /**
     * A Google reembolsa automaticamente compras não confirmadas em 3 dias.
     * Até aqui a confirmação existia apenas no cliente (PaywallScreen), sem
     * retentativa: se a chamada falhasse logo após a compra — o momento mais
     * provável de instabilidade em rede móvel — a receita era estornada e o
     * servidor não tinha como perceber. Confirmar no servidor torna o cliente
     * redundante em vez de único responsável.
     *
     * @return true se a compra está confirmada ao fim da chamada.
     */
    boolean acknowledge(String packageName, String productId, String productType, String token) {
        String collection = "SUBSCRIPTION".equals(productType) ? "subscriptions" : "products";
        try {
            var accessToken = accessToken();
            client.post()
                .uri(builder -> builder.pathSegment("androidpublisher", "v3", "applications", packageName,
                    "purchases", collection, productId, "tokens", token + ":acknowledge").build())
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException exception) {
            // A Google responde 400 quando a compra já está confirmada. Do ponto de
            // vista do negócio isso é sucesso: o que importa é não ser estornada.
            if (exception.getStatusCode().value() == 400) return true;
            LOG.warn("Falha ao confirmar {} na Google Play: {}", productId, exception.getStatusCode());
            return false;
        } catch (RuntimeException exception) {
            LOG.warn("Falha ao confirmar {} na Google Play", productId, exception);
            return false;
        }
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

/**
 * Liquidação de uma compra, em transação própria.
 *
 * Existe como bean separado por um motivo concreto: {@code restore} chamava
 * {@code verify} diretamente (this.verify), e o proxy do Spring só intercepta
 * chamadas que entram pelo bean. A anotação {@code @Transactional} não tinha
 * efeito nesse caminho — uma restauração de até 100 compras que falhasse na
 * de número 47 deixava as 46 primeiras gravadas e as 53 restantes não, sem
 * compensação. Com a liquidação em um bean próprio, cada compra tem a sua
 * transação de verdade e uma falha isolada não contamina as demais.
 */
@Service
class GooglePurchaseSettlement {
    private final CommercialProductRepository products;
    private final GooglePlayPurchaseRepository receipts;
    private final GooglePlayVerifier verifier;
    private final WalletCreditService wallet;

    GooglePurchaseSettlement(
        CommercialProductRepository products,
        GooglePlayPurchaseRepository receipts,
        GooglePlayVerifier verifier,
        WalletCreditService wallet
    ) {
        this.products = products;
        this.receipts = receipts;
        this.verifier = verifier;
        this.wallet = wallet;
    }

    // public de propósito: AnnotationTransactionAttributeSource ignora
    // @Transactional em método não-público (publicMethodsOnly = true). O verify
    // original era package-private, então a anotação nunca teve efeito — nem pela
    // auto-invocação, nem pela visibilidade. A classe continua package-private,
    // então isto não amplia a superfície pública do pacote.
    @Transactional
    public SettledPurchase settle(UUID userId, GooglePurchaseVerifyRequest request) {
        if (!verifier.expectedPackageName().equals(request.packageName())) {
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
            return new SettledPurchase(
                response(existing, wallet.balance(userId)),
                tokenHash, existing.productId, existing.productType, !existing.acknowledged
            );
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
        // Só enquanto a confirmação estiver pendente; markAcknowledged() apaga.
        receipt.purchaseToken = verified.acknowledged() ? null : request.purchaseToken();
        receipt.createdAt = OffsetDateTime.now();
        receipt.updatedAt = receipt.createdAt;
        receipts.save(receipt);

        int balance = wallet.balance(userId);
        if ("COIN_PACK".equals(product.type)) {
            balance = wallet.creditOnce(userId, "google-play:" + tokenHash, product.coins, "GOOGLE_PLAY", product.productId);
        }
        return new SettledPurchase(
            response(receipt, balance), tokenHash, product.productId, product.type, !verified.acknowledged()
        );
    }

    /**
     * Registra a confirmação em transação curta, depois da chamada de rede, e
     * descarta o token: ele só existia para permitir a retentativa.
     */
    @Transactional
    public void markAcknowledged(String tokenHash) {
        receipts.findById(tokenHash).ifPresent(receipt -> {
            receipt.acknowledged = true;
            receipt.purchaseToken = null;
            receipt.updatedAt = OffsetDateTime.now();
            receipts.save(receipt);
        });
    }

    static GooglePurchaseVerifyResponse response(GooglePlayPurchase receipt, int balance) {
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

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 indisponível", exception);
        }
    }
}

record SettledPurchase(
    GooglePurchaseVerifyResponse response,
    String tokenHash,
    String productId,
    String productType,
    boolean needsAcknowledgement
) {}

@Service
class GooglePurchaseService {
    private static final Logger LOG = LoggerFactory.getLogger(GooglePurchaseService.class);

    private final GooglePurchaseSettlement settlement;
    private final GooglePlayPurchaseRepository receipts;
    private final GooglePlayVerifier verifier;
    private final WalletCreditService wallet;
    private final VipAccessService vipAccess;
    private final boolean acknowledgeRetryEnabled;

    GooglePurchaseService(
        GooglePurchaseSettlement settlement,
        GooglePlayPurchaseRepository receipts,
        GooglePlayVerifier verifier,
        WalletCreditService wallet,
        VipAccessService vipAccess,
        // Desligado nos testes: o agendador dispara em thread própria e as chamadas
        // ao verificador poluiriam a contagem de interações dos mocks.
        @Value("${google.play.acknowledge-retry-enabled:true}") boolean acknowledgeRetryEnabled
    ) {
        this.settlement = settlement;
        this.receipts = receipts;
        this.verifier = verifier;
        this.wallet = wallet;
        this.vipAccess = vipAccess;
        this.acknowledgeRetryEnabled = acknowledgeRetryEnabled;
    }

    GooglePurchaseVerifyResponse verify(UUID userId, GooglePurchaseVerifyRequest request) {
        var settled = settlement.settle(userId, request);
        return confirmWithGoogle(settled, request.packageName(), request.purchaseToken());
    }

    /**
     * Confirma na Google **fora** da transação: é chamada de rede, e manter uma
     * transação de banco aberta durante I/O externo prende conexão do pool.
     *
     * A falha aqui não invalida a compra — o direito já foi concedido e o recibo
     * gravado. O recibo fica marcado como não confirmado e a rotina de
     * {@code retryPendingAcknowledgements} tenta de novo antes dos 3 dias em que
     * a Google estorna automaticamente.
     */
    private GooglePurchaseVerifyResponse confirmWithGoogle(SettledPurchase settled, String packageName, String token) {
        if (!settled.needsAcknowledgement() || !verifier.isConfigured()) return settled.response();
        boolean acknowledged = verifier.acknowledge(packageName, settled.productId(), settled.productType(), token);
        if (!acknowledged) return settled.response();
        settlement.markAcknowledged(settled.tokenHash());
        var confirmed = settled.response();
        return new GooglePurchaseVerifyResponse(
            confirmed.valid(), true, confirmed.consumable(), confirmed.balance(), confirmed.activeSubscriptionProductId()
        );
    }

    /**
     * Cada compra é liquidada na sua própria transação e uma falha isolada não
     * interrompe as demais: restaurar 100 compras e falhar na 47ª deve restaurar
     * as outras 99, não abortar tudo nem deixar estado parcial silencioso.
     */
    GoogleRestoreResponse restore(UUID userId, GoogleRestoreRequest request) {
        int restored = 0;
        int failed = 0;
        String activeSubscription = null;
        for (GooglePurchaseVerifyRequest purchase : request.purchases()) {
            try {
                var settled = settlement.settle(userId, purchase);
                var result = confirmWithGoogle(settled, purchase.packageName(), purchase.purchaseToken());
                if (result.valid()) restored++;
                if (result.activeSubscriptionProductId() != null) activeSubscription = result.activeSubscriptionProductId();
            } catch (RuntimeException exception) {
                failed++;
                LOG.warn("Falha ao restaurar compra do usuário {}: {}", userId, exception.getMessage());
            }
        }
        if (failed > 0) LOG.info("Restauração do usuário {}: {} restauradas, {} com falha", userId, restored, failed);
        return new GoogleRestoreResponse(restored, wallet.balance(userId), activeSubscription);
    }

    SubscriptionStatusResponse subscription(UUID userId) {
        var google = receipts.findFirstByUserIdAndProductTypeAndExpiresAtAfterOrderByExpiresAtDesc(
            userId, "SUBSCRIPTION", OffsetDateTime.now()
        ).orElse(null);
        var rewardsExpiry = vipAccess.activeUntil(userId).orElse(null);
        if (google != null && (rewardsExpiry == null || google.expiresAt.isAfter(rewardsExpiry))) {
            return new SubscriptionStatusResponse(true, google.productId, google.expiresAt);
        }
        if (rewardsExpiry != null) {
            return new SubscriptionStatusResponse(true, "vip_rewards", rewardsExpiry);
        }
        return new SubscriptionStatusResponse(false, null, null);
    }

    List<PurchaseHistoryView> history(UUID userId) {
        return receipts.findTop50ByUserIdOrderByCreatedAtDesc(userId).stream().map(this::historyView).toList();
    }

    List<PurchaseHistoryView> adminHistory() {
        return receipts.findTop100ByOrderByCreatedAtDesc().stream().map(this::historyView).toList();
    }

    private PurchaseHistoryView historyView(GooglePlayPurchase receipt) {
        return new PurchaseHistoryView(
            receipt.userId,
            receipt.productId,
            receipt.productType,
            receipt.orderId,
            receipt.acknowledged,
            receipt.expiresAt,
            receipt.createdAt
        );
    }

    /**
     * Rede de proteção contra o estorno automático: a Google reembolsa compras
     * não confirmadas em 3 dias. Se a confirmação falhou no momento da compra —
     * indisponibilidade da API, rede instável — esta rotina tenta de novo.
     *
     * A janela é de 72h porque depois disso o estorno já ocorreu e insistir não
     * recupera a receita, só gasta chamada.
     */
    @Scheduled(fixedDelayString = "${google.play.acknowledge-retry-interval:PT30M}")
    void retryPendingAcknowledgements() {
        if (!acknowledgeRetryEnabled || !verifier.isConfigured()) return;
        var since = OffsetDateTime.now().minusHours(72);
        var pending = receipts.findTop200ByAcknowledgedFalseAndPurchaseTokenIsNotNullAndCreatedAtAfter(since);
        if (pending.isEmpty()) return;

        int recovered = 0;
        for (GooglePlayPurchase receipt : pending) {
            boolean acknowledged = verifier.acknowledge(
                verifier.expectedPackageName(), receipt.productId, receipt.productType, receipt.purchaseToken
            );
            if (acknowledged) {
                settlement.markAcknowledged(receipt.tokenHash);
                recovered++;
            }
        }
        if (recovered > 0) LOG.info("Confirmação tardia: {} de {} recibos pendentes recuperados", recovered, pending.size());
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

    @GetMapping("/v1/me/purchases")
    List<PurchaseHistoryView> history(java.security.Principal principal) {
        return purchases.history(userId(principal));
    }

    @GetMapping("/v1/admin/monetization/purchases")
    List<PurchaseHistoryView> adminHistory() {
        return purchases.adminHistory();
    }

    private static UUID userId(java.security.Principal principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        try { return UUID.fromString(principal.getName()); }
        catch (RuntimeException exception) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED); }
    }
}
