package com.bimd.fiscalpipeline.domain.dto;

/**
 * @param url pre-signed, opens in the browser without credentials
 * @param expiresAt ISO 8601 UTC; after it the link answers 403
 */
public record DocumentFileResponse(String url, String expiresAt) {
}
