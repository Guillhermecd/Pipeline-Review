package com.bimd.fiscalpipeline.controller;

import com.bimd.fiscalpipeline.domain.dto.PresignedUrlResponse;
import com.bimd.fiscalpipeline.service.storage.PresignedUpload;
import com.bimd.fiscalpipeline.service.storage.StorageService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class StorageController {

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final StorageService storageService;

    @GetMapping("/api/storage/presigned-url")
    public PresignedUrlResponse presignedUrl(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam String filename,
            @RequestParam(required = false) String contentType) {
        String key = "uploads/%s/%s-%s".formatted(principal.getUsername(), UUID.randomUUID(), sanitize(filename));
        PresignedUpload upload = storageService.createPresignedUpload(
                key, contentType != null ? contentType : DEFAULT_CONTENT_TYPE);
        return new PresignedUrlResponse(upload.uploadUrl(), upload.downloadUrl(), upload.key());
    }

    private String sanitize(String filename) {
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
