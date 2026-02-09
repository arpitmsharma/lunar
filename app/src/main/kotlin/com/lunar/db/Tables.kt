package com.lunar.db

import com.lunar.model.RocketStatus
import org.jetbrains.exposed.sql.Table

object Rockets : Table("rockets") {
    val channelId = uuid("channel_id")
    val type = varchar("type", 128).default("")
    val speed = integer("speed").default(0)
    val mission = varchar("mission", 256).default("")
    val status = enumeration<RocketStatus>("status")
    val explosionReason = varchar("explosion_reason", 256).default("")
    val lastApplied = integer("last_applied").default(0)

    override val primaryKey = PrimaryKey(channelId)
}
