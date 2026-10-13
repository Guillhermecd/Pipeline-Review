package com.bimd.fiscalpipeline.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/** Creates the bucket on boot so a fresh MinIO instance works without a manual setup step. */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageBucketInitializer implements CommandLineRunner {

    private final S3Client s3Client;

    @Value("${app.storage.bucket}")
    private String bucket;

    /** Storage being down must not stop the boot: only PostgreSQL is mandatory (BACKEND.md §3). */
    @Override
    public void run(String... args) {
        try {
            ensureBucket();
        } catch (SdkException e) {
            log.warn("Storage unavailable on boot, bucket '{}' not checked: {}", bucket, e.getMessage());
        }
    }

    private void ensureBucket() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
    }
}
