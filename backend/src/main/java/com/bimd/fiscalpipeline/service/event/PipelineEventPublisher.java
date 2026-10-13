package com.bimd.fiscalpipeline.service.event;

import com.bimd.fiscalpipeline.domain.enums.EventLevel;
import com.bimd.fiscalpipeline.domain.enums.ServiceName;
import com.bimd.fiscalpipeline.domain.model.PipelineEventRecord;
import com.bimd.fiscalpipeline.repository.PipelineEventRepository;
import com.bimd.fiscalpipeline.util.IsoDates;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Single exit for the events the screens show (BACKEND.md §6.6). For now it records the
 * history; the SSE stream and the simulator mirror hook in here in later phases.
 */
@Service
@RequiredArgsConstructor
public class PipelineEventPublisher {

    private final PipelineEventRepository pipelineEventRepository;
    private final Clock clock;

    public void publish(ServiceName service, String documentCode, String message, EventLevel level) {
        PipelineEventRecord event = new PipelineEventRecord();
        event.setId(UUID.randomUUID());
        event.setOccurredAt(IsoDates.format(clock.instant()));
        event.setService(service);
        event.setDocumentCode(documentCode);
        event.setMessage(message);
        event.setLevel(level);
        pipelineEventRepository.save(event);
    }
}
