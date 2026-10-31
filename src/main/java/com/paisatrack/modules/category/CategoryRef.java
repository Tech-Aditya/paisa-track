package com.paisatrack.modules.category;

import java.util.UUID;

/** Read-only category view for other modules. {@code parentName} is null for top-level categories. */
public record CategoryRef(UUID id, String name, CategoryType type, UUID parentId, String parentName, boolean active) {

    /** The top-level category this rolls up to in summaries. */
    public UUID topLevelId() {
        return parentId != null ? parentId : id;
    }
}
