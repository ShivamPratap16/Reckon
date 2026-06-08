package com.reckon.platform.metrics

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

@Component
class Metrics(private val registry: MeterRegistry) {
    /** Count a completed/failed/replayed transfer by type+outcome. */
    fun transfer(type: String, outcome: String) = registry.counter("reckon.transfers", "type", type, "outcome", outcome).increment()

    fun <T> timeTransfer(block: () -> T): T = Timer.builder("reckon.transfer.duration")
        .publishPercentiles(0.5, 0.95, 0.99).register(registry)
        .recordCallable(block)!!
}
