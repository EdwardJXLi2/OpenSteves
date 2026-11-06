package dev.hydranet.opensteves.session

import kotlin.math.abs

internal class SessionDriftCorrection {
    var largeDriftSamples = 0
        private set
    private var lastPositionAppliedAt: Long? = null

    fun shouldCorrect(driftMs: Long, playing: Boolean, nowMs: Long, settings: SessionDebugSettings): Boolean {
        val drift = abs(driftMs)
        if (!playing || drift <= settings.playingDriftThresholdMs) {
            largeDriftSamples = 0
            return !playing && drift > settings.pausedDriftToleranceMs
        }
        largeDriftSamples = (largeDriftSamples + 1).coerceAtMost(settings.requiredDriftSamples)
        return largeDriftSamples >= settings.requiredDriftSamples && cooldownRemainingMs(nowMs, settings) == 0L
    }

    fun cooldownRemainingMs(nowMs: Long, settings: SessionDebugSettings): Long =
        lastPositionAppliedAt?.let { (settings.correctionCooldownMs - (nowMs - it)).coerceAtLeast(0) } ?: 0

    fun resetSamples() {
        largeDriftSamples = 0
    }

    fun positionApplied(nowMs: Long) {
        largeDriftSamples = 0
        lastPositionAppliedAt = nowMs
    }

    fun reset() {
        largeDriftSamples = 0
        lastPositionAppliedAt = null
    }
}
