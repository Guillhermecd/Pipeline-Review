package com.bimd.fiscalpipeline.config;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Wires the S3 client/presigner against MinIO in dev (path-style) and real S3 in prod alike. */
@Configuration
public class StorageConfig {

    @Bean
    public S3Client s3Client(
            @Value("${app.storage.endpoint}") String endpoint,
            @Value("${app.storage.region}") String region,
            @Value("${app.storage.access-key-id}") String accessKeyId,
            @Value("${app.storage.secret-access-key}") String secretAccessKey,
            @Value("${app.storage.force-path-style}") boolean forcePathStyle) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(forcePathStyle)
                        .build())
                .build();
    }

    @Bean
    public S3Presigner s3Presigner(
            @Value("${app.storage.endpoint}") String endpoint,
            @Value("${app.storage.region}") String region,
            @Value("${app.storage.access-key-id}") String accessKeyId,
            @Value("${app.storage.secret-access-key}") String secretAccessKey,
            @Value("${app.storage.force-path-style}") boolean forcePathStyle) {
        // Without this, the presigner defaults to virtual-hosted-style URLs
        // (bucket.endpoint) even though the S3Client above uses path-style —
        // MinIO doesn't answer on the bucket-as-subdomain form, so every
        // presigned URL 404s despite the client itself working fine.
        return S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(forcePathStyle)
                        .build())
                .build();
    }
}
