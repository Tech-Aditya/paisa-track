package com.paisatrack.modules.people;

import java.util.UUID;

/** Read-only person view for other modules. */
public record PersonRef(UUID id, String name) {

    static PersonRef of(Person person) {
        return new PersonRef(person.getId(), person.getName());
    }
}
