package com.paisatrack.modules.user;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paisatrack.platform.exception.ApiException;
import com.paisatrack.platform.exception.ErrorCode;
import com.paisatrack.platform.exception.ResourceNotFoundException;
import com.paisatrack.modules.user.dto.ChangePasswordRequest;
import com.paisatrack.modules.user.dto.UpdateProfileRequest;
import com.paisatrack.modules.user.dto.UserProfileResponse;

/** User identity: registration, credential checks, profile, password. Callers pass the token's user id. */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final String INVALID_CREDENTIALS_MESSAGE = "Invalid email or password";

    private final UserRepository userRepository;
    private final UserPreferencesRepository preferencesRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    // Checked when the email is unknown, so timing doesn't reveal which emails exist.
    private final String timingDummyHash;

    UserService(UserRepository userRepository,
                UserPreferencesRepository preferencesRepository,
                PasswordEncoder passwordEncoder,
                ApplicationEventPublisher events,
                Clock clock) {
        this.userRepository = userRepository;
        this.preferencesRepository = preferencesRepository;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
        this.clock = clock;
        this.timingDummyHash = passwordEncoder.encode("timing-equaliser-" + UUID.randomUUID());
    }

    @Transactional
    public User register(NewUser newUser) {
        String email = User.normalizeEmail(newUser.email());
        if (userRepository.existsByEmail(email)) {
            throw emailTaken();
        }
        User user = new User(email, passwordEncoder.encode(newUser.rawPassword()), newUser.fullName());
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent sign-up for the same email.
            throw emailTaken();
        }
        UserPreferences preferences = preferencesRepository.save(
                new UserPreferences(user.getId(), newUser.defaultCurrency(), newUser.timezone()));
        log.info("User registered userId={}", user.getId());
        events.publishEvent(new UserRegisteredEvent(user.getId(), preferences.getDefaultCurrency()));
        return user;
    }

    /** Verifies credentials. Unknown email and wrong password give the same error. */
    @Transactional
    public User authenticate(String email, String rawPassword) {
        Optional<User> found = userRepository.findByEmail(User.normalizeEmail(email));
        if (found.isEmpty()) {
            passwordEncoder.matches(rawPassword, timingDummyHash);
            log.info("Login failed reason=unknown_email");
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, INVALID_CREDENTIALS_MESSAGE);
        }
        User user = found.get();
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            log.info("Login failed userId={} reason=bad_password", user.getId());
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, INVALID_CREDENTIALS_MESSAGE);
        }
        if (!user.isActive()) {
            log.info("Login failed userId={} reason=disabled", user.getId());
        }
        requireActive(user);
        if (passwordEncoder.upgradeEncoding(user.getPasswordHash())) {
            user.changePasswordHash(passwordEncoder.encode(rawPassword));
        }
        user.recordLogin(clock.instant());
        log.info("Login succeeded userId={}", user.getId());
        return user;
    }

    @Transactional(readOnly = true)
    public User getActiveUser(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User"));
        requireActive(user);
        return user;
    }

    /** Non-throwing check; won't mark the caller's transaction rollback-only. */
    @Transactional(readOnly = true)
    public boolean isActive(UUID userId) {
        return userRepository.findById(userId).map(User::isActive).orElse(false);
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(UUID userId) {
        User user = getActiveUser(userId);
        UserPreferences preferences = preferencesRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User preferences"));
        return UserProfileResponse.from(user, preferences);
    }

    @Transactional
    public UserProfileResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = getActiveUser(userId);
        if (request.fullName() != null) {
            user.rename(request.fullName());
        }
        userRepository.flush();
        UserPreferences preferences = preferencesRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User preferences"));
        return UserProfileResponse.from(user, preferences);
    }

    /** Changes the password; {@link PasswordChangedEvent} then revokes every session. */
    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        User user = getActiveUser(userId);
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new ApiException(ErrorCode.CURRENT_PASSWORD_INCORRECT, "Current password is incorrect");
        }
        if (request.currentPassword().equals(request.newPassword())) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "New password must be different from the current password");
        }
        user.changePasswordHash(passwordEncoder.encode(request.newPassword()));
        log.info("Password changed userId={}", userId);
        events.publishEvent(new PasswordChangedEvent(userId));
    }

    private static void requireActive(User user) {
        if (!user.isActive()) {
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED, "This account is disabled");
        }
    }

    private static ApiException emailTaken() {
        return new ApiException(ErrorCode.EMAIL_ALREADY_REGISTERED, "An account with this email already exists");
    }
}
