package com.paisatrack.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.paisatrack.auth.dto.AuthResponse;
import com.paisatrack.auth.dto.LoginRequest;
import com.paisatrack.auth.dto.RefreshTokenRequest;
import com.paisatrack.auth.dto.RegisterRequest;
import com.paisatrack.common.api.ApiResponse;
import com.paisatrack.security.CurrentUser;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Registration, sign-in and session tokens")
class AuthController {

    private final AuthService authService;
    private final CurrentUser currentUser;

    AuthController(AuthService authService, CurrentUser currentUser) {
        this.authService = authService;
        this.currentUser = currentUser;
    }

    @PostMapping("/register")
    @SecurityRequirements
    @Operation(summary = "Create an account and sign in",
            description = "Default currency is INR and timezone Asia/Kolkata unless supplied.")
    ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Account created", authService.register(request)));
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Sign in with email and password")
    ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok("Signed in", authService.login(request));
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    @Operation(summary = "Exchange a refresh token for a new token pair",
            description = "Refresh tokens are single-use. Re-using an old one signs out that whole session family.")
    ApiResponse<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.ok("Token refreshed", authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    @SecurityRequirements
    @Operation(summary = "Sign out the session the refresh token belongs to")
    ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    @Operation(summary = "Sign out of every session on every device")
    ResponseEntity<Void> logoutEverywhere() {
        authService.logoutEverywhere(currentUser.id());
        return ResponseEntity.noContent().build();
    }
}
