package com.paisatrack.modules.user.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.paisatrack.modules.user.UserPreferencesService;
import com.paisatrack.modules.user.UserService;
import com.paisatrack.modules.user.dto.ChangePasswordRequest;
import com.paisatrack.modules.user.dto.PreferencesResponse;
import com.paisatrack.modules.user.dto.UpdatePreferencesRequest;
import com.paisatrack.modules.user.dto.UpdateProfileRequest;
import com.paisatrack.modules.user.dto.UserProfileResponse;
import com.paisatrack.platform.api.ApiResponse;
import com.paisatrack.platform.security.CurrentUser;

/** The caller's own profile. No endpoint takes a user id: the subject is always the token's user. */
@RestController
@RequestMapping("/api/v1/users/me")
@Tag(name = "Users", description = "Profile, password and preferences of the signed-in user")
class UserController {

    private final UserService userService;
    private final UserPreferencesService preferencesService;
    private final CurrentUser currentUser;

    UserController(UserService userService, UserPreferencesService preferencesService, CurrentUser currentUser) {
        this.userService = userService;
        this.preferencesService = preferencesService;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "Get my profile, including preferences")
    ApiResponse<UserProfileResponse> getProfile() {
        return ApiResponse.ok(userService.getProfile(currentUser.id()));
    }

    @PatchMapping
    @Operation(summary = "Update my profile (only supplied fields change)")
    ApiResponse<UserProfileResponse> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponse.ok("Profile updated", userService.updateProfile(currentUser.id(), request));
    }

    @PutMapping("/password")
    @Operation(summary = "Change my password",
            description = "Signs out every session: all refresh tokens are revoked. Existing access tokens expire on their own.")
    ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(currentUser.id(), request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/preferences")
    @Operation(summary = "Get my preferences")
    ApiResponse<PreferencesResponse> getPreferences() {
        return ApiResponse.ok(preferencesService.get(currentUser.id()));
    }

    @PatchMapping("/preferences")
    @Operation(summary = "Update my preferences (only supplied fields change)",
            description = "Changing the default currency affects new records only; existing amounts keep their currency.")
    ApiResponse<PreferencesResponse> updatePreferences(@Valid @RequestBody UpdatePreferencesRequest request) {
        return ApiResponse.ok("Preferences updated", preferencesService.update(currentUser.id(), request));
    }
}
