package com.bimd.fiscalpipeline.domain.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Queue names are part of the frontend contract (BACKEND.md §5.2). */
@Getter
@RequiredArgsConstructor
public enum BrokerKind {
    KAFKA("kafka", "Kafka", "documentos.recebidos"),
    RABBITMQ("rabbitmq", "RabbitMQ", "extracao.q");

    @JsonValue
    private final String code;
    private final String label;
    private final String inputQueue;

    @JsonCreator
    public static BrokerKind fromCode(String code) {
        return Arrays.stream(values())
                .filter(kind -> kind.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown broker: " + code));
    }
}
