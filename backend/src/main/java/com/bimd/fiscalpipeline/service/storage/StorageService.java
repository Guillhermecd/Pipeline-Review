package com.bimd.fiscalpipeline.service.storage;

import com.bimd.fiscalpipeline.exception.StorageUnavailableException;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * Object storage (MinIO in dev, S3 in prod). Presigned URLs come from the template contract;
 * {@link #upload} is the server-side write used by the document upload.
 */
@Service
@RequiredArgsConstructor
public class StorageService {

    private final S3Client s3Client;
    private final S3Presigner presigner;

    @Value("${app.storage.bucket}")
    private String bucket;

    @Value("${app.storage.presigned-url-expires-in}")
    private long expiresInSeconds;

    public void upload(String key, String contentType, byte[] content) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket).key(key).contentType(contentType).build();
        try {
            s3Client.putObject(request, RequestBody.fromBytes(content));
        } catch (SdkException e) {
            throw new StorageUnavailableException(e);
        }
    }

    public PresignedUpload createPresignedUpload(String key, String contentType) {
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucket).key(key).contentType(contentType).build();
        String uploadUrl = presigner.presignPutObject(PutObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofSeconds(expiresInSeconds))
                        .putObjectRequest(putRequest)
                        .build())
                .url()
                .toString();

        GetObjectRequest getRequest = GetObjectRequest.builder().bucket(bucket).key(key).build();
        String downloadUrl = presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofSeconds(expiresInSeconds))
                        .getObjectRequest(getRequest)
                        .build())
                .url()
                .toString();

        return new PresignedUpload(uploadUrl, downloadUrl, key);
    }

    /** A link the browser can open directly for a while, without credentials. */
    public String createPresignedDownload(String key, Duration validity) {
        GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(key).build();
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(validity)
                        .getObjectRequest(request)
                        .build())
                .url()
                .toString();
    }

    public Duration presignedUrlValidity() {
        return Duration.ofSeconds(expiresInSeconds);
    }
}
