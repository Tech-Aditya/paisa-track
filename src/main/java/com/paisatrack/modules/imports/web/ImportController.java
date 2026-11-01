package com.paisatrack.modules.imports.web;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.paisatrack.modules.imports.ImportService;
import com.paisatrack.modules.imports.dto.ImportCommitRequest;
import com.paisatrack.modules.imports.dto.ImportMapping;
import com.paisatrack.modules.imports.dto.ImportPreviewResponse;
import com.paisatrack.modules.imports.dto.ImportResult;
import com.paisatrack.platform.api.ApiResponse;
import com.paisatrack.platform.idempotency.IdempotencyService;
import com.paisatrack.platform.security.CurrentUser;

@RestController
@RequestMapping("/api/v1/imports")
@Tag(name = "Import", description = "Bank statement CSV import: preview with duplicate checks, then commit the rows you keep")
class ImportController {

    private final ImportService service;
    private final CurrentUser currentUser;

    ImportController(ImportService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Read a statement without saving anything",
            description = "Multipart field 'file' (CSV, up to 2 MB / 2000 rows). Columns are detected; send the mapping fields to override.")
    ApiResponse<ImportPreviewResponse> preview(@RequestParam("file") MultipartFile file, @RequestParam UUID accountId,
                                               @RequestParam(required = false) Integer headerRow,
                                               @RequestParam(required = false) Integer dateColumn,
                                               @RequestParam(required = false) Integer descriptionColumn,
                                               @RequestParam(required = false) Integer debitColumn,
                                               @RequestParam(required = false) Integer creditColumn,
                                               @RequestParam(required = false) Integer amountColumn,
                                               @RequestParam(required = false) Integer typeColumn,
                                               @RequestParam(required = false) String dateFormat) throws IOException {
        ImportMapping mapping = new ImportMapping(headerRow, dateColumn, descriptionColumn, debitColumn, creditColumn, amountColumn,
                typeColumn, dateFormat);
        return ApiResponse.ok(service.preview(currentUser.id(), accountId, file.getOriginalFilename(), file.getBytes(), mapping));
    }

    @PostMapping("/commit")
    @Operation(summary = "Save the rows you kept", description = "All rows are saved or none. Send an Idempotency-Key to make retries safe.")
    ResponseEntity<ApiResponse<ImportResult>> commit(@Valid @RequestBody ImportCommitRequest request,
                                                     @Parameter(description = "Send a unique value to make retries safe")
                                                     @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        ImportResult result = service.commit(currentUser.id(), request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(result.imported() + " transactions imported", result));
    }

    @GetMapping
    @Operation(summary = "My last 10 imports")
    ApiResponse<List<ImportResult>> recent() {
        return ApiResponse.ok(service.recent(currentUser.id()));
    }
}
