package com.bimd.fiscalpipeline.domain.dto;

/** An uploaded file, already detached from the HTTP layer. */
public record DocumentUpload(String fileName, String mimeType, byte[] content) {
}
