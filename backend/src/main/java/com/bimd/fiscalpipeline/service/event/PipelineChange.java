package com.bimd.fiscalpipeline.service.event;

import com.bimd.fiscalpipeline.domain.dto.PipelineStream;
import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import com.bimd.fiscalpipeline.domain.model.PipelineEventRecord;
import java.util.UUID;

/**
 * Things that happened inside the application and that the screens must hear about. They are
 * published as Spring events and only reach the stream after the transaction that caused them
 * commits, so a change that was rolled back is never shown.
 */
public sealed interface PipelineChange {

    record DocumentChanged(UUID documentId) implements PipelineChange {
    }

    record EventRecorded(PipelineEventRecord event) implements PipelineChange {
    }

    record EnvelopeSeen(PipelineStream.Envelope envelope) implements PipelineChange {
    }

    record BrokerSwitched(BrokerKind broker) implements PipelineChange {
    }
}
