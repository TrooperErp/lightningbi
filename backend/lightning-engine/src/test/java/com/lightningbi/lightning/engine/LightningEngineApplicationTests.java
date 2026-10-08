// src/test/java/com/lightningbi/lightning/engine/LightningEngineApplicationTests.java
package com.lightningbi.lightning.engine;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@Disabled("Richiede i database: i test di integrazione si faranno con Testcontainers")
@SpringBootTest
class LightningEngineApplicationTests {

	@Test
	void contextLoads() {
	}
}