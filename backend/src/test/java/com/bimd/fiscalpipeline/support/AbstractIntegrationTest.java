package com.bimd.fiscalpipeline.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Boots the application against real PostgreSQL and MinIO. Containers are started once and
 * shared by every IT class (singleton pattern), so the Spring context is reused too.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    // MinIO no longer publishes minio/minio on Docker Hub; Chainguard ships the same server.
    static final MinIOContainer MINIO = new MinIOContainer(
            DockerImageName.parse("cgr.dev/chainguard/minio:latest").asCompatibleSubstituteFor("minio/minio"));

    static {
        POSTGRES.start();
        MINIO.start();
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Value("${app.initial-user.email}")
    private String initialUserEmail;

    @Value("${app.initial-user.password}")
    private String initialUserPassword;

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("app.storage.endpoint", MINIO::getS3URL);
        registry.add("app.storage.access-key-id", MINIO::getUserName);
        registry.add("app.storage.secret-access-key", MINIO::getPassword);
    }

    /** Logs in as the seeded user through the real endpoint and returns the Authorization header value. */
    protected String bearerToken() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("email", initialUserEmail, "password", initialUserPassword));
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return "Bearer " + objectMapper.readTree(response).get("token").asText();
    }
}
