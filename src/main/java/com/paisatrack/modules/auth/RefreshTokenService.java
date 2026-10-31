package com.paisatrack.modules.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paisatrack.platform.exception.ApiException;
import com.paisatrack.platform.exception.ErrorCode;
import com.paisatrack.platform.security.JwtProperties;
import com.paisatrack.modules.user.PasswordChangedEvent;
import com.paisatrack.modules.user.UserService;

/**
 * Refresh tokens are single-use. Replaying a rotated token revokes its whole family
 * (all tokens from that login), signing out a possible thief.
 */
@Service
class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository repository;
    private final UserService userService;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    RefreshTokenService(RefreshTokenRepository repository, UserService userService,
                        JwtProperties jwtProperties, Clock clock) {
        this.repository = repository;
        this.userService = userService;
        this.jwtProperties = jwtProperties;
        this.clock = clock;
    }

    /** New token family, on login or registration. */
    @Transactional
    public IssuedRefreshToken issueForNewSession(UUID userId) {
        return issue(userId, UUID.randomUUID(), clock.instant());
    }

    /** Swaps a valid token for a new one. noRollbackFor: revocations stick even when we reject. */
    @Transactional(noRollbackFor = ApiException.class)
    public Rotation rotate(String rawToken) {
        Instant now = clock.instant();
        RefreshToken current = repository.findByTokenHashForUpdate(hash(rawToken))
                .orElseThrow(RefreshTokenService::invalidToken);

        if (current.isRevoked()) {
            // Only a replayed *rotated* token suggests theft; logged-out tokens are just rejected.
            if (current.getRevocationReason() == RevocationReason.ROTATED) {
                int revoked = repository.revokeFamily(current.getFamilyId(), RevocationReason.REUSE_DETECTED, now);
                log.warn("Refresh token reuse, family revoked userId={} tokens={}",
                        current.getUserId(), revoked);
            }
            throw invalidToken();
        }
        if (current.isExpiredAt(now)) {
            throw invalidToken();
        }
        if (!userService.isActive(current.getUserId())) {
            repository.revokeFamily(current.getFamilyId(), RevocationReason.ACCOUNT_DISABLED, now);
            throw invalidToken();
        }

        IssuedRefreshToken next = issue(current.getUserId(), current.getFamilyId(), now);
        current.markRotated(now, next.id());
        log.debug("Token refreshed userId={}", current.getUserId());
        return new Rotation(current.getUserId(), next);
    }

    /** Ends the token's session. Unknown or revoked tokens are ignored. */
    @Transactional
    public void revokeSession(String rawToken) {
        repository.findByTokenHash(hash(rawToken))
                .filter(token -> !token.isRevoked())
                .ifPresent(token -> {
                    repository.revokeFamily(token.getFamilyId(), RevocationReason.LOGOUT, clock.instant());
                    log.info("Logged out userId={}", token.getUserId());
                });
    }

    @Transactional
    public void revokeAllSessions(UUID userId) {
        int revoked = repository.revokeAllForUser(userId, RevocationReason.LOGOUT_ALL, clock.instant());
        log.info("Logged out everywhere userId={} tokens={}", userId, revoked);
    }

    /** Runs in the password-change transaction. */
    @EventListener
    void onPasswordChanged(PasswordChangedEvent event) {
        int revoked = repository.revokeAllForUser(event.userId(), RevocationReason.PASSWORD_CHANGED, clock.instant());
        log.info("Sessions revoked after password change userId={} tokens={}", event.userId(), revoked);
    }

    private IssuedRefreshToken issue(UUID userId, UUID familyId, Instant now) {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        RefreshToken token = new RefreshToken(userId, familyId, hash(raw), now, now.plus(jwtProperties.refreshTokenTtl()));
        repository.save(token);
        return new IssuedRefreshToken(token.getId(), raw, token.getExpiresAt());
    }

    static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static ApiException invalidToken() {
        return new ApiException(ErrorCode.INVALID_REFRESH_TOKEN, "Refresh token is invalid or expired. Please sign in again.");
    }

    record IssuedRefreshToken(UUID id, String value, Instant expiresAt) {
        @Override
        public String toString() {
            return "IssuedRefreshToken[id=" + id + ", expiresAt=" + expiresAt + "]";
        }
    }

    record Rotation(UUID userId, IssuedRefreshToken refreshToken) {
    }
}
