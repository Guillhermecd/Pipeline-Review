package com.bimd.fiscalpipeline.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The examples in contracts/ must be valid for the JSON Schemas and readable by the Java records.
 * The worker validates the same files: change the contract and both sides break together.
 */
class ContractExamplesTest {

    private static final Path CONTRACTS = Path.of("..", "contracts");
    private static final ObjectMapper MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    private static final JsonSchemaFactory SCHEMAS = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    @ParameterizedTest
    @CsvSource({
        "documento.recebido.json, documento-recebido.payload.schema.json",
        "documento.retry.json, documento-recebido.payload.schema.json",
        "documento.progresso.ocr.json, documento-progresso.payload.schema.json",
        "documento.progresso.retry-wait.json, documento-progresso.payload.schema.json",
        "documento.extraido.json, documento-extraido.payload.schema.json",
    })
    void should_acceptExample_when_validatedAgainstSchemaAndRecord(String exampleName, String payloadSchema)
            throws IOException {
        JsonNode example = MAPPER.readTree(CONTRACTS.resolve("examples").resolve(exampleName).toFile());

        assertThat(schema("envelope.schema.json").validate(example)).isEmpty();
        assertThat(schema(payloadSchema).validate(example.get("payload"))).isEmpty();

        EventEnvelope envelope = MAPPER.treeToValue(example, EventEnvelope.class);
        assertThat(MAPPER.<JsonNode>valueToTree(envelope)).isEqualTo(example);
    }

    @ParameterizedTest
    @CsvSource({"documento.progresso.ocr.json", "documento.progresso.retry-wait.json"})
    void should_readProgressPayload_when_exampleIsAProgressMessage(String exampleName) throws IOException {
        JsonNode payload = MAPPER.readTree(CONTRACTS.resolve("examples").resolve(exampleName).toFile())
                .get("payload");

        ProgressPayload progress = MAPPER.treeToValue(payload, ProgressPayload.class);

        assertThat(progress.stage().name()).isEqualTo(payload.get("stage").asText());
        assertThat(progress.attempt()).isEqualTo(payload.get("attempt").asInt());
    }

    private JsonSchema schema(String name) throws IOException {
        return SCHEMAS.getSchema(MAPPER.readTree(CONTRACTS.resolve("events").resolve(name).toFile()));
    }
}
