package com.paisatrack.modules.export.web;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.paisatrack.modules.export.ExportFormat;
import com.paisatrack.modules.export.ExportService;
import com.paisatrack.modules.report.dto.ReportResponse;
import com.paisatrack.modules.transaction.TransactionType;
import com.paisatrack.modules.transaction.dto.TransactionFilter;
import com.paisatrack.platform.security.CurrentUser;
import com.paisatrack.platform.validation.CurrencyCode;

@RestController
@Tag(name = "Exports", description = "Download transactions and reports as CSV, Excel or PDF")
class ExportController {

    private final ExportService service;
    private final CurrentUser currentUser;

    ExportController(ExportService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/v1/exports/transactions")
    @Operation(summary = "Transactions as a file", description = "Same filters as GET /transactions (paging ignored), oldest first, up to 20,000 rows.")
    ResponseEntity<byte[]> transactions(@RequestParam(defaultValue = "CSV") ExportFormat format, @Valid @ModelAttribute TransactionFilter filter) {
        return send(service.transactions(currentUser.id(), filter, format));
    }

    @GetMapping("/api/v1/exports/reports/{kind}")
    @Operation(summary = "A report as a file", description = "Same parameters and figures as /reports/{kind}.")
    ResponseEntity<byte[]> report(@PathVariable String kind, @RequestParam(defaultValue = "PDF") ExportFormat format,
                                  @RequestParam(required = false) LocalDate date, @RequestParam(required = false) YearMonth month,
                                  @RequestParam(required = false) Integer year, @RequestParam(required = false) LocalDate from,
                                  @RequestParam(required = false) LocalDate to, @RequestParam(required = false) UUID accountId,
                                  @RequestParam(required = false) UUID categoryId, @RequestParam(required = false) TransactionType type,
                                  @RequestParam(required = false) @CurrencyCode String currency) {
        ReportResponse.Kind k = ReportResponse.Kind.valueOf(kind.toUpperCase());
        return send(service.report(currentUser.id(), k, date, month, year, from, to, accountId, categoryId, type, currency, format));
    }

    private static ResponseEntity<byte[]> send(ExportService.ExportFile f) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(f.filename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(f.contentType()))
                .body(f.content());
    }
}
