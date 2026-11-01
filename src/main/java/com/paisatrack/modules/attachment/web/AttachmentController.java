package com.paisatrack.modules.attachment.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.paisatrack.modules.attachment.AttachmentService;
import com.paisatrack.modules.attachment.dto.AttachmentResponse;
import com.paisatrack.platform.api.ApiResponse;
import com.paisatrack.platform.security.CurrentUser;

@RestController
@Tag(name = "Attachments", description = "Receipts on transactions: JPG, PNG, WEBP or PDF, up to 5 MB, 5 per transaction")
class AttachmentController {

    private final AttachmentService service;
    private final CurrentUser currentUser;

    AttachmentController(AttachmentService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/v1/transactions/{id}/attachments")
    ApiResponse<List<AttachmentResponse>> list(@PathVariable UUID id) {
        return ApiResponse.ok(service.list(currentUser.id(), id));
    }

    @PostMapping(value = "/api/v1/transactions/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Attach a receipt", description = "Multipart field 'file'. The type is checked from the content, not the name.")
    ResponseEntity<ApiResponse<AttachmentResponse>> upload(@PathVariable UUID id, @RequestParam("file") MultipartFile file) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Attached", service.upload(currentUser.id(), id, file.getOriginalFilename(), file.getBytes())));
    }

    @GetMapping("/api/v1/attachments/{id}/content")
    @Operation(summary = "Download a receipt (owner only)")
    ResponseEntity<byte[]> content(@PathVariable UUID id) {
        AttachmentService.Content c = service.content(currentUser.id(), id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(c.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(c.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(c.bytes());
    }

    @DeleteMapping("/api/v1/attachments/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(currentUser.id(), id);
        return ResponseEntity.noContent().build();
    }
}
