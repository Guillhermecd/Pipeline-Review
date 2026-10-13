package com.bimd.fiscalpipeline.domain.dto;

public record PresignedUrlResponse(String uploadUrl, String downloadUrl, String key) {
}
