package com.lightning

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class LightningEngineApplication

fun main(args: Array<String>) {
	runApplication<LightningEngineApplication>(*args)
}
