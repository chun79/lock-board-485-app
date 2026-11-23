package cn.tuodi.lockboard.core.testing

import cn.tuodi.lockboard.core.client.CommResult
import cn.tuodi.lockboard.core.client.LockBoardClient
import cn.tuodi.lockboard.core.protocol.Command
import kotlinx.coroutines.delay

data class EndurancePlan(
    val address: Int,
    val channels: List<Int>,
    val cycles: Int = 100,
    /** Pause after each cycle over all channels. */
    val cycleIntervalMs: Int = 2000,
    val settleMs: Int = 300,
    /** Stop after this many consecutive failures on one channel (0 = never). */
    val stopAfterConsecutiveFailures: Int = 5,
) {
    fun validated() = copy(
        channels = channels.filter { it in 1..12 }.distinct().sorted(),
        cycles = cycles.coerceIn(1, 1_000_000),
        cycleIntervalMs = cycleIntervalMs.coerceIn(0, 3_600_000),
        settleMs = settleMs.coerceIn(0, 10_000),
        stopAfterConsecutiveFailures = stopAfterConsecutiveFailures.coerceAtLeast(0),
    )
}

/**
 * Per-channel counters. "Verified" requires the board to report 01 **and** an independent read-back to
 * go from closed (before) to open (after). The board's own 01 alone is not feedback evidence; 00 and
 * abnormal replies are failures.
 */
data class ChannelEndurance(
    val channel: Int,
    val commandsSent: Int = 0,
    val validReplies: Int = 0,
    val boardReportedOpen: Int = 0,
    val boardReportedFail: Int = 0,
    val verifiedActions: Int = 0,
    val unverified: Int = 0,
    val failures: Int = 0,
    val consecutiveFailures: Int = 0,
    val lastError: String = "",
)

data class EnduranceStatus(
    val plan: EndurancePlan,
    val cycle: Int,
    val channels: List<ChannelEndurance>,
    val finished: Boolean,
    val stopReason: String = "",
) {
    fun summaryText(): String = buildString {
        append("寿命测试 地址 ${plan.address}，第 $cycle/${plan.cycles} 轮")
        if (finished) append("，已结束").append(if (stopReason.isNotBlank()) "（$stopReason）" else "")
        appendLine()
        for (c in channels) {
            appendLine(
                "第 ${c.channel} 路：发送 ${c.commandsSent}，有效回复 ${c.validReplies}，板报打开 ${c.boardReportedOpen}，板报失败 ${c.boardReportedFail}，" +
                    "已验证动作 ${c.verifiedActions}，未验证 ${c.unverified}，失败 ${c.failures}" +
                    if (c.lastError.isNotBlank()) "（最近错误：${c.lastError}）" else "",
            )
        }
    }
}

class EnduranceRunner(
    private val client: LockBoardClient,
    plan: EndurancePlan,
    private val onProgress: (EnduranceStatus) -> Unit = {},
) {
    private val plan = plan.validated()

    @Volatile
    private var stopRequested = false

    fun requestStop() { stopRequested = true }

    suspend fun run(): EnduranceStatus {
        val stats = plan.channels.associateWith { ChannelEndurance(it) }.toMutableMap()
        var cycle = 0
        var reason = ""
        fun status(done: Boolean) = EnduranceStatus(plan, cycle, plan.channels.map { stats.getValue(it) }, done, reason)

        loop@ while (cycle < plan.cycles) {
            cycle++
            for (ch in plan.channels) {
                if (stopRequested) { reason = "操作者停止"; break@loop }
                val before = client.execute(Command.ReadOne(plan.address, ch)).confirmedReply()?.state
                val r = client.execute(Command.UnlockOne(plan.address, ch))
                var s = stats.getValue(ch).copy(commandsSent = stats.getValue(ch).commandsSent + 1)
                val reply = r.confirmedReply()
                fun fail(reason: String) = s.copy(failures = s.failures + 1, consecutiveFailures = s.consecutiveFailures + 1, lastError = reason)
                when {
                    reply == null -> s = fail(r.describe())
                    else -> {
                        val reportedOpen = reply.state == true
                        s = s.copy(
                            validReplies = s.validReplies + 1,
                            boardReportedOpen = s.boardReportedOpen + if (reportedOpen) 1 else 0,
                            boardReportedFail = s.boardReportedFail + if (reportedOpen) 0 else 1,
                        )
                        delay(plan.settleMs.toLong())
                        val after = client.execute(Command.ReadOne(plan.address, ch)).confirmedReply()?.state
                        s = when {
                            !reportedOpen -> fail("板报失败（00）" + if (after == true) "，但读回为打开" else "")
                            before == false && after == true -> s.copy(verifiedActions = s.verifiedActions + 1, consecutiveFailures = 0)
                            else -> s.copy(unverified = s.unverified + 1, consecutiveFailures = 0)
                        }
                    }
                }
                stats[ch] = s
                if (r is CommResult.Failed && !client.isOpen) { reason = "链路断开"; break@loop }
                if (plan.stopAfterConsecutiveFailures > 0 && s.consecutiveFailures >= plan.stopAfterConsecutiveFailures) {
                    reason = "第 $ch 路连续失败 ${s.consecutiveFailures} 次"
                    break@loop
                }
            }
            onProgress(status(false))
            if (cycle < plan.cycles) {
                var waited = 0L
                while (waited < plan.cycleIntervalMs && !stopRequested) {
                    val step = minOf(100L, plan.cycleIntervalMs - waited)
                    delay(step)
                    waited += step
                }
            }
        }
        val done = status(true)
        onProgress(done)
        return done
    }
}
