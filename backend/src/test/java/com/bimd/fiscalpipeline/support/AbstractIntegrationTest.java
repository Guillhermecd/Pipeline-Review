package com.bimd.fiscalpipeline.support;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import com.bimd.fiscalpipeline.domain.enums.DocumentStatus;
import com.bimd.fiscalpipeline.domain.model.Document;
import com.bimd.fiscalpipeline.messaging.MessagingRouter;
import com.bimd.fiscalpipeline.repository.DocumentRepository;
import com.bimd.fiscalpipeline.service.broker.BrokerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Boots the application against real PostgreSQL, MinIO, Kafka and RabbitMQ. Containers are
 * started once and shared by every IT class (singleton pattern), so the Spring context is
 * reused too. No worker runs: {@link FakeWorker} plays its part.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    protected static final String DOCUMENTS_URL = "/api/v1/documentos";
    protected static final Duration PIPELINE_TIMEOUT = Duration.ofSeconds(30);

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    // MinIO no longer publishes minio/minio on Docker Hub; Chainguard ships the same server.
    static final MinIOContainer MINIO = new MinIOContainer(
            DockerImageName.parse("cgr.dev/chainguard/minio:latest").asCompatibleSubstituteFor("minio/minio"));

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer("rabbitmq:3-management");

    static {
        POSTGRES.start();
        MINIO.start();
        KAFKA.start();
        RABBITMQ.start();
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected DocumentRepository documentRepository;

    @Autowired
    protected BrokerService brokerService;

    @Autowired
    private MessagingRouter messagingRouter;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Value("${app.initial-user.email}")
    private String initialUserEmail;

    @Value("${app.initial-user.password}")
    private String initialUserPassword;

    private FakeWorker fakeWorker;

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("app.storage.endpoint", MINIO::getS3URL);
        registry.add("app.storage.access-key-id", MINIO::getUserName);
        registry.add("app.storage.secret-access-key", MINIO::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.rabbitmq.addresses", RABBITMQ::getAmqpUrl);
        registry.add("app.pipeline.outbox-poll-ms", () -> "100");
    }

    /** The active broker lives in memory and is shared by every test of the run. */
    @BeforeEach
    void startOnKafka() {
        brokerService.switchTo(BrokerKind.KAFKA);
    }

    protected FakeWorker worker() {
        if (fakeWorker == null) {
            fakeWorker = new FakeWorker(messagingRouter, rabbitTemplate, objectMapper, KAFKA.getBootstrapServers());
        }
        return fakeWorker;
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

    /** Uploads a file nobody uploaded before and returns the new document's id. */
    protected UUID uploadNewDocument(String token) throws Exception {
        String body = mockMvc.perform(multipart(DOCUMENTS_URL)
                        .file(new MockMultipartFile("file", "nota.pdf", "application/pdf", uniqueContent()))
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    protected Document awaitStatus(UUID documentId, DocumentStatus expected) {
        await().atMost(PIPELINE_TIMEOUT)
                .until(() -> documentRepository.findById(documentId).orElseThrow().getStatus() == expected);
        return documentRepository.findById(documentId).orElseThrow();
    }

    protected byte[] uniqueContent() {
        return ("%PDF-1.4 " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
    }
}
