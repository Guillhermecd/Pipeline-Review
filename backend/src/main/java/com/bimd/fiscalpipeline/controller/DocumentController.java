package com.bimd.fiscalpipeline.controller;

import com.bimd.fiscalpipeline.domain.dto.DocumentCorrectionRequest;
import com.bimd.fiscalpipeline.domain.dto.DocumentResponse;
import com.bimd.fiscalpipeline.domain.dto.DocumentUpload;
import com.bimd.fiscalpipeline.security.CustomUserDetails;
import com.bimd.fiscalpipeline.service.document.DocumentService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/documentos")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> upload(
            @AuthenticationPrincipal CustomUserDetails principal, @RequestPart("file") MultipartFile file)
            throws IOException {
        DocumentUpload upload = new DocumentUpload(file.getOriginalFilename(), file.getContentType(), file.getBytes());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(documentService.upload(principal.getUser().getId(), upload));
    }

    @GetMapping
    public List<DocumentResponse> list(@RequestParam(defaultValue = "40") int limit) {
        return documentService.listRecent(limit);
    }

    @GetMapping("/{id}")
    public DocumentResponse getById(@PathVariable UUID id) {
        return documentService.getById(id);
    }

    @PostMapping("/{id}/reprocessar")
    public ResponseEntity<DocumentResponse> reprocess(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(documentService.reprocess(id));
    }

    @PatchMapping("/{id}")
    public DocumentResponse correct(@PathVariable UUID id, @Valid @RequestBody DocumentCorrectionRequest correction) {
        return documentService.correct(id, correction);
    }

    @PostMapping("/{id}/aprovar")
    public DocumentResponse approve(@AuthenticationPrincipal CustomUserDetails principal, @PathVariable UUID id) {
        return documentService.approve(id, principal.getUser().getId());
    }

    @PostMapping("/{id}/rejeitar")
    public DocumentResponse reject(@AuthenticationPrincipal CustomUserDetails principal, @PathVariable UUID id) {
        return documentService.reject(id, principal.getUser().getId());
    }
}
