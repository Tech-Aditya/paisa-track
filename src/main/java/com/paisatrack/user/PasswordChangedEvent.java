package com.paisatrack.user;

import java.util.UUID;

/** Published inside the password-change transaction; session revocation commits with it. */
public record PasswordChangedEvent(UUID userId) {
}
