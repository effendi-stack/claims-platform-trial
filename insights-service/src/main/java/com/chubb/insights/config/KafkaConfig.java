package com.chubb.insights.config;

import com.chubb.insights.projection.InvalidEventException;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Failure handling for the projection consumer. Transient errors (e.g. the DB is briefly
 * unavailable) are retried 3 times, 1s apart. Malformed events are not retried. Either way the
 * record then goes to a dead-letter topic owned by this consumer, so one poison message cannot
 * block its partition forever, and it can be inspected and replayed later.
 */
@Configuration
public class KafkaConfig {

    @Bean
    NewTopic deadLetterTopic(@Value("${insights.kafka.dead-letter-topic}") String dlt) {
        // Same partition count as the source topic: the recoverer keeps the original partition.
        return TopicBuilder.name(dlt).partitions(6).replicas(1).build();
    }

    @Bean
    CommonErrorHandler errorHandler(KafkaTemplate<String, String> template,
                                    @Value("${insights.kafka.dead-letter-topic}") String dlt) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(dlt, record.partition()));
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3));
        handler.addNotRetryableExceptions(JsonProcessingException.class, InvalidEventException.class);
        return handler;
    }
}
