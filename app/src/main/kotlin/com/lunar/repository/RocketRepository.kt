package com.lunar.repository

import com.lunar.model.RocketState
import java.util.UUID

interface RocketRepository {
    fun getForUpdate(channelId: UUID): RocketState?
    fun create(channelId: UUID): RocketState
    fun save(state: RocketState)
    fun findByChannel(channelId: UUID): RocketState?
    fun findAll(sortField: String?, sortOrder: String?): List<RocketState>
}
