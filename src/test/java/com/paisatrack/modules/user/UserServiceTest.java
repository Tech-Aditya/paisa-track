package com.paisatrack.modules.user;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.paisatrack.platform.exception.ApiException;
import com.paisatrack.platform.exception.ErrorCode;
import com.paisatrack.modules.user.dto.ChangePasswordRequest;

class UserServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final UserPreferencesRepository preferences = mock(UserPreferencesRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private UserService service;

    @BeforeEach
    void setUp() {
        when(encoder.encode(anyString())).thenAnswer(inv -> "hashed:" + inv.getArgument(0));
        service = new UserService(users, preferences, encoder, events, Clock.systemUTC());
    }

    @Test
    void unknownEmailStillPerformsAPasswordCheck() {
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.authenticate(" Nobody@Example.com ", "Secret123"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        verify(encoder).matches(eq("Secret123"), anyString());
    }

    @Test
    void disabledUserCannotSignInEvenWithTheRightPassword() throws Exception {
        User user = new User("a@example.com", "hash", "A");
        var status = User.class.getDeclaredField("status");
        status.setAccessible(true);
        status.set(user, UserStatus.DISABLED);
        when(users.findByEmail("a@example.com")).thenReturn(Optional.of(user));
        when(encoder.matches("Secret123", "hash")).thenReturn(true);

        assertThatThrownBy(() -> service.authenticate("a@example.com", "Secret123"))
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_DISABLED);
    }

    @Test
    void changingPasswordPublishesEventForSessionRevocation() {
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.of(new User("a@example.com", "hash", "A")));
        when(encoder.matches("Secret123", "hash")).thenReturn(true);

        service.changePassword(id, new ChangePasswordRequest("Secret123", "BetterPass456"));

        verify(events).publishEvent(new PasswordChangedEvent(id));
    }

    @Test
    void reusingTheCurrentPasswordIsRejectedWithoutSideEffects() {
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.of(new User("a@example.com", "hash", "A")));
        when(encoder.matches("Secret123", "hash")).thenReturn(true);

        assertThatThrownBy(() -> service.changePassword(id, new ChangePasswordRequest("Secret123", "Secret123")))
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION);
        verify(events, never()).publishEvent(any());
    }
}
