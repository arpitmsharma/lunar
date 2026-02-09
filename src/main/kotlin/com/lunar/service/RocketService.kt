package com.lunar.service

import com.lunar.model.EventParser
import com.lunar.model.RocketEvent
import com.lunar.model.IncomingMessage
import com.lunar.model.RocketResponse
import com.lunar.model.RocketState
import com.lunar.model.RocketStatus
import com.lunar.repository.RocketRepository
import com.lunar.repository.StashRepository
import com.lunar.repository.TransactionRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.UUID

class RocketService(
    private val rocketRepository: RocketRepository,
    private val stashRepository: StashRepository,
    private val transactionRunner: TransactionRunner
) {

    private val logger = LoggerFactory.getLogger(RocketService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    fun processMessage(incoming: IncomingMessage) {
        val channelId = UUID.fromString(incoming.metadata.channel)
        val messageNumber = incoming.metadata.messageNumber
        val messageType = incoming.metadata.messageType
        val payload = incoming.message

        transactionRunner.run {
            val state = rocketRepository.lockAndGet(channelId)

            if (state == null) {
                rocketRepository.create(channelId)
                logger.info("Rocket created for channel {}", channelId)
            }

            val lastApplied = state?.lastApplied ?: 0

            when {
                messageNumber <= lastApplied -> {
                    logger.debug("Ignoring duplicate/past message {} for channel {}", messageNumber, channelId)
                }
                messageNumber > lastApplied + 1 -> {
                    stashMessage(channelId, messageNumber, messageType, payload)
                }
                else -> {
                    applyAndDrain(channelId, state ?: RocketState(channelId = channelId), messageNumber, messageType, payload)
                }
            }
        }
    }

    private fun stashMessage(channelId: UUID, messageNumber: Int, messageType: String, payload: JsonObject) {
        if (!stashRepository.exists(channelId, messageNumber)) {
            val stashPayload = buildJsonObject {
                put("messageType", messageType)
                put("message", payload)
            }
            stashRepository.save(channelId, messageNumber, stashPayload.toString())
            logger.debug("Stashed message {} for channel {}", messageNumber, channelId)
        }
    }

    private fun applyAndDrain(
        channelId: UUID,
        initialState: RocketState,
        messageNumber: Int,
        messageType: String,
        payload: JsonObject
    ) {
        var currentState = initialState
        var currentNumber = messageNumber
        var currentType = messageType
        var currentPayload = payload
        val drainedMessageNumbers = mutableListOf<Int>()

        while (true) {
            val event = EventParser.parse(currentType, currentPayload)
            currentState = applyEvent(channelId, currentState, event)
                .copy(lastApplied = currentNumber)

            rocketRepository.save(currentState)

            val next = stashRepository.findNext(channelId, currentNumber + 1) ?: break

            val stashedJson = json.parseToJsonElement(next.payload).jsonObject
            currentType = next.messageType
            currentPayload = stashedJson["message"]?.jsonObject ?: break

            currentNumber++
            drainedMessageNumbers.add(currentNumber)
        }

        if (drainedMessageNumbers.isNotEmpty()) {
            stashRepository.deleteAll(channelId, drainedMessageNumbers)
            logger.info("Drained {} stashed messages for channel {}", drainedMessageNumbers.size, channelId)
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

    fun getRocket(channelId: String): RocketResponse? {
        val uuid = UUID.fromString(channelId)
        return rocketRepository.findByChannel(uuid)?.toResponse()
    }

    fun getAllRockets(sort: String?, order: String?): List<RocketResponse> {
        return rocketRepository.findAll(sort, order).map { it.toResponse() }
    }

    private fun RocketState.toResponse() = RocketResponse(
        channel = channelId.toString(),
        type = type,
        speed = speed,
        mission = mission,
        status = status.name.lowercase(),
        explosionReason = explosionReason
    )
}
