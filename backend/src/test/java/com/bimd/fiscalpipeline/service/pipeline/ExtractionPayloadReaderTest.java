package com.bimd.fiscalpipeline.service.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.bimd.fiscalpipeline.domain.enums.FieldKey;
import com.bimd.fiscalpipeline.service.pipeline.ExtractedData.Confidence;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExtractionPayloadReaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ExtractionPayloadReader reader = new ExtractionPayloadReader();

    @Test
    void should_readEveryField_when_payloadFollowsTheContract() throws IOException {
        ExtractedData data = reader.read(examplePayload());

        assertThat(data.kind()).isEqualTo("NF-e");
        assertThat(data.cnpj()).isEqualTo("60.701.190/0001-04");
        assertThat(data.issuer()).isEqualTo("Papelaria Ponto Final");
        assertThat(data.issueDate()).isEqualTo("2026-09-28");
        assertThat(data.total()).isEqualTo(new BigDecimal("283.80"));
        assertThat(data.items())
                .containsExactly(
                        new ExtractedData.Item("Resma A4 75g", new BigDecimal("5.000"), new BigDecimal("29.90")),
                        new ExtractedData.Item("Caneta gel (cx 12)", new BigDecimal("2.000"), new BigDecimal("38.50")),
                        new ExtractedData.Item("Pasta catálogo", new BigDecimal("4.000"), new BigDecimal("11.20")));
        assertThat(data.confidences())
                .containsEntry(FieldKey.CNPJ, new Confidence(new BigDecimal("0.9500"), "pdf_text"))
                .containsEntry(FieldKey.ITEMS, new Confidence(new BigDecimal("0.9500"), "llm"))
                .containsOnlyKeys(FieldKey.values());
        assertThat(data.ocrEngine()).isEqualTo("pdf_text");
        assertThat(data.llmModel()).isEqualTo("gemini-3.5-flash-lite");
        assertThat(data.inputTokens()).isEqualTo(1830);
        assertThat(data.outputTokens()).isEqualTo(412);
    }

    /** The contract lets the worker send null for what it could not read. */
    @Test
    void should_keepNullAndZeroConfidence_when_workerCouldNotReadAField() throws IOException {
        Map<String, Object> payload = examplePayload();
        field(payload, "cnpj").put("value", null);
        field(payload, "total").put("value", null);

        ExtractedData data = reader.read(payload);

        assertThat(data.cnpj()).isNull();
        assertThat(data.total()).isNull();
        assertThat(data.confidences().get(FieldKey.CNPJ).value()).isEqualByComparingTo("0");
        assertThat(data.confidences().get(FieldKey.TOTAL).value()).isEqualByComparingTo("0");
        assertThat(data.issuer()).isEqualTo("Papelaria Ponto Final");
    }

    @ParameterizedTest
    @ValueSource(strings = {"283,80", "R$ 283.80", "-1.00", "1.234", "1000000000000.00", "1e400", "NaN", ""})
    void should_dropTotal_when_itIsNotAMoneyValueThatFitsTheColumn(String total) throws IOException {
        Map<String, Object> payload = examplePayload();
        field(payload, "total").put("value", total);

        ExtractedData data = reader.read(payload);

        assertThat(data.total()).isNull();
        assertThat(data.confidences().get(FieldKey.TOTAL).value()).isEqualByComparingTo("0");
    }

    @Test
    void should_dropOrTrim_when_textDoesNotFitItsColumn() throws IOException {
        Map<String, Object> payload = examplePayload();
        field(payload, "cnpj").put("value", "1".repeat(19));
        field(payload, "issuer").put("value", "x".repeat(300));
        field(payload, "issueDate").put("value", "28/09/2026");
        field(payload, "issuer").put("source", "a-source-name-that-is-too-long");
        payload.put("llmModel", "m".repeat(100));

        ExtractedData data = reader.read(payload);

        // Half an identifier is worse than none; a long name is still the name.
        assertThat(data.cnpj()).isNull();
        assertThat(data.issuer()).hasSize(255);
        assertThat(data.issueDate()).isNull();
        assertThat(data.confidences().get(FieldKey.ISSUER).source()).hasSize(16);
        assertThat(data.llmModel()).hasSize(64);
    }

    @Test
    void should_skipItemsThatCannotBeStored_when_readingItems() throws IOException {
        Map<String, Object> payload = examplePayload();
        List<Object> items = new ArrayList<>();
        items.add(Map.of("description", "Válido", "quantity", "38.2", "unitPrice", "6.19"));
        items.add(Map.of("description", "Quantidade enorme", "quantity", "1000000000", "unitPrice", "1.00"));
        items.add(Map.of("description", "Preço com vírgula", "quantity", "1", "unitPrice", "6,19"));
        items.add(Map.of("quantity", "1", "unitPrice", "1.00"));
        items.add("not an item");
        payload.put("items", items);

        ExtractedData data = reader.read(payload);

        assertThat(data.items())
                .containsExactly(new ExtractedData.Item("Válido", new BigDecimal("38.200"), new BigDecimal("6.19")));
    }

    @Test
    void should_clampConfidence_when_itIsOutOfRange() throws IOException {
        Map<String, Object> payload = examplePayload();
        field(payload, "cnpj").put("confidence", 7);
        field(payload, "issuer").put("confidence", -0.5);
        field(payload, "total").put("confidence", "high");
        payload.put("itemsConfidence", 1.5);

        ExtractedData data = reader.read(payload);

        assertThat(data.confidences().get(FieldKey.CNPJ).value()).isEqualByComparingTo("1");
        assertThat(data.confidences().get(FieldKey.ISSUER).value()).isEqualByComparingTo("0");
        assertThat(data.confidences().get(FieldKey.TOTAL).value()).isEqualByComparingTo("0");
        assertThat(data.confidences().get(FieldKey.ITEMS).value()).isEqualByComparingTo("1");
    }

    @Test
    void should_returnEmptyData_when_payloadHasNothingUsable() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("fields", "not an object");
        payload.put("items", 42);
        payload.put("tokens", List.of());
        payload.put("itemsConfidence", 0.9);

        ExtractedData data = reader.read(payload);

        assertThat(data.cnpj()).isNull();
        assertThat(data.issuer()).isNull();
        assertThat(data.issueDate()).isNull();
        assertThat(data.total()).isNull();
        assertThat(data.items()).isEmpty();
        // No item was kept, so there is nothing to be confident about.
        assertThat(data.confidences().get(FieldKey.ITEMS).value()).isEqualByComparingTo("0");
        assertThat(data.inputTokens()).isNull();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> field(Map<String, Object> payload, String name) {
        return (Map<String, Object>) ((Map<String, Object>) payload.get("fields")).get(name);
    }

    private Map<String, Object> examplePayload() throws IOException {
        Map<String, Object> example = MAPPER.readValue(
                Path.of("..", "contracts", "examples", "documento.extraido.json").toFile(), new TypeReference<>() {
                });
        return MAPPER.convertValue(example.get("payload"), new TypeReference<>() {
        });
    }
}
