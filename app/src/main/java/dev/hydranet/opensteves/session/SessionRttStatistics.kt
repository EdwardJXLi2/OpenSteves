package dev.hydranet.opensteves.session

import kotlin.math.abs

internal data class SessionRttSnapshot(
    val samples: Long,
    val latestMs: Long?,
    val bestMs: Long?,
    val worstMs: Long?,
    val jitterMs: Double?,
)

internal class SessionRttStatistics {
    private var samples = 0L
    private var latestMs: Long? = null
    private var bestMs: Long? = null
    private var worstMs: Long? = null
    private var jitterMs = 0.0

    fun record(rttMs: Long) {
        require(rttMs >= 0)
        val previous = latestMs
        samples++
        if (previous != null) jitterMs += (abs(rttMs - previous) - jitterMs) / (samples - 1)
        latestMs = rttMs
        bestMs = bestMs?.coerceAtMost(rttMs) ?: rttMs
        worstMs = worstMs?.coerceAtLeast(rttMs) ?: rttMs
    }

    fun snapshot(): SessionRttSnapshot = SessionRttSnapshot(
        samples, latestMs, bestMs, worstMs, jitterMs.takeIf { samples > 1 },
    )
}
