package com.reckon.consumer.listener

import com.fasterxml.jackson.databind.ObjectMapper
import com.reckon.consumer.model.PaymentEvent
import com.reckon.consumer.service.RewardsService
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class RewardsConsumer(private val rewards: RewardsService, private val mapper: ObjectMapper) {
    @KafkaListener(
        topics = ["\${reckon.outbox.topic}"],
        groupId = "rewards",
        autoStartup = "\${reckon.consumers.enabled:true}",
    )
    fun onMessage(payload: String) {
        rewards.award(mapper.readValue(payload, PaymentEvent::class.java))
    }
}
