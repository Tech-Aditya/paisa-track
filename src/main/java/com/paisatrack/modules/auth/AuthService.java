package com.paisatrack.modules.auth;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paisatrack.modules.auth.dto.AuthResponse;
import com.paisatrack.modules.auth.dto.LoginRequest;
import com.paisatrack.modules.auth.dto.RegisterRequest;
import com.paisatrack.platform.security.AccessTokenService;
import com.paisatrack.platform.security.AccessTokenService.IssuedAccessToken;
import com.paisatrack.modules.user.NewUser;
import com.paisatrack.modules.user.User;
import com.paisatrack.modules.user.UserService;

@Service
public class AuthService {

    private final UserService userService;
    private final RefreshTokenService refreshTokenService;
    private final AccessTokenService accessTokenService;

    AuthService(UserService userService, RefreshTokenService refreshTokenService, AccessTokenService accessTokenService) {
        this.userService = userService;
        this.refreshTokenService = refreshTokenService;
        this.accessTokenService = accessTokenService;
    }

    /** Creates the account and signs the new user in. */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        User user = userService.register(new NewUser(
                request.email(), request.password(), request.fullName(), request.defaultCurrency(), request.timezone()));
        return startSession(user.getId());
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userService.authenticate(request.email(), request.password());
        return startSession(user.getId());
    }

    /** Not transactional: rotation commits its own revocations even when it rejects the token. */
    public AuthResponse refresh(String rawRefreshToken) {
        RefreshTokenService.Rotation rotation = refreshTokenService.rotate(rawRefreshToken);
        return buildResponse(rotation.userId(), rotation.refreshToken());
    }

    public void logout(String rawRefreshToken) {
        refreshTokenService.revokeSession(rawRefreshToken);
    }

    public void logoutEverywhere(UUID userId) {
        refreshTokenService.revokeAllSessions(userId);
    }

    private AuthResponse startSession(UUID userId) {
        return buildResponse(userId, refreshTokenService.issueForNewSession(userId));
    }

    private AuthResponse buildResponse(UUID userId, RefreshTokenService.IssuedRefreshToken refreshToken) {
        IssuedAccessToken accessToken = accessTokenService.issue(userId);
        return new AuthResponse(
                "Bearer",
                accessToken.value(),
                accessToken.expiresInSeconds(),
                accessToken.expiresAt(),
                refreshToken.value(),
                refreshToken.expiresAt(),
                userService.getProfile(userId));
    }
}
