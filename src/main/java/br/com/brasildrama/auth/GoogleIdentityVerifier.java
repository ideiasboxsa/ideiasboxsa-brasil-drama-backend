package br.com.brasildrama.auth;

import com.google.api.client.json.webtoken.JsonWebToken;
import com.google.auth.oauth2.TokenVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

/**
 * Verifica o ID token emitido pelo Sign in with Google.
 *
 * O endpoint {@code /v1/auth/google} era um stub que devolvia 501: o app já
 * obtinha o token pelo Credential Manager e o enviava, mas o servidor nunca o
 * validou nem criou sessão. Login social não funcionava em ambiente nenhum.
 *
 * <p>A validação é criptográfica, contra o conjunto de chaves públicas do Google,
 * e nunca por confiança no cliente: um ID token é um bearer credential e aceitar
 * um sem verificar assinatura, emissor, audiência e expiração equivale a permitir
 * que qualquer um se autentique como qualquer usuário.
 *
 * <p>Usa {@code TokenVerifier} do google-auth-library, que já era dependência do
 * projeto para a verificação de compras na Play — sem dependência nova.
 */
@Service
public class GoogleIdentityVerifier {
    private static final Logger LOG = LoggerFactory.getLogger(GoogleIdentityVerifier.class);

    /** O Google emite ambas as formas de {@code iss}; as duas são legítimas. */
    private static final List<String> ISSUERS = List.of("https://accounts.google.com", "accounts.google.com");

    private final String clientId;

    GoogleIdentityVerifier(@Value("${google.oauth.web-client-id:${GOOGLE_WEB_CLIENT_ID:}}") String clientId) {
        this.clientId = clientId == null ? "" : clientId.trim();
    }

    public boolean isConfigured() {
        return !clientId.isBlank();
    }

    public GoogleIdentity verify(String idToken) {
        if (!isConfigured()) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "GOOGLE_AUTH_NOT_CONFIGURED");
        }

        var payload = verifySignature(idToken);

        // A audiência precisa ser o client ID *web*, não o Android: é o
        // serverClientId que o app passa ao GetSignInWithGoogleOption, e é para
        // ele que o Google emite o token destinado ao backend.
        String subject = payload.getSubject();
        String email = asText(payload.get("email"));
        Object emailVerified = payload.get("email_verified");

        if (subject == null || subject.isBlank()) {
            throw unauthorized("GOOGLE_TOKEN_WITHOUT_SUBJECT");
        }
        if (email == null || email.isBlank()) {
            throw unauthorized("GOOGLE_TOKEN_WITHOUT_EMAIL");
        }
        // Sem este teste, a vinculação por e-mail vira tomada de conta: bastaria
        // um provedor devolver um e-mail alheio não verificado para assumir a
        // conta existente com aquele endereço.
        if (!isTrue(emailVerified)) {
            throw unauthorized("GOOGLE_EMAIL_NOT_VERIFIED");
        }

        return new GoogleIdentity(
            subject,
            email.trim().toLowerCase(Locale.ROOT),
            asText(payload.get("name")),
            asText(payload.get("picture"))
        );
    }

    private JsonWebToken.Payload verifySignature(String idToken) {
        Exception lastFailure = null;
        for (String issuer : ISSUERS) {
            try {
                // O TokenVerifier baixa e cacheia as chaves públicas do Google e
                // valida assinatura, audiência, emissor e expiração.
                return TokenVerifier.newBuilder()
                    .setAudience(clientId)
                    .setIssuer(issuer)
                    .build()
                    .verify(idToken)
                    .getPayload();
            } catch (TokenVerifier.VerificationException exception) {
                lastFailure = exception;
            }
        }
        LOG.debug("ID token do Google rejeitado", lastFailure);
        throw unauthorized("GOOGLE_TOKEN_INVALID");
    }

    private static boolean isTrue(Object value) {
        return value instanceof Boolean flag ? flag : "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static ResponseStatusException unauthorized(String reason) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, reason);
    }

    /** Identidade confirmada pelo Google. */
    public record GoogleIdentity(String subject, String email, String displayName, String pictureUrl) {}
}
