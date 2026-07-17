plugins {
	kotlin("jvm") version "2.2.21"
	kotlin("plugin.spring") version "2.2.21"
	id("org.springframework.boot") version "4.0.5"
	id("io.spring.dependency-management") version "1.1.7"
	id("com.vaadin") version "25.1.0"
}

group = "com.lightningbi"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

extra["vaadinVersion"] = "25.1.0"

// FIX: forza Flyway 10.x su tutto il BOM (Boot 4 gestisce 11.x, incompatibile
// con flyway-database-clickhouse 10.24.0 -> era la causa del "Query failed").
// Questo override allinea flyway-core alla versione del plugin ClickHouse.
extra["flyway.version"] = "10.22.0"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	//implementation("org.springframework.boot:spring-boot-starter-data-jpa")

	implementation("com.vaadin:vaadin-spring-boot-starter")
	developmentOnly("com.vaadin:vaadin-dev")

	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
	implementation("tools.jackson.module:jackson-module-kotlin")
	implementation("com.oracle.database.jdbc:ojdbc11:23.5.0.24.07")
	implementation("com.ibm.db2:jcc:11.5.9.0")
	implementation("com.microsoft.sqlserver:mssql-jdbc:12.8.1.jre11")

	// Migrations: Flyway -> ClickHouse, Liquibase -> Postgres
	// (niente versione esplicita su flyway-core: la governa extra["flyway.version"])
	implementation("org.flywaydb:flyway-database-clickhouse:10.24.0")
	implementation("org.liquibase:liquibase-core")
	implementation("org.springframework.boot:spring-boot-liquibase")

	// Driver DB
	implementation("com.clickhouse:clickhouse-jdbc:0.9.8:all")
	implementation("org.apache.httpcomponents.client5:httpclient5:5.3.1")
	runtimeOnly("org.postgresql:postgresql")

	// JWT
	implementation("io.jsonwebtoken:jjwt-api:0.12.6")
	runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
	runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

	// Test
	testImplementation("org.springframework.boot:spring-boot-starter-data-redis-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
	imports {
		mavenBom("com.vaadin:vaadin-bom:${property("vaadinVersion")}")
	}
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}