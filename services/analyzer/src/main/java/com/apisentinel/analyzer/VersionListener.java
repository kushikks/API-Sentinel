package com.apisentinel.analyzer;

import com.apisentinel.shared.Events;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
class VersionListener {
    private static final TypeReference<Events.Envelope<Events.VersionRegistered>> ENVELOPE = new TypeReference<>() {};
    private final JobStore jobs;
    private final ObjectMapper json;

    VersionListener(JobStore jobs, ObjectMapper json) {
        this.jobs = jobs;
        this.json = json;
    }

    @RabbitListener(queues = Events.ANALYZER_QUEUE)
    void accept(Message message) {
        if (message.getBody().length > 32_768) throw new AmqpRejectAndDontRequeueException("Event exceeds 32 KiB.");
        try {
            var event = json.readValue(message.getBody(), ENVELOPE);
            validate(event);
            jobs.ingest(event);
        } catch (IOException | IllegalArgumentException invalid) {
            throw new AmqpRejectAndDontRequeueException("Invalid or conflicting VERSION_REGISTERED event.");
        }
    }

    private static void validate(Events.Envelope<Events.VersionRegistered> event) {
        if (event == null || event.schemaVersion() != 1 || !"VERSION_REGISTERED".equals(event.eventType())
                || !"registry-service".equals(event.producer()) || event.eventId() == null || event.correlationId() == null
                || event.occurredAt() == null || event.entityId() == null || event.payload() == null) {
            throw new IllegalArgumentException("Invalid event envelope.");
        }
        var request = event.payload();
        if (request.analysisId() == null || request.apiId() == null || request.versionId() == null
                || request.ownerId() == null || request.submittedBy() == null || !request.versionId().equals(event.entityId())
                || request.versionId().equals(request.baselineVersionId())) {
            throw new IllegalArgumentException("Invalid event payload.");
        }
    }
}
