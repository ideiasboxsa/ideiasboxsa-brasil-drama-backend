package br.com.brasildrama.auth;

import br.com.brasildrama.identity.VisitorIdentity;
import br.com.brasildrama.identity.VisitorMergeService;
import br.com.brasildrama.rewards.RewardGrantService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}
record RegisterRequest(@NotBlank @Size(min = 2, max = 120) String displayName, @Email @NotBlank String email, @NotBlank @Size(min = 8, max = 100) String password) {}
record GoogleAuthRequest(@NotBlank String idToken) {}
record PasswordForgotRequest(@Email @NotBlank String email) {}
record PasswordResetRequest(@NotBlank String token, @NotBlank @Size(min = 8, max = 100) String newPassword) {}
record UserDto(String id, String email, String displayName) {}
record LoginResponse(String accessToken, String expiresAt, UserDto user, boolean welcomeBonusGranted, long welcomeBonusAmount) {}
record ProfileDto(String id, String email, String displayName) {}
record ProfileUpdateRequest(@NotBlank @Size(min = 2, max = 120) String displayName) {}
record PlaybackPreferencesDto(boolean autoplay, boolean allowMobileData) {}
record PlaybackPreferencesUpdateRequest(boolean autoplay, boolean allowMobileData) {}

@RestController
public class AuthApi {
    private final UserAccountRepository users;
    private final PasswordEncoder passwords;
    private final JwtService jwt;
    private final RewardGrantService rewardGrants;
    private final VisitorMergeService visitorMerges;
    private final GoogleIdentityVerifier googleIdentities;

    public AuthApi(
        UserAccountRepository users,
        PasswordEncoder passwords,
        JwtService jwt,
        RewardGrantService rewardGrants,
        VisitorMergeService visitorMerges,
        GoogleIdentityVerifier googleIdentities
    ) {
        this.users = users;
        this.passwords = passwords;
        this.jwt = jwt;
        this.rewardGrants = rewardGrants;
        this.visitorMerges = visitorMerges;
        this.googleIdentities = googleIdentities;
    }

    @PostMapping("/v1/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    LoginResponse register(
        @Valid @RequestBody RegisterRequest request,
        @RequestHeader(value = VisitorIdentity.HEADER, required = false) String visitorId
    ) {
        var email = normalizeEmail(request.email());
        if (users.existsByEmailIgnoreCase(email)) throw new ResponseStatusException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS");
        var user = new UserAccount(UUID.randomUUID(), email, request.displayName().trim(), passwords.encode(request.password()));
        users.saveAndFlush(user);
        visitorMerges.merge(visitorId, user.id);
        long welcomeBonus = rewardGrants.grantWelcomeBonus(user.id);
        return response(user, welcomeBonus);
    }

    @PostMapping("/v1/auth/login")
    @Transactional
    LoginResponse login(
        @Valid @RequestBody LoginRequest request,
        @RequestHeader(value = VisitorIdentity.HEADER, required = false) String visitorId
    ) {
        var user = users.findByEmailIgnoreCase(normalizeEmail(request.email()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS"));
        if (user.passwordHash == null || !passwords.matches(request.password(), user.passwordHash))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        visitorMerges.merge(visitorId, user.id);
        long welcomeBonus = rewardGrants.grantWelcomeBonus(user.id);
        return response(user, welcomeBonus);
    }

    /**
     * Login social. Era um stub que devolvia 501 — o app obtinha o ID token pelo
     * Credential Manager e o enviava, mas o servidor nunca o validava.
     *
     * <p>A vinculação procura primeiro pelo {@code sub} do Google, que é estável,
     * e só depois pelo e-mail. A ordem importa: quem trocar o endereço na conta
     * Google continua entrando na mesma conta do Brasil Drama em vez de ganhar
     * uma nova, e a conta criada por e-mail e senha é adotada pelo login social
     * do mesmo endereço em vez de colidir na restrição de unicidade.
     *
     * <p>A vinculação por e-mail só é segura porque
     * {@link GoogleIdentityVerifier} recusa token sem {@code email_verified}.
     */
    @PostMapping("/v1/auth/google")
    @Transactional
    public LoginResponse google(
        @Valid @RequestBody GoogleAuthRequest request,
        @RequestHeader(value = VisitorIdentity.HEADER, required = false) String visitorId
    ) {
        var identity = googleIdentities.verify(request.idToken());

        var user = users.findByGoogleSubject(identity.subject())
            .or(() -> users.findByEmailIgnoreCase(identity.email()))
            .orElse(null);

        boolean isNewAccount = user == null;
        if (isNewAccount) {
            user = new UserAccount(
                UUID.randomUUID(),
                identity.email(),
                displayNameFor(identity),
                // Sem senha: a conta passa a existir apenas via Google até que o
                // usuário defina uma. login() já trata passwordHash nulo como
                // credencial inválida, então não há caminho de entrada aberto.
                null
            );
        }

        // Adota a conta pré-existente criada por e-mail e senha.
        user.googleSubject = identity.subject();
        if (user.displayName == null || user.displayName.isBlank()) {
            user.displayName = displayNameFor(identity);
        }
        users.saveAndFlush(user);

        visitorMerges.merge(visitorId, user.id);
        long welcomeBonus = rewardGrants.grantWelcomeBonus(user.id);
        return response(user, welcomeBonus);
    }

    private static String displayNameFor(GoogleIdentityVerifier.GoogleIdentity identity) {
        if (identity.displayName() != null && !identity.displayName().isBlank()) {
            return identity.displayName().trim();
        }
        int at = identity.email().indexOf('@');
        return at > 0 ? identity.email().substring(0, at) : identity.email();
    }

    @PostMapping("/v1/auth/password/forgot")
    void forgot(@Valid @RequestBody PasswordForgotRequest request) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "PASSWORD_DELIVERY_NOT_CONFIGURED");
    }

    @PostMapping("/v1/auth/password/reset")
    void reset(@Valid @RequestBody PasswordResetRequest request) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "PASSWORD_RESET_NOT_CONFIGURED");
    }

    @GetMapping("/v1/me")
    ProfileDto me(Authentication authentication) {
        return profile(current(authentication));
    }

    @PutMapping("/v1/me")
    @Transactional
    ProfileDto updateProfile(Authentication authentication, @Valid @RequestBody ProfileUpdateRequest request) {
        var user = current(authentication);
        user.displayName = request.displayName().trim();
        return profile(users.save(user));
    }

    @GetMapping("/v1/me/playback-preferences")
    PlaybackPreferencesDto playbackPreferences(Authentication authentication) {
        var user = current(authentication);
        return new PlaybackPreferencesDto(user.autoplay, user.allowMobileData);
    }

    @PutMapping("/v1/me/playback-preferences")
    @Transactional
    PlaybackPreferencesDto updatePlaybackPreferences(Authentication authentication, @RequestBody PlaybackPreferencesUpdateRequest request) {
        var user = current(authentication);
        user.autoplay = request.autoplay();
        user.allowMobileData = request.allowMobileData();
        users.save(user);
        return new PlaybackPreferencesDto(user.autoplay, user.allowMobileData);
    }

    private UserAccount current(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        UUID id;
        try { id = UUID.fromString(authentication.getName()); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED); }
        return users.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private LoginResponse response(UserAccount user, long welcomeBonus) {
        var issued = jwt.issue(user.id);
        return new LoginResponse(
            issued.value(),
            issued.expiresAt().toString(),
            new UserDto(user.id.toString(), user.email, user.displayName),
            welcomeBonus > 0,
            welcomeBonus
        );
    }

    private ProfileDto profile(UserAccount user) {
        return new ProfileDto(user.id.toString(), user.email, user.displayName);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
