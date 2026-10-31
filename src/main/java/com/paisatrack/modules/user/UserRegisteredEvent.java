package com.paisatrack.modules.user;

import java.util.UUID;

/** Published inside the registration transaction so default data is created atomically with the user. */
public record UserRegisteredEvent(UUID userId, String defaultCurrency) {
}
