package com.chubb.insights.projection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ClaimEventListener {

    private static final Logger log = LoggerFactory.getLogger(ClaimEventListener.class);

    private final ObjectMapper json;
    private final ClaimSummaryProjector projector;

    public ClaimEventListener(ObjectMapper json, ClaimSummaryProjector projector) {
        this.json = json;
        this.projector = projector;
    }

    @KafkaListener(topics = "${insights.kafka.topic}")
    public void on(ConsumerRecord<String, String> record) throws JsonProcessingException {
        ClaimEventMessage event = json.readValue(record.value(), ClaimEventMessage.class).validated();
        boolean applied = projector.apply(event);
        log.debug("{} v{} for {} -> {}", event.eventType(), event.claim().version(), event.claim().claimId(),
                applied ? "applied" : "skipped (duplicate/stale)");
    }
}
