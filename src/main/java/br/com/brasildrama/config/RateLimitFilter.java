package br.com.brasildrama.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limite de taxa por IP nos endpoints públicos.
 *
 * Antes disto nenhum endpoint tinha proteção alguma. Os caminhos expostos e o
 * que cada um permitia:
 *
 * <ul>
 *   <li>{@code /v1/auth/login} — força bruta e preenchimento de credenciais sem limite;</li>
 *   <li>{@code /v1/auth/register} — criação em massa de contas, cada uma levando o
 *       bônus de boas-vindas. Criar 10 mil contas era um laço de dez linhas, e o
 *       custo não é só o conteúdo liberado: corrompe conversão, ARPU e funil de
 *       aquisição, e depois não há como separar o ruído do sinal;</li>
 *   <li>{@code /v1/auth/password/forgot} — amplificação de e-mail via Mailgun, com custo por mensagem;</li>
 *   <li>{@code /v1/analytics/playback/events} — ingestão anônima ilimitada.</li>
 * </ul>
 *
 * <p>Roda antes da cadeia do Spring Security de propósito: rejeitar cedo evita
 * gastar hash de senha ou consulta de banco com tráfego abusivo.
 *
 * <p><b>Limitação conhecida:</b> a contagem é por instância, em memória. Com N
 * instâncias o limite efetivo é N vezes o configurado. É proteção contra abuso
 * trivial e acidente de cliente, não contra ataque distribuído — para esse
 * caso, WAF na borda. Vale o registro para que ninguém confie mais do que deve.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(RateLimitFilter.class);

    /** Teto de chaves distintas; acima disso os buckets expirados são descartados. */
    private static final int MAX_TRACKED_KEYS = 100_000;

    private final boolean enabled;
    private final List<Rule> rules;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    RateLimitFilter(
        @Value("${brasil-drama.rate-limit.enabled:true}") boolean enabled,
        @Value("${brasil-drama.rate-limit.auth-per-minute:10}") int authPerMinute,
        @Value("${brasil-drama.rate-limit.register-per-hour:5}") int registerPerHour,
        @Value("${brasil-drama.rate-limit.password-reset-per-hour:5}") int passwordResetPerHour,
        @Value("${brasil-drama.rate-limit.telemetry-per-minute:120}") int telemetryPerMinute
    ) {
        this.enabled = enabled;
        this.rules = List.of(
            // O registro é o mais restrito: é ele que emite moeda.
            new Rule("register", "/v1/auth/register", registerPerHour, Duration.ofHours(1)),
            new Rule("password-forgot", "/v1/auth/password/forgot", passwordResetPerHour, Duration.ofHours(1)),
            new Rule("password-reset", "/v1/auth/password/reset", passwordResetPerHour, Duration.ofHours(1)),
            new Rule("auth", "/v1/auth/", authPerMinute, Duration.ofMinutes(1)),
            new Rule("admin-auth", "/v1/admin/auth/", authPerMinute, Duration.ofMinutes(1)),
            new Rule("telemetry", "/v1/analytics/playback/events", telemetryPerMinute, Duration.ofMinutes(1))
        );
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {

        Rule rule = enabled ? match(request.getRequestURI()) : null;
        if (rule == null) {
            chain.doFilter(request, response);
            return;
        }

        String key = rule.name() + '|' + clientKey(request);
        Instant now = Instant.now();
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(now.plus(rule.window())));

        if (!bucket.tryConsume(rule.limit(), rule.window(), now)) {
            long retryAfter = Math.max(1, Duration.between(now, bucket.resetAt()).getSeconds());
            LOG.warn("Limite de taxa atingido: regra={} cliente={}", rule.name(), clientKey(request));
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"RATE_LIMIT_EXCEEDED\",\"retryAfterSeconds\":" + retryAfter + "}");
            return;
        }

        if (buckets.size() > MAX_TRACKED_KEYS) evictExpired(now);
        chain.doFilter(request, response);
    }

    private Rule match(String uri) {
        if (uri == null) return null;
        for (Rule rule : rules) {
            // Regras específicas (caminho exato) vêm antes das de prefixo na lista,
            // então a primeira que casar é a mais restritiva aplicável.
            if (uri.equals(rule.path()) || (rule.path().endsWith("/") && uri.startsWith(rule.path()))) return rule;
        }
        return null;
    }

    /**
     * O serviço roda atrás de proxy ({@code forward-headers-strategy: framework}),
     * então o IP real vem no primeiro elemento de X-Forwarded-For.
     */
    private static String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }

    private void evictExpired(Instant now) {
        buckets.entrySet().removeIf(entry -> entry.getValue().resetAt().isBefore(now));
    }

    private record Rule(String name, String path, int limit, Duration window) {}

    /** Janela fixa. Simples e suficiente: o objetivo é cortar abuso, não modelar tráfego. */
    private static final class Bucket {
        private int count;
        private Instant resetAt;

        Bucket(Instant resetAt) {
            this.resetAt = resetAt;
        }

        synchronized boolean tryConsume(int limit, Duration window, Instant now) {
            if (!now.isBefore(resetAt)) {
                count = 0;
                resetAt = now.plus(window);
            }
            if (count >= limit) return false;
            count++;
            return true;
        }

        synchronized Instant resetAt() {
            return resetAt;
        }
    }
}
