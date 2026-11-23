package cn.tuodi.lockboard.core.client

import cn.tuodi.lockboard.core.protocol.Protocol

data class ClientSettings(
    val baud: Int = Protocol.DEFAULT_BAUD,
    /** Queries and immediate confirmations (private 01/11/12/16/1A, every MODBUS request). */
    val readTimeoutMs: Int = 300,
    /** Retries for side-effect-free reads only. Actions are never retried automatically. */
    val readRetries: Int = 2,
    /** Worst case for one lock of a sequential pulse task: 4 × (300 ms on + 50 ms settle). */
    val perLockPulseMaxMs: Int = 1400,
    /** One simultaneous pulse (0x18 / MODBUS 0006): ≈300 ms on + 50 ms, no retries. */
    val simultaneousPulseMs: Int = 350,
    /** Scheduling overhead added to every pulse window (the document gives no number). */
    val actionMarginMs: Int = 1500,
    /** Minimum quiet time between the end of one frame and the next transmission. */
    val interFrameGapMs: Int = 20,
    /** After a timeout, wait this long and discard any late reply before the next transmission. */
    val lateReplyGuardMs: Int = 300,
    /** Silence after which an incomplete buffered frame is dropped and the parser resynchronises. */
    val interByteTimeoutMs: Int = 50,
    /**
     * Assumed upper bound on how late a reply can arrive. A reply to a request written while an earlier
     * request with the same reply shape was unanswered (within this age) cannot be attributed.
     */
    val lateReplyMaxAgeMs: Int = 10_000,
    /** Swallow an exact copy of each transmitted frame (adapters that echo TX; MODBUS 05/06 replies equal the request). */
    val discardTxEcho: Boolean = false,
) {
    /** Window during which a pulse task may run: sequential = n × per-lock budget, simultaneous = one pulse; plus margin. */
    fun pulseWindowMs(sequential: Boolean, channels: Int): Long = when {
        channels <= 0 -> 0
        sequential -> channels.toLong() * perLockPulseMaxMs + actionMarginMs
        else -> simultaneousPulseMs.toLong() + actionMarginMs
    }

    /**
     * Real boards need ≥ 4 × 350 ms per lock (sequential) and ≈350 ms for a simultaneous pulse plus
     * scheduling: shorter budgets would let a new pulse replace a running one. Shorter values are only
     * meaningful for an accelerated simulator.
     */
    fun withPhysicalMinimums(): ClientSettings = copy(
        perLockPulseMaxMs = maxOf(perLockPulseMaxMs, 1400),
        simultaneousPulseMs = maxOf(simultaneousPulseMs, 350),
        actionMarginMs = maxOf(actionMarginMs, 500),
    )

    /** Time on the wire for [bytes] bytes at 10 bits per byte (8N1), rounded up. */
    fun wireTimeMs(bytes: Int): Long = (bytes * 10_000L + baud - 1) / baud

    fun validated(): ClientSettings = copy(
        baud = baud.coerceIn(300, 921_600),
        readTimeoutMs = readTimeoutMs.coerceIn(20, 10_000),
        readRetries = readRetries.coerceIn(0, 5),
        perLockPulseMaxMs = perLockPulseMaxMs.coerceIn(50, 10_000),
        simultaneousPulseMs = simultaneousPulseMs.coerceIn(20, 10_000),
        actionMarginMs = actionMarginMs.coerceIn(0, 30_000),
        interFrameGapMs = interFrameGapMs.coerceIn(0, 2_000),
        lateReplyGuardMs = lateReplyGuardMs.coerceIn(0, 10_000),
        interByteTimeoutMs = interByteTimeoutMs.coerceIn(5, 1_000),
        lateReplyMaxAgeMs = lateReplyMaxAgeMs.coerceIn(100, 600_000),
    )
}
