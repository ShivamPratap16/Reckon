package com.walletx

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class WalletxApplication

fun main(args: Array<String>) {
    runApplication<WalletxApplication>(*args)
}
