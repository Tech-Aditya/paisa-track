package com.paisatrack.modules.notification.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.paisatrack.modules.notification.NotificationService;
import com.paisatrack.modules.notification.dto.NotificationPreferenceResponse;
import com.paisatrack.modules.notification.dto.NotificationResponse;
import com.paisatrack.modules.notification.dto.UpdateNotificationPreferencesRequest;
import com.paisatrack.platform.api.ApiResponse;
import com.paisatrack.platform.api.PageResponse;
import com.paisatrack.platform.security.CurrentUser;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "In-app reminders for EMIs, bills, lending, borrowing, budgets and salary")
class NotificationController {

    private final NotificationService service;
    private final CurrentUser currentUser;

    NotificationController(NotificationService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "Notification history, newest first")
    ApiResponse<PageResponse<NotificationResponse>> list(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(service.list(currentUser.id(), unreadOnly, page, size));
    }

    @GetMapping("/unread-count")
    ApiResponse<Map<String, Long>> unreadCount() {
        return ApiResponse.ok(Map.of("unread", service.unreadCount(currentUser.id())));
    }

    @PostMapping("/{id}/read")
    ApiResponse<NotificationResponse> markRead(@PathVariable UUID id) {
        return ApiResponse.ok(service.markRead(currentUser.id(), id));
    }

    @PostMapping("/read-all")
    ApiResponse<Map<String, Integer>> markAllRead() {
        return ApiResponse.ok(Map.of("marked", service.markAllRead(currentUser.id())));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Check for due reminders now", description = "The same check runs hourly. Never creates duplicates.")
    ApiResponse<Map<String, Long>> refresh() {
        UUID userId = currentUser.id();
        long created = service.generate(userId);
        return ApiResponse.ok(Map.of("created", created, "unread", service.unreadCount(userId)));
    }

    @GetMapping("/preferences")
    @Operation(summary = "Per-type preferences: on/off and how many days ahead to remind")
    ApiResponse<List<NotificationPreferenceResponse>> preferences() {
        return ApiResponse.ok(service.preferences(currentUser.id()));
    }

    @PutMapping("/preferences")
    ApiResponse<List<NotificationPreferenceResponse>> updatePreferences(@Valid @RequestBody UpdateNotificationPreferencesRequest request) {
        return ApiResponse.ok("Preferences saved", service.updatePreferences(currentUser.id(), request));
    }
}
