package com.lunar.repository

interface TransactionRunner {
    fun <T> run(block: () -> T): T
}
