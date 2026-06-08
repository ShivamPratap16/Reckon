package com.reckon.platform.config

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

@Configuration
class KafkaConfig(@Value("\${reckon.outbox.topic}") private val topic: String) {
    @Bean fun paymentEventsTopic(): NewTopic = TopicBuilder.name(topic).partitions(3).replicas(1).build()

    @Bean fun deadLetterTopic(): NewTopic = TopicBuilder.name("$topic.DLT").partitions(3).replicas(1).build()

    @Bean
    fun kafkaErrorHandler(template: KafkaTemplate<String, String>): DefaultErrorHandler {
        val recoverer = DeadLetterPublishingRecoverer(template)
        // 2 retries with no backoff, then send to DLT (do NOT block the partition forever)
        return DefaultErrorHandler(recoverer, FixedBackOff(0L, 2L))
    }
}
