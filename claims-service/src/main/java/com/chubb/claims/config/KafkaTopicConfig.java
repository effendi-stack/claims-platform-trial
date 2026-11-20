package com.chubb.claims.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * claims-service owns the claims.events topic, so it declares it.
 * Events are keyed by claimId: all events for one claim land on one partition,
 * which is what gives per-claim ordering.
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    NewTopic claimsEventsTopic(@Value("${claims.kafka.topic}") String topic,
                               @Value("${claims.kafka.partitions}") int partitions) {
        return TopicBuilder.name(topic)
                .partitions(partitions)
                .replicas(1)
                .build();
    }
}
