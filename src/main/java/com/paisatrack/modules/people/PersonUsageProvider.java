package com.paisatrack.modules.people;

import java.util.UUID;

/** Something that references people. A person any provider reports as used can't be deleted. */
public interface PersonUsageProvider {

    boolean isUsed(UUID userId, UUID personId);
}
