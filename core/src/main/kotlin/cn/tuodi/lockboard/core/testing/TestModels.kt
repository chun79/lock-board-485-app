package cn.tuodi.lockboard.core.testing

import cn.tuodi.lockboard.core.client.OutputKey

enum class Verdict(val label: String, val rank: Int) {
    PASS("通过", 1),
    UNVERIFIED("未验证", 2),
    WARN("警告", 3),
    FAIL("失败", 4),
    SKIP("跳过", 0);

    companion object {
        /** FAIL > WARN > UNVERIFIED > PASS; SKIP only when everything was skipped. */
        fun aggregate(verdicts: Collection<Verdict>): Verdict =
            verdicts.maxByOrNull { it.rank } ?: SKIP
    }
}

enum class Answer { YES, NO, SKIP }

/** Operator interaction for physical observations. Implementations must be cancellable. */
interface Operator {
    /** Yes/no question about something the operator can observe. SKIP = cannot observe. */
    suspend fun ask(question: String): Answer

    /** Asks the operator to do something (e.g. close doors) and waits until they confirm. */
    suspend fun request(instruction: String)
}

/** Operator used when manual confirmation is disabled. */
object NoOperator : Operator {
    override suspend fun ask(question: String) = Answer.SKIP
    override suspend fun request(instruction: String) = Unit
}

data class TestPlan(
    // ---- communication / consistency
    val communication: Boolean = true,
    val communicationCount: Int = 20,
    val consistency: Boolean = true,
    val robustness: Boolean = true,
    val modbusExceptions: Boolean = true,
    val stress: Boolean = true,
    val stressCount: Int = 100,
    // ---- pulse unlocking (private / MODBUS)
    val unlockEach: Boolean = true,
    val modbusUnlockEach: Boolean = true,
    val unlockMany: Boolean = true,
    val modbusUnlockMany: Boolean = true,
    val unlockSimultaneous: Boolean = true,
    val modbusSimultaneous: Boolean = true,
    /** Empty = the first three lock channels. */
    val simultaneousChannels: List<Int> = emptyList(),
    val unlockAll: Boolean = true,
    /** 0x14 pulses all 12 channels; skipped when outputs are wired unless this is set. */
    val allowPulseOnOutputs: Boolean = false,
    // ---- outputs (lamp/relay channels only, verified by reading back register 0004)
    val outputSingle: Boolean = true,
    val outputMulti: Boolean = true,
    val outputTimed: Boolean = true,
    val modbusOutputs: Boolean = true,
    val crossProtocolTimers: Boolean = true,
    /** Short timer used by T11/M6/X1/X2 (the long one is twice this). */
    val timedSeconds: Int = 3,
    /** Allowance for "断电时刻受主循环调度影响" when checking timer expiry. */
    val timerToleranceMs: Int = 1500,
    // ---- judgement
    /** Ask the operator to confirm physical actions (lock popped, lamp on/off). */
    val manualConfirm: Boolean = false,
    /** Lock feedback must confirm each unlock on channels wired with feedback. */
    val requireFeedback: Boolean = true,
    val minSuccessRate: Double = 1.0,
    val stressMinSuccessRate: Double = 0.99,
    /** Wait between the end of an action and the read-back of the feedback. */
    val settleMs: Int = 300,
    val unlockIntervalMs: Int = 300,
    /** Max wait for doors to be closed before an action whose effect must be verified. */
    val waitClosedMs: Int = 5000,
) {
    fun validated() = copy(
        communicationCount = communicationCount.coerceIn(1, 10_000),
        stressCount = stressCount.coerceIn(1, 100_000),
        minSuccessRate = minSuccessRate.coerceIn(0.0, 1.0),
        stressMinSuccessRate = stressMinSuccessRate.coerceIn(0.0, 1.0),
        settleMs = settleMs.coerceIn(0, 10_000),
        unlockIntervalMs = unlockIntervalMs.coerceIn(0, 60_000),
        waitClosedMs = waitClosedMs.coerceIn(0, 120_000),
        timedSeconds = timedSeconds.coerceIn(1, 600),
        timerToleranceMs = timerToleranceMs.coerceIn(0, 60_000),
        simultaneousChannels = simultaneousChannels.filter { it in 1..12 }.distinct().sorted(),
    )
}

data class StepRecord(
    val text: String,
    val verdict: Verdict,
    val detail: String = "",
)

data class ItemResult(
    val id: String,
    val name: String,
    val verdict: Verdict,
    val summary: String,
    val steps: List<StepRecord> = emptyList(),
    val metrics: Map<String, String> = emptyMap(),
)

data class BoardReport(
    val address: Int,
    val boardName: String,
    /** Private 0x01 reply [version, reserved] and MODBUS register 0001. */
    val privateVersion: List<Int>?,
    val modbusVersion: Int?,
    val items: List<ItemResult>,
) {
    val verdict: Verdict get() = Verdict.aggregate(items.map { it.verdict })
}

data class TestReport(
    val appVersion: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val transport: String,
    val physicalTransport: Boolean,
    val settingsSummary: String,
    val plan: TestPlan,
    val boards: List<BoardReport>,
    /** Channels whose switch-off could not be confirmed by a trusted read-back at the end of the run. */
    val possiblyEnergized: List<OutputKey>,
    val cancelled: Boolean,
    val commStats: String,
) {
    val verdict: Verdict get() = Verdict.aggregate(boards.map { it.verdict } + if (possiblyEnergized.isNotEmpty()) listOf(Verdict.FAIL) else emptyList())
}

sealed interface Progress {
    data class ItemStarted(val address: Int, val id: String, val name: String) : Progress
    data class Step(val address: Int, val id: String, val step: StepRecord) : Progress
    data class ItemFinished(val address: Int, val result: ItemResult) : Progress
    data class Message(val text: String) : Progress
}
