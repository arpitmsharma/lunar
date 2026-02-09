package com.lunar.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {

    @Volatile
    private var initialized = false

    fun init(
        url: String = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/lunar",
        user: String = System.getenv("DATABASE_USER") ?: "lunar",
        password: String = System.getenv("DATABASE_PASSWORD") ?: "lunar"
    ) {
        if (initialized) {
            return
        }

        synchronized(this) {
            if (initialized) {
                return
            }

            val dataSource = hikari(url, user, password)
            Database.connect(dataSource)

            transaction {
                SchemaUtils.create(Rockets)
            }

            initialized = true
        }
    }

    private fun hikari(url: String, user: String, password: String): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = url
            driverClassName = "org.postgresql.Driver"
            username = user
            this.password = password
            maximumPoolSize = 20
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            validate()
        }
        return HikariDataSource(config)
    }
}
