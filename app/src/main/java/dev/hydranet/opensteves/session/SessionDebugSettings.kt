package dev.hydranet.opensteves.session

internal data class SessionDebugSettings(
    val pausedDriftToleranceMs: Long = 500,
    val playingDriftThresholdMs: Long = 2_000,
    val requiredDriftSamples: Int = 3,
    val correctionCooldownMs: Long = 10_000,
    val schedulingLeadMs: Long = 600,
    val clockSyncIntervalMs: Long = 2_000,
    val playbackSyncIntervalMs: Long = 2_000,
    val peerTimeoutMs: Long = 12_000,
    val clockSampleMaxAgeMs: Long = 30_000,
    val maxClockRttMs: Long = 5_000,
) {
    fun validationError(): String? = when {
        pausedDriftToleranceMs !in 0..10_000 -> "Paused drift tolerance must be between 0 and 10000 ms."
        playingDriftThresholdMs !in pausedDriftToleranceMs..30_000 ->
            "Playing drift threshold must be at least the paused tolerance and at most 30000 ms."
        requiredDriftSamples !in 1..30 -> "Consecutive samples must be between 1 and 30."
        correctionCooldownMs !in 0..120_000 -> "Correction cooldown must be between 0 and 120000 ms."
        schedulingLeadMs !in 0..10_000 -> "Scheduling lead must be between 0 and 10000 ms."
        clockSyncIntervalMs !in 250..30_000 -> "Clock sync interval must be between 250 and 30000 ms."
        playbackSyncIntervalMs !in 250..60_000 -> "Playback sync interval must be between 250 and 60000 ms."
        peerTimeoutMs !in 3 * clockSyncIntervalMs..120_000 ->
            "Connection timeout must be at least three clock sync intervals and at most 120000 ms."
        clockSampleMaxAgeMs !in 1_000..300_000 -> "Clock sample age limit must be between 1000 and 300000 ms."
        maxClockRttMs !in 1..10_000 -> "Clock RTT limit must be between 1 and 10000 ms."
        else -> null
    }
}
