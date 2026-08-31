package br.com.brasildrama.rewards;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

record RewardedAdSessionDto(String operationKey, String expiresAt) {}
record RewardedEpisodeAdSessionRequest(UUID episodeId) {}
record RewardedAdClaimDto(
    boolean accepted,
    Long bonusBalance,
    Long vipPointsBalance,
    String subscriptionExpiresAt,
    RewardsOverviewDto overview,
    int balance
) {}
record RewardedEpisodeAdClaimDto(boolean accepted, UUID episodeId, boolean unlocked) {}

@RestController
@RequestMapping("/v1/rewards/ads")
class RewardedAdController {
    private final RewardedAdService rewardedAds;

    RewardedAdController(RewardedAdService rewardedAds) {
        this.rewardedAds = rewardedAds;
    }

    @PostMapping("/session")
    RewardedAdSessionDto createSession(Authentication authentication) {
        return rewardedAds.createSession(userId(authentication));
    }

    @PostMapping("/episode/session")
    RewardedAdSessionDto createEpisodeSession(Authentication authentication, @RequestBody RewardedEpisodeAdSessionRequest request) {
        if (request == null || request.episodeId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "episodeId is required");
        return rewardedAds.createEpisodeSession(userId(authentication), request.episodeId());
    }

    @GetMapping("/ssv")
    @ResponseStatus(HttpStatus.OK)
    void ssv(HttpServletRequest request) {
        rewardedAds.verifySsv(request);
    }

    @PostMapping("/claim")
    RewardedAdClaimDto claim(Authentication authentication, @RequestBody RewardsOperationRequest request) {
        return rewardedAds.claim(userId(authentication), operationKey(request));
    }

    @PostMapping("/episode/claim")
    RewardedEpisodeAdClaimDto claimEpisode(Authentication authentication, @RequestBody RewardsOperationRequest request) {
        return rewardedAds.claimEpisode(userId(authentication), operationKey(request));
    }

    private static UUID userId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        try { return UUID.fromString(authentication.getName()); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED); }
    }

    private static String operationKey(RewardsOperationRequest request) {
        if (request == null || request.operationKey() == null || request.operationKey().isBlank() || request.operationKey().length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "operationKey is required");
        }
        return request.operationKey().trim();
    }
}

@Service
class RewardedAdService {
    private final JdbcTemplate jdbc;
    private final RewardsService rewards;
    private final AdMobSsvVerifier verifier;
    private final ZoneId zoneId;
    private final int dailyLimit;
    private final long bonusAmount;
    private final Duration sessionTtl;
    private final String expectedAdUnitId;

    RewardedAdService(
        JdbcTemplate jdbc,
        RewardsService rewards,
        AdMobSsvVerifier verifier,
        @Value("${rewards.zone-id:America/Sao_Paulo}") String zoneId,
        @Value("${rewards.ads.daily-limit:5}") int dailyLimit,
        @Value("${rewards.ads.bonus:20}") long bonusAmount,
        @Value("${rewards.ads.session-ttl-minutes:10}") long sessionTtlMinutes,
        @Value("${rewards.ads.ad-unit-id:}") String expectedAdUnitId
    ) {
        this.jdbc = jdbc;
        this.rewards = rewards;
        this.verifier = verifier;
        this.zoneId = ZoneId.of(zoneId);
        this.dailyLimit = Math.max(0, dailyLimit);
        this.bonusAmount = Math.max(0, bonusAmount);
        this.sessionTtl = Duration.ofMinutes(Math.max(1, sessionTtlMinutes));
        this.expectedAdUnitId = expectedAdUnitId == null ? "" : expectedAdUnitId.trim();
    }

    @Transactional
    public RewardedAdSessionDto createSession(UUID userId) {
        return createSession(userId, "BONUS", null);
    }

    @Transactional
    public RewardedAdSessionDto createEpisodeSession(UUID userId, UUID episodeId) {
        Integer eligible = jdbc.queryForObject("select count(*) from episode where id=? and free=false", Integer.class, episodeId);
        if (eligible == null || eligible == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "REWARDED_EPISODE_NOT_ELIGIBLE");
        Integer alreadyUnlocked = jdbc.queryForObject("select count(*) from episode_entitlement where user_id=? and episode_id=?", Integer.class, userId, episodeId);
        if (alreadyUnlocked != null && alreadyUnlocked > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "EPISODE_ALREADY_UNLOCKED");
        return createSession(userId, "EPISODE_UNLOCK", episodeId);
    }

    private RewardedAdSessionDto createSession(UUID userId, String rewardType, UUID episodeId) {
        lock(userId);
        if (dailyLimit == 0 || claimedToday(userId) >= dailyLimit) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "REWARDED_AD_DAILY_LIMIT_REACHED");
        }
        var operationKey = "ad:" + UUID.randomUUID();
        var expiresAt = Instant.now().plus(sessionTtl);
        jdbc.update(
            "insert into rewarded_ad_session(operation_key,user_id,expires_at,reward_type,episode_id,created_at) values (?,?,?,?,?,now())",
            operationKey, userId, Timestamp.from(expiresAt), rewardType, episodeId
        );
        return new RewardedAdSessionDto(operationKey, expiresAt.toString());
    }

    @Transactional
    public void verifySsv(HttpServletRequest request) {
        AdMobSsvPayload payload = verifier.verify(request);
        if (payload.customData() == null || payload.customData().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SSV_CUSTOM_DATA_REQUIRED");
        if (!expectedAdUnitId.isBlank() && !expectedAdUnitId.equals(payload.adUnit())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SSV_AD_UNIT_MISMATCH");

        var session = jdbc.query(
            "select user_id,expires_at,transaction_id from rewarded_ad_session where operation_key=? for update",
            (rs, rowNum) -> new AdSession(rs.getObject("user_id", UUID.class), rs.getObject("expires_at", OffsetDateTime.class).toInstant(), rs.getString("transaction_id")),
            payload.customData()
        ).stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SSV_SESSION_NOT_FOUND"));
        if (session.expiresAt().isBefore(Instant.now())) throw new ResponseStatusException(HttpStatus.GONE, "SSV_SESSION_EXPIRED");
        if (session.transactionId() != null) {
            if (session.transactionId().equals(payload.transactionId())) return;
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SSV_SESSION_ALREADY_VERIFIED");
        }
        Integer transactionExists = jdbc.queryForObject("select count(*) from rewarded_ad_session where transaction_id=?", Integer.class, payload.transactionId());
        if (transactionExists != null && transactionExists > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "SSV_TRANSACTION_ALREADY_USED");
        jdbc.update("update rewarded_ad_session set verified_at=now(), transaction_id=? where operation_key=? and transaction_id is null", payload.transactionId(), payload.customData());
    }

    @Transactional
    public RewardedAdClaimDto claim(UUID userId, String operationKey) {
        var session = verifiedClaimSession(userId, operationKey, "BONUS");
        var ledgerOperation = "rewarded-ad:" + operationKey;
        Integer prior = jdbc.queryForObject("select count(*) from reward_ledger where user_id=? and operation_key=?", Integer.class, userId, ledgerOperation);
        if (prior == null || prior == 0) {
            jdbc.update("insert into reward_ledger(id,user_id,ledger_type,operation_key,amount,reference_type,reference_id,created_at) values (?,?,?,?,?,?,?,now())",
                UUID.randomUUID(), userId, "BONUS", ledgerOperation, bonusAmount, "REWARDED_AD", operationKey);
        }
        markClaimed(operationKey);
        return response(session.claimedAt() == null, userId);
    }

    @Transactional
    public RewardedEpisodeAdClaimDto claimEpisode(UUID userId, String operationKey) {
        var session = verifiedClaimSession(userId, operationKey, "EPISODE_UNLOCK");
        if (session.episodeId() == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "REWARDED_EPISODE_MISSING");
        if (session.claimedAt() == null) {
            jdbc.update(
                "insert into episode_entitlement(user_id,episode_id,source,operation_key,granted_at) values (?,?,?,?,now()) on conflict (user_id,episode_id) do nothing",
                userId, session.episodeId(), "REWARDED_AD", "rewarded-ad-unlock:" + operationKey
            );
            markClaimed(operationKey);
        }
        return new RewardedEpisodeAdClaimDto(session.claimedAt() == null, session.episodeId(), true);
    }

    private ClaimSession verifiedClaimSession(UUID userId, String operationKey, String expectedRewardType) {
        lock(userId);
        var session = jdbc.query(
            "select user_id,expires_at,verified_at,claimed_at,reward_type,episode_id from rewarded_ad_session where operation_key=? for update",
            (rs, rowNum) -> new ClaimSession(
                rs.getObject("user_id", UUID.class), rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                rs.getObject("verified_at", OffsetDateTime.class), rs.getObject("claimed_at", OffsetDateTime.class),
                rs.getString("reward_type"), rs.getObject("episode_id", UUID.class)
            ), operationKey
        ).stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "REWARDED_AD_SESSION_NOT_FOUND"));
        if (!userId.equals(session.userId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "REWARDED_AD_SESSION_OWNER_MISMATCH");
        if (!expectedRewardType.equals(session.rewardType())) throw new ResponseStatusException(HttpStatus.CONFLICT, "REWARDED_AD_REWARD_TYPE_MISMATCH");
        if (session.claimedAt() != null) return session;
        if (session.expiresAt().isBefore(Instant.now())) throw new ResponseStatusException(HttpStatus.GONE, "REWARDED_AD_SESSION_EXPIRED");
        if (session.verifiedAt() == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "REWARDED_AD_NOT_VERIFIED");
        if (dailyLimit == 0 || claimedToday(userId) >= dailyLimit) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "REWARDED_AD_DAILY_LIMIT_REACHED");
        return session;
    }

    private void markClaimed(String operationKey) {
        jdbc.update("update rewarded_ad_session set claimed_at=now() where operation_key=? and claimed_at is null", operationKey);
    }

    private RewardedAdClaimDto response(boolean accepted, UUID userId) {
        var overview = rewards.overview(userId);
        long bonus = overview.bonusBalance() == null ? 0 : overview.bonusBalance();
        return new RewardedAdClaimDto(accepted, overview.bonusBalance(), overview.vipPointsBalance(), null, overview,
            bonus > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) bonus);
    }

    private int claimedToday(UUID userId) {
        var today = LocalDate.now(zoneId);
        var start = today.atStartOfDay(zoneId).toOffsetDateTime();
        var end = today.plusDays(1).atStartOfDay(zoneId).toOffsetDateTime();
        Integer count = jdbc.queryForObject("select count(*) from rewarded_ad_session where user_id=? and claimed_at>=? and claimed_at<?", Integer.class, userId, start, end);
        return count == null ? 0 : count;
    }

    private void lock(UUID userId) {
        jdbc.queryForObject("select 1 from (select pg_advisory_xact_lock(hashtext(?))) rewards_ad_lock", Integer.class, userId.toString());
    }

    private record AdSession(UUID userId, Instant expiresAt, String transactionId) {}
    private record ClaimSession(UUID userId, Instant expiresAt, OffsetDateTime verifiedAt, OffsetDateTime claimedAt, String rewardType, UUID episodeId) {}
}

record AdMobSsvPayload(String customData, String transactionId, String adUnit, long timestampMs) {}

@Service
class AdMobSsvVerifier {
    private static final String KEYS_URL = "https://www.gstatic.com/admob/reward/verifier-keys.json";
    private static final Duration MAX_CACHE = Duration.ofHours(24);
    private static final Duration MAX_EVENT_AGE = Duration.ofHours(24);
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);

    private final RestClient restClient = RestClient.create();
    private final ObjectMapper objectMapper;
    private final Map<Long, PublicKey> cachedKeys = new ConcurrentHashMap<>();
    private volatile Instant keysLoadedAt = Instant.EPOCH;

    AdMobSsvVerifier(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    AdMobSsvPayload verify(HttpServletRequest request) {
        try {
            var rawQuery = request.getQueryString();
            if (rawQuery == null || rawQuery.isBlank()) throw new IllegalArgumentException("Missing query string");
            int signatureIndex = rawQuery.indexOf("&signature=");
            if (signatureIndex <= 0) throw new IllegalArgumentException("Missing signature");
            var signedContent = rawQuery.substring(0, signatureIndex).getBytes(StandardCharsets.UTF_8);
            var signatureText = required(request, "signature");
            long keyId = Long.parseLong(required(request, "key_id"));
            var publicKey = keys().get(keyId);
            if (publicKey == null) { refreshKeys(); publicKey = cachedKeys.get(keyId); }
            if (publicKey == null) throw new IllegalArgumentException("Unknown key_id");
            var signature = Signature.getInstance("SHA256withECDSA");
            signature.initVerify(publicKey);
            signature.update(signedContent);
            if (!signature.verify(Base64.getUrlDecoder().decode(padBase64(signatureText)))) throw new IllegalArgumentException("Invalid SSV signature");
            long timestampMs = Long.parseLong(required(request, "timestamp"));
            var eventTime = Instant.ofEpochMilli(timestampMs);
            var now = Instant.now();
            if (eventTime.isBefore(now.minus(MAX_EVENT_AGE)) || eventTime.isAfter(now.plus(MAX_FUTURE_SKEW))) throw new IllegalArgumentException("SSV timestamp outside accepted window");
            return new AdMobSsvPayload(request.getParameter("custom_data"), required(request, "transaction_id"), required(request, "ad_unit"), timestampMs);
        } catch (ResponseStatusException ex) { throw ex; }
        catch (Exception ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_ADMOB_SSV", ex); }
    }

    private Map<Long, PublicKey> keys() throws Exception {
        if (cachedKeys.isEmpty() || keysLoadedAt.plus(MAX_CACHE).isBefore(Instant.now())) refreshKeys();
        return cachedKeys;
    }

    private synchronized void refreshKeys() throws Exception {
        if (!cachedKeys.isEmpty() && keysLoadedAt.plus(MAX_CACHE).isAfter(Instant.now())) return;
        var body = restClient.get().uri(KEYS_URL).retrieve().body(String.class);
        if (body == null || body.isBlank()) throw new IllegalStateException("Empty AdMob key response");
        var root = objectMapper.readTree(body);
        var next = new HashMap<Long, PublicKey>();
        for (var node : root.path("keys")) {
            long keyId = node.path("keyId").asLong();
            var encoded = Base64.getDecoder().decode(node.path("base64").asText());
            var key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));
            next.put(keyId, key);
        }
        if (next.isEmpty()) throw new IllegalStateException("No AdMob verification keys");
        cachedKeys.clear(); cachedKeys.putAll(next); keysLoadedAt = Instant.now();
    }

    private static String required(HttpServletRequest request, String name) {
        var value = request.getParameter(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }

    private static String padBase64(String value) {
        int remainder = value.length() % 4;
        return remainder == 0 ? value : value + "=".repeat(4 - remainder);
    }
}
