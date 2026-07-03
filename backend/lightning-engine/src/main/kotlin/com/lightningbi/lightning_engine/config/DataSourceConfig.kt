package com.lightningbi.lightning_engine.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource

@Configuration
class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.clickhouse")
    fun clickHouseDataSourceProperties(): HikariConfig = HikariConfig()

    @Bean
    @Primary
    fun clickHouseDataSource(): DataSource =
        HikariDataSource(clickHouseDataSourceProperties())

    @Bean
    @Primary
    fun jdbcTemplate(clickHouseDataSource: DataSource): JdbcTemplate =
        JdbcTemplate(clickHouseDataSource)

    @Bean
    @ConfigurationProperties("spring.datasource.postgres")
    fun postgresDataSourceProperties(): HikariConfig = HikariConfig()

    @Bean(name = ["postgresDataSource"])
    fun postgresDataSource(): DataSource =
        HikariDataSource(postgresDataSourceProperties())

    @Bean(name = ["postgresJdbcTemplate"])
    fun postgresJdbcTemplate(@Qualifier("postgresDataSource") postgresDataSource: DataSource): JdbcTemplate =
        JdbcTemplate(postgresDataSource)

    @Bean(name = ["postgresTransactionManager"])
    fun postgresTransactionManager(
        @Qualifier("postgresDataSource") ds: DataSource
    ): PlatformTransactionManager = DataSourceTransactionManager(ds)
}