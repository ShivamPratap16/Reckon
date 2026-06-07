package com.reckon.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class NotificationsConsumer(private val notifications: NotificationsService, private val mapper: ObjectMapper) {
    @KafkaListener(
        topics = ["\${reckon.outbox.topic}"],
        groupId = "notifications",
        autoStartup = "\${reckon.consumers.enabled:true}",
    )
    fun onMessage(payload: String) {
        notifications.notify(mapper.readValue(payload, PaymentEvent::class.java))
    }
}
