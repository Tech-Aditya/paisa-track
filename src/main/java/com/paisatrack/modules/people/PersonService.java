package com.paisatrack.modules.people;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paisatrack.modules.people.dto.PersonRequest;
import com.paisatrack.modules.people.dto.PersonResponse;
import com.paisatrack.platform.exception.ApiException;
import com.paisatrack.platform.exception.ErrorCode;
import com.paisatrack.platform.exception.FieldValidationException;
import com.paisatrack.platform.exception.ResourceNotFoundException;

/** The user's contacts. Names are unique per user, ignoring case. */
@Service
public class PersonService {

    private static final Logger log = LoggerFactory.getLogger(PersonService.class);

    private final PersonRepository repository;
    private final List<PersonUsageProvider> usages;

    PersonService(PersonRepository repository, List<PersonUsageProvider> usages) {
        this.repository = repository;
        this.usages = usages;
    }

    @Transactional(readOnly = true)
    public List<PersonResponse> list(UUID userId) {
        return repository.findByUserIdOrderByNameAsc(userId).stream().map(PersonService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PersonResponse get(UUID userId, UUID personId) {
        return toResponse(load(userId, personId));
    }

    @Transactional
    public PersonResponse create(UUID userId, PersonRequest request) {
        if (request.name() == null) {
            throw new FieldValidationException("name", "is required");
        }
        if (repository.findByUserIdAndNameIgnoreCase(userId, request.name().strip()).isPresent()) {
            throw duplicate(request.name());
        }
        Person person = new Person(userId, request.name());
        applyContact(person, request);
        repository.saveAndFlush(person);
        log.info("Person created personId={}", person.getId());
        return toResponse(person);
    }

    @Transactional
    public PersonResponse update(UUID userId, UUID personId, PersonRequest request) {
        Person person = load(userId, personId);
        if (request.name() != null) {
            if (repository.existsByUserIdAndNameIgnoreCaseAndIdNot(userId, request.name().strip(), personId)) {
                throw duplicate(request.name());
            }
            person.rename(request.name());
        }
        applyContact(person, request);
        repository.flush();
        return toResponse(person);
    }

    /** Only people with no lending or borrowing records can be deleted. */
    @Transactional
    public void delete(UUID userId, UUID personId) {
        Person person = load(userId, personId);
        if (usages.stream().anyMatch(u -> u.isUsed(userId, personId))) {
            throw new ApiException(ErrorCode.PERSON_IN_USE, "This person has lending or borrowing records and can't be deleted");
        }
        repository.delete(person);
    }

    // ---- API for other modules ----

    /** An existing person by id, or by name (created if new). Exactly one of the two must be given. */
    @Transactional
    public PersonRef resolve(UUID userId, UUID personId, String personName) {
        if (personId != null) {
            return PersonRef.of(load(userId, personId));
        }
        if (personName == null || personName.isBlank()) {
            throw new FieldValidationException("personName", "give personId or personName");
        }
        String name = personName.strip();
        if (name.length() > 80) {
            throw new FieldValidationException("personName", "must be at most 80 characters");
        }
        return PersonRef.of(repository.findByUserIdAndNameIgnoreCase(userId, name)
                .orElseGet(() -> repository.saveAndFlush(new Person(userId, name))));
    }

    @Transactional(readOnly = true)
    public PersonRef require(UUID userId, UUID personId) {
        return PersonRef.of(load(userId, personId));
    }

    @Transactional(readOnly = true)
    public Map<UUID, PersonRef> refs(UUID userId, Collection<UUID> personIds) {
        if (personIds.isEmpty()) {
            return Map.of();
        }
        return repository.findByUserIdAndIdIn(userId, personIds).stream()
                .collect(Collectors.toMap(Person::getId, PersonRef::of));
    }

    // ---- internals ----

    private Person load(UUID userId, UUID personId) {
        return repository.findByIdAndUserId(personId, userId).orElseThrow(() -> new ResourceNotFoundException("Person"));
    }

    private static void applyContact(Person person, PersonRequest request) {
        if (request.phone() != null) {
            person.setPhone(blankToNull(request.phone()));
        }
        if (request.email() != null) {
            person.setEmail(blankToNull(request.email()));
        }
        if (request.notes() != null) {
            person.setNotes(blankToNull(request.notes()));
        }
    }

    private static ApiException duplicate(String name) {
        return new ApiException(ErrorCode.DUPLICATE_NAME, "You already have a contact named '" + name.strip() + "'");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static PersonResponse toResponse(Person p) {
        return new PersonResponse(p.getId(), p.getName(), p.getPhone(), p.getEmail(), p.getNotes(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
