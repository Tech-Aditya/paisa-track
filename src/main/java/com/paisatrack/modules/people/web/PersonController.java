package com.paisatrack.modules.people.web;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.paisatrack.modules.people.PersonService;
import com.paisatrack.modules.people.dto.PersonRequest;
import com.paisatrack.modules.people.dto.PersonResponse;
import com.paisatrack.platform.api.ApiResponse;
import com.paisatrack.platform.security.CurrentUser;

@RestController
@RequestMapping("/api/v1/people")
@Tag(name = "People", description = "Contacts you lend to or borrow from")
class PersonController {

    private final PersonService service;
    private final CurrentUser currentUser;

    PersonController(PersonService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "My contacts, by name")
    ApiResponse<List<PersonResponse>> list() {
        return ApiResponse.ok(service.list(currentUser.id()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One contact")
    ApiResponse<PersonResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(currentUser.id(), id));
    }

    @PostMapping
    @Operation(summary = "Add a contact")
    ResponseEntity<ApiResponse<PersonResponse>> create(@Valid @RequestBody PersonRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Contact added", service.create(currentUser.id(), request)));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update a contact")
    ApiResponse<PersonResponse> update(@PathVariable UUID id, @Valid @RequestBody PersonRequest request) {
        return ApiResponse.ok("Contact updated", service.update(currentUser.id(), id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a contact with no lending or borrowing records")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(currentUser.id(), id);
        return ResponseEntity.noContent().build();
    }
}
