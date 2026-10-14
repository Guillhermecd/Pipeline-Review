package com.bimd.fiscalpipeline.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import com.bimd.fiscalpipeline.domain.enums.DocumentStatus;
import com.bimd.fiscalpipeline.domain.model.Document;
import com.bimd.fiscalpipeline.domain.model.PipelineEventRecord;
import com.bimd.fiscalpipeline.repository.PipelineEventRepository;
import com.bimd.fiscalpipeline.support.AbstractIntegrationTest;
import com.bimd.fiscalpipeline.support.FakeWorker.Received;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class BrokerSwitchIT extends AbstractIntegrationTest {

    private static final String BROKER_URL = "/api/v1/broker";

    @Autowired
    private PipelineEventRepository pipelineEventRepository;

    @Test
    void should_describeActiveBroker_when_asked() throws Exception {
        mockMvc.perform(get(BROKER_URL).header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("kafka"))
                .andExpect(jsonPath("$.label").value("Kafka"))
                .andExpect(jsonPath("$.inputQueue").value("documentos.recebidos"))
                .andExpect(jsonPath("$.outputQueue").value("documentos.extraidos"))
                .andExpect(jsonPath("$.retryQueue").value("documentos.recebidos-retry"))
                .andExpect(jsonPath("$.deadLetterQueue").value("documentos.recebidos.DLT"))
                .andExpect(jsonPath("$.consumerConfig").value("consumer group extrator-py · 3 partições"))
                .andExpect(jsonPath("$.retryPolicy").isNotEmpty());
    }

    @Test
    void should_routeNewDocumentsToRabbitMq_and_keepOldOnesOnKafka_when_brokerIsSwitched() throws Exception {
        String token = bearerToken();
        UUID kafkaDocumentId = uploadNewDocument(token);

        mockMvc.perform(put(BROKER_URL)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"rabbitmq\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("rabbitmq"))
                .andExpect(jsonPath("$.label").value("RabbitMQ"))
                .andExpect(jsonPath("$.inputQueue").value("extracao.q"))
                .andExpect(jsonPath("$.outputQueue").value("validacao.q"))
                .andExpect(jsonPath("$.retryQueue").value("extracao.retry (TTL)"))
                .andExpect(jsonPath("$.deadLetterQueue").value("extracao.dlq"))
                .andExpect(jsonPath("$.consumerConfig").value("exchange documentos · prefetch 10"));
        UUID rabbitDocumentId = uploadNewDocument(token);

        Document rabbitDocument = documentRepository.findById(rabbitDocumentId).orElseThrow();
        assertThat(rabbitDocument.getBroker()).isEqualTo(BrokerKind.RABBITMQ);
        Received onRabbit =
                worker().awaitInput(BrokerKind.RABBITMQ, rabbitDocumentId, rabbitDocument.getCurrentRequestId());
        assertThat(onRabbit.envelope().get("broker").asText()).isEqualTo("rabbitmq");
        awaitStatus(rabbitDocumentId, DocumentStatus.QUEUED);

        Document kafkaDocument = documentRepository.findById(kafkaDocumentId).orElseThrow();
        assertThat(kafkaDocument.getBroker()).isEqualTo(BrokerKind.KAFKA);
        worker().awaitInput(BrokerKind.KAFKA, kafkaDocumentId, kafkaDocument.getCurrentRequestId());

        assertThat(pipelineEventRepository.findByDocumentCodeOrderByOccurredAtAsc("—"))
                .extracting(PipelineEventRecord::getMessage)
                .contains("broker alterado para RabbitMQ · novos documentos usam extracao.q");
    }

    @Test
    void should_rejectSwitch_when_brokerIsUnknown() throws Exception {
        mockMvc.perform(put(BROKER_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"sqs\"}"))
                .andExpect(status().isBadRequest());

        assertThat(brokerService.active()).isEqualTo(BrokerKind.KAFKA);
    }

    @Test
    void should_rejectSwitch_when_userIsNotAuthenticated() throws Exception {
        mockMvc.perform(put(BROKER_URL).contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"rabbitmq\"}"))
                .andExpect(status().isForbidden());

        assertThat(brokerService.active()).isEqualTo(BrokerKind.KAFKA);
    }
}
