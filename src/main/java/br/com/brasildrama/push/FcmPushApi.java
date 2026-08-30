package br.com.brasildrama.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

record PushCampaignRequest(String title, String body, String deepLink, String audience) {}
record PushCampaignResult(String audience, int targeted, int delivered, int failed, boolean configured) {}

@RestController
@RequestMapping("/v1/admin/push")
class FcmPushController {
    private final FcmPushService service;

    FcmPushController(FcmPushService service) {
        this.service = service;
    }

    @PostMapping("/send")
    PushCampaignResult send(@RequestBody PushCampaignRequest request) {
        return service.send(request);
    }
}

@org.springframework.stereotype.Service
class FcmPushService {
    private static final String FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Environment environment;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    FcmPushService(JdbcTemplate jdbc, ObjectMapper objectMapper, Environment environment) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.environment = environment;
    }

    PushCampaignResult send(PushCampaignRequest request) {
        validate(request);
        String audience = normalizeAudience(request.audience());
        if (!enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "FCM_NOT_CONFIGURED");
        }

        List<String> tokens = tokensFor(audience);
        if (tokens.isEmpty()) return new PushCampaignResult(audience, 0, 0, 0, true);

        try {
            GoogleCredentials credentials = GoogleCredentials.getApplicationDefault()
                    .createScoped(List.of(FCM_SCOPE));
            credentials.refreshIfExpired();
            String accessToken = credentials.getAccessToken().getTokenValue();
            String projectId = projectId();
            URI endpoint = URI.create("https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send");

            int delivered = 0;
            int failed = 0;
            for (String token : tokens) {
                Map<String, Object> message = new HashMap<>();
                message.put("token", token);
                message.put("notification", Map.of("title", request.title().trim(), "body", request.body().trim()));
                Map<String, String> data = new HashMap<>();
                data.put("title", request.title().trim());
                data.put("body", request.body().trim());
                if (request.deepLink() != null && !request.deepLink().isBlank()) data.put("deepLink", request.deepLink().trim());
                message.put("data", data);
                message.put("android", Map.of("priority", "high"));

                String payload = objectMapper.writeValueAsString(Map.of("message", message));
                HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(15))
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) delivered++;
                else failed++;
            }
            return new PushCampaignResult(audience, tokens.size(), delivered, failed, true);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "FCM_SEND_FAILED", e);
        }
    }

    private List<String> tokensFor(String audience) {
        String consent = switch (audience) {
            case "MARKETING" -> "and marketing_enabled = true";
            case "NEW_EPISODE" -> "and new_episode_enabled = true";
            case "REWARD" -> "and reward_enabled = true";
            default -> "";
        };
        return jdbc.queryForList("""
                select token
                  from push_device
                 where enabled = true
                   and provider = 'FCM'
                   and token is not null
                   and token <> ''
                """ + consent + " order by last_seen_at desc", String.class);
    }

    private boolean enabled() {
        return Boolean.parseBoolean(environment.getProperty("push.fcm.enabled", "false"));
    }

    private String projectId() {
        String projectId = environment.getProperty("push.fcm.project-id", "").trim();
        if (projectId.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "FCM_PROJECT_ID_MISSING");
        return projectId;
    }

    private static String normalizeAudience(String value) {
        if (value == null || value.isBlank()) return "ALL";
        String audience = value.trim().toUpperCase();
        return switch (audience) {
            case "ALL", "MARKETING", "NEW_EPISODE", "REWARD" -> audience;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PUSH_AUDIENCE");
        };
    }

    private static void validate(PushCampaignRequest request) {
        if (request == null || request.title() == null || request.title().isBlank() || request.title().length() > 120)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PUSH_TITLE");
        if (request.body() == null || request.body().isBlank() || request.body().length() > 500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PUSH_BODY");
        if (request.deepLink() != null && request.deepLink().length() > 500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PUSH_DEEP_LINK");
    }
}
