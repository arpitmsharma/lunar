package com.lunar.service

import com.lunar.model.EventParser
import com.lunar.model.RocketEvent
import com.lunar.model.IncomingMessage
import com.lunar.model.RocketResponse
import com.lunar.model.RocketState
import com.lunar.model.RocketStatus
import com.lunar.repository.RocketRepository
import com.lunar.repository.TransactionRunner
import org.slf4j.LoggerFactory
import java.util.UUID

class RocketService(
    private val rocketRepository: RocketRepository,
    private val transactionRunner: TransactionRunner
) {

    private val logger = LoggerFactory.getLogger(RocketService::class.java)

    /**
     * Apply a sequenced message to the rocket state
     * This method assumes messages arrive in order (sequencing is handled by Redis)
     */
    fun applyMessage(incoming: IncomingMessage) {
        val channelId = UUID.fromString(incoming.metadata.channel)
        val messageNumber = incoming.metadata.messageNumber
        val messageType = incoming.metadata.messageType
        val payload = incoming.message

        transactionRunner.run {
            val state = rocketRepository.getForUpdate(channelId)

            if (state == null) {
                rocketRepository.create(channelId)
                logger.info("Rocket created for channel {}", channelId)
            }

            val currentState = state ?: RocketState(channelId = channelId)
            val event = EventParser.parse(messageType, payload)
            val newState = applyEvent(channelId, currentState, event)
                .copy(lastApplied = messageNumber)

            rocketRepository.save(newState)
            logger.debug("Applied message {} for channel {}", messageNumber, channelId)
        }
    }

    private fun applyEvent(channelId: UUID, state: RocketState, event: RocketEvent): RocketState {
        if (state.status == RocketStatus.EXPLODED && event !is RocketEvent.Launched) {
            logger.debug("Rocket {} is exploded, ignoring event", channelId)
            return state
        }

        logger.info("Applying event {} for channel {}", event::class.simpleName, channelId)
        return event.applyTo(state)
    }

    fun getRocket(channelId: UUID): RocketResponse? {
        return rocketRepository.findByChannel(channelId)?.toResponse()
    }

    fun getAllRockets(sort: String?, order: String?): List<RocketResponse> {
        return rocketRepository.findAll(sort, order).map { it.toResponse() }
    }

    private fun RocketState.toResponse() = RocketResponse(
        channel = channelId.toString(),
        type = type,
        speed = speed,
        mission = mission,
        status = status.toApiString(),
        explosionReason = explosionReason
    )
}
