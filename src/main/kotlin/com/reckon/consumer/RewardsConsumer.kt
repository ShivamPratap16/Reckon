package com.reckon.consumer

import com.fasterxml.jackson.databind.ObjectMapper
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
