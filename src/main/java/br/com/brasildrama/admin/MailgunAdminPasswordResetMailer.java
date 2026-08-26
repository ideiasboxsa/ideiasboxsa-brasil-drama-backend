package br.com.brasildrama.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
class MailgunAdminPasswordResetMailer {
    private static final Logger log = LoggerFactory.getLogger(MailgunAdminPasswordResetMailer.class);

    private final HttpClient http = HttpClient.newHttpClient();
    private final String apiKey;
    private final String domain;
    private final String from;

    MailgunAdminPasswordResetMailer(
        @Value("${mailgun.api-key:${MAILGUN_API_KEY:}}") String apiKey,
        @Value("${mailgun.domain:${MAILGUN_DOMAIN:}}") String domain,
        @Value("${mailgun.from:${MAILGUN_FROM:}}") String from
    ) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.domain = domain == null ? "" : domain.trim();
        this.from = from == null ? "" : from.trim();
    }

    boolean send(String recipient, String resetUrl) {
        if (apiKey.isBlank() || domain.isBlank() || from.isBlank()) {
            log.warn("Admin password reset email not sent: Mailgun is not configured");
            return false;
        }

        var body = form("from", from)
            + "&" + form("to", recipient)
            + "&" + form("subject", "Redefinição de senha — Brasil Drama Studio")
            + "&" + form("text", """
                Recebemos uma solicitação para redefinir sua senha do Brasil Drama Studio.

                Use o link abaixo. Ele expira em 30 minutos e funciona apenas uma vez:

                %s

                Se você não solicitou a redefinição, ignore esta mensagem.
                """.formatted(resetUrl));

        var authorization = Base64.getEncoder()
            .encodeToString(("api:" + apiKey).getBytes(StandardCharsets.UTF_8));

        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.mailgun.net/v3/" + domain + "/messages"))
                .header("Authorization", "Basic " + authorization)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            var response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) return true;
            log.warn("Admin password reset email rejected by Mailgun with status {}", response.statusCode());
        } catch (Exception exception) {
            Thread.currentThread().interrupt();
            log.warn("Admin password reset email could not be sent", exception);
        }
        return false;
    }

    private static String form(String key, String value) {
        return URLEncoder.encode(key, StandardCharsets.UTF_8) + "="
            + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
