package com.bimd.fiscalpipeline.service.storage;

public record PresignedUpload(String uploadUrl, String downloadUrl, String key) {
}
