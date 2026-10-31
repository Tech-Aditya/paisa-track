package com.paisatrack.user;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paisatrack.exception.ResourceNotFoundException;
import com.paisatrack.user.dto.PreferencesResponse;
import com.paisatrack.user.dto.UpdatePreferencesRequest;

@Service
public class UserPreferencesService {

    private final UserPreferencesRepository preferencesRepository;
    private final Clock clock;

    UserPreferencesService(UserPreferencesRepository preferencesRepository, Clock clock) {
        this.preferencesRepository = preferencesRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PreferencesResponse get(UUID userId) {
        return PreferencesResponse.from(load(userId));
    }

    @Transactional(readOnly = true)
    public ZoneId zoneOf(UUID userId) {
        return ZoneId.of(load(userId).getTimezone());
    }

    /** Today's date in the user's own timezone. */
    @Transactional(readOnly = true)
    public LocalDate today(UUID userId) {
        return LocalDate.now(clock.withZone(zoneOf(userId)));
    }

    @Transactional(readOnly = true)
    public String defaultCurrency(UUID userId) {
        return load(userId).getDefaultCurrency();
    }

    /** Applies only the fields present. Existing records keep their own currency. */
    @Transactional
    public PreferencesResponse update(UUID userId, UpdatePreferencesRequest request) {
        UserPreferences preferences = load(userId);
        if (request.defaultCurrency() != null) {
            preferences.setDefaultCurrency(request.defaultCurrency());
        }
        if (request.timezone() != null) {
            preferences.setTimezone(request.timezone());
        }
        if (request.weekStartDay() != null) {
            preferences.setWeekStartDay(request.weekStartDay());
        }
        if (request.theme() != null) {
            preferences.setTheme(request.theme());
        }
        preferencesRepository.flush();
        return PreferencesResponse.from(preferences);
    }

    private UserPreferences load(UUID userId) {
        return preferencesRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User preferences"));
    }
}
