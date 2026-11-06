package dev.hydranet.opensteves.session

import kotlin.math.abs

internal class SessionDriftCorrection {
    private var largeDriftSamples = 0
    private var lastPositionAppliedAt: Long? = null

    fun shouldCorrect(driftMs: Long, playing: Boolean, nowMs: Long): Boolean {
        val drift = abs(driftMs)
        if (!playing || drift <= MAX_PLAYING_DRIFT_MS) {
            largeDriftSamples = 0
            return !playing && drift > TOLERATED_DRIFT_MS
        }
        largeDriftSamples = (largeDriftSamples + 1).coerceAtMost(REQUIRED_SAMPLES)
        val lastApplied = lastPositionAppliedAt
        return largeDriftSamples >= REQUIRED_SAMPLES &&
            (lastApplied == null || nowMs - lastApplied >= CORRECTION_COOLDOWN_MS)
    }

    fun positionApplied(nowMs: Long) {
        largeDriftSamples = 0
        lastPositionAppliedAt = nowMs
    }

    fun reset() {
        largeDriftSamples = 0
        lastPositionAppliedAt = null
    }

    private companion object {
        const val TOLERATED_DRIFT_MS = 500L
        const val MAX_PLAYING_DRIFT_MS = 2_000L
        const val REQUIRED_SAMPLES = 3
        const val CORRECTION_COOLDOWN_MS = 10_000L
    }
}
