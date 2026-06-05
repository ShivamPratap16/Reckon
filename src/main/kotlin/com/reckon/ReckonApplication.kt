package com.reckon

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class ReckonApplication

fun main(args: Array<String>) {
    runApplication<ReckonApplication>(*args)
}
