package com.reckon.platform

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

@Configuration
class KafkaConfig(@Value("\${reckon.outbox.topic}") private val topic: String) {
    @Bean fun paymentEventsTopic(): NewTopic = TopicBuilder.name(topic).partitions(3).replicas(1).build()
}
