package com.chubb.claims.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Publishes committed outbox rows to Kafka, oldest first, then marks them published.
 *
 * Delivery is at-least-once: if the process dies after sending but before marking, the
 * batch is sent again, which is why every consumer dedupes. FOR UPDATE SKIP LOCKED lets
 * several instances run the relay without publishing the same row concurrently.
 * Per-claim ordering holds because rows are sent sequentially, keyed by claimId.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final String topic;
    private final int batchSize;

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                       @Value("${claims.kafka.topic}") String topic,
                       @Value("${claims.outbox.batch-size:100}") int batchSize) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.topic = topic;
        this.batchSize = batchSize;
    }

    private record Row(UUID id, UUID aggregateId, String eventType, String payload) {}

    @Scheduled(fixedDelayString = "${claims.outbox.poll-interval:PT0.5S}")
    public void publishPending() {
        try {
            int sent;
            do {
                sent = tx.execute(status -> publishBatch());
            } while (sent == batchSize);
        } catch (Exception e) {
            // Rows stay unpublished and are retried on the next tick.
            log.warn("Outbox relay failed, will retry: {}", e.getMessage());
        }
    }

    private int publishBatch() {
        List<Row> rows = jdbc.query(
                "SELECT id, aggregate_id, event_type, payload::text FROM outbox_event "
                        + "WHERE published_at IS NULL ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4)),
                batchSize);

        for (Row row : rows) {
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(topic, row.aggregateId().toString(), row.payload());
            record.headers().add("eventType", row.eventType().getBytes(StandardCharsets.UTF_8));
            record.headers().add("eventId", row.id().toString().getBytes(StandardCharsets.UTF_8));
            try {
                kafka.send(record).get(10, TimeUnit.SECONDS); // wait for the broker ack before marking
            } catch (Exception e) {
                throw new IllegalStateException("Kafka send failed for outbox row " + row.id(), e);
            }
            jdbc.update("UPDATE outbox_event SET published_at = now() WHERE id = ?", row.id());
        }
        return rows.size();
    }
}
