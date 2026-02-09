package com.lunar.db

import com.lunar.repository.TransactionRunner
import org.jetbrains.exposed.sql.transactions.transaction

class ExposedTransactionRunner : TransactionRunner {
    override fun <T> run(block: () -> T): T = transaction {
        exec("SET LOCAL lock_timeout = '5s'")
        block()
    }
}
