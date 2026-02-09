package com.lunar.db

import com.lunar.model.RocketState
import com.lunar.model.RocketStatus
import com.lunar.repository.RocketRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class ExposedRocketRepository : RocketRepository {

    override fun getForUpdate(channelId: UUID): RocketState? {
        return Rockets.selectAll().where { Rockets.channelId eq channelId }
            .forUpdate().singleOrNull()?.toState()
    }

    override fun create(channelId: UUID): RocketState {
        Rockets.insert {
            it[Rockets.channelId] = channelId
            it[status] = RocketStatus.LAUNCHED
            it[lastApplied] = 0
        }
        return RocketState(channelId = channelId)
    }

    override fun save(state: RocketState) {
        Rockets.update({ Rockets.channelId eq state.channelId }) {
            it[type] = state.type
            it[speed] = state.speed
            it[mission] = state.mission
            it[status] = state.status
            it[explosionReason] = state.explosionReason
            it[lastApplied] = state.lastApplied
        }
    }

    override fun findByChannel(channelId: UUID): RocketState? {
        return transaction {
            Rockets.selectAll().where { Rockets.channelId eq channelId}
                .singleOrNull()?.toState()
        }
    }

    override fun findAll(sortField: String?, sortOrder: String?): List<RocketState> {
        return transaction {
            val column = when (sortField?.lowercase()) {
                "speed" -> Rockets.speed
                "type" -> Rockets.type
                "mission" -> Rockets.mission
                "status" -> Rockets.status
                "channel" -> Rockets.channelId
                else -> Rockets.channelId
            }
            val order = if (sortOrder?.lowercase() == "desc") SortOrder.DESC else SortOrder.ASC
            Rockets.selectAll().orderBy(column, order).map { it.toState() }
        }
    }

    private fun ResultRow.toState() = RocketState(
        channelId = this[Rockets.channelId],
        type = this[Rockets.type],
        speed = this[Rockets.speed],
        mission = this[Rockets.mission],
        status = this[Rockets.status],
        explosionReason = this[Rockets.explosionReason],
        lastApplied = this[Rockets.lastApplied]
    )
}
