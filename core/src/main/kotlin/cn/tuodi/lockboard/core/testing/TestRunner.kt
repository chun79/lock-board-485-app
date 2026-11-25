package cn.tuodi.lockboard.core.testing

import cn.tuodi.lockboard.core.client.Clock
import cn.tuodi.lockboard.core.client.CommResult
import cn.tuodi.lockboard.core.client.DiagnosticProbe
import cn.tuodi.lockboard.core.client.ExecOptions
import cn.tuodi.lockboard.core.client.LockBoardClient
import cn.tuodi.lockboard.core.client.ModbusProbe
import cn.tuodi.lockboard.core.client.OutputKey
import cn.tuodi.lockboard.core.client.SystemClock
import cn.tuodi.lockboard.core.config.BoardProfile
import cn.tuodi.lockboard.core.config.ChannelType
import cn.tuodi.lockboard.core.modbus.Modbus
import cn.tuodi.lockboard.core.modbus.ModbusReply
import cn.tuodi.lockboard.core.modbus.ModbusRequest
import cn.tuodi.lockboard.core.protocol.AckReply
import cn.tuodi.lockboard.core.protocol.Cmd
import cn.tuodi.lockboard.core.protocol.Command
import cn.tuodi.lockboard.core.protocol.LockMask
import cn.tuodi.lockboard.core.protocol.MaskReply
import cn.tuodi.lockboard.core.protocol.Protocol
import cn.tuodi.lockboard.core.protocol.VersionReply
import cn.tuodi.lockboard.core.protocol.hex2
import cn.tuodi.lockboard.core.protocol.toHex
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/**
 * Automatic test plan for the 12-channel board running firmware LOCK_12_MODBUS_TIMED_20261006: both
 * protocols, pulse unlocking, continuous/timed outputs (verified by reading back MODBUS register 0004)
 * and cross-protocol timer rules.
 *
 * Order per board: online check → read-only items → pulse unlocking → outputs → cross-protocol timers.
 * Every exit path ends with [LockBoardClient.cleanup]. [requestStop] ends the run cooperatively with a
 * partial report; coroutine cancellation still runs the clean-up before propagating.
 */
class TestRunner(
    private val client: LockBoardClient,
    private val profiles: (Int) -> BoardProfile,
    plan: TestPlan,
    private val operator: Operator,
    private val appVersion: String,
    private val onProgress: (Progress) -> Unit = {},
    private val clock: Clock = SystemClock,
) {
    private val plan = plan.validated()

    @Volatile
    private var stopRequested = false

    private class StopRequested : Exception()

    fun requestStop() { stopRequested = true }

    private fun checkStop() { if (stopRequested) throw StopRequested() }

    /** Operator interaction never starts (or continues) after a stop request. */
    private suspend fun ask(question: String): Answer {
        checkStop()
        val a = operator.ask(question)
        checkStop()
        return a
    }

    private suspend fun request(instruction: String) {
        checkStop()
        operator.request(instruction)
        checkStop()
    }

    // ------------------------------------------------------------------------------------------
    // Preview

    fun preview(addresses: List<Int>): List<String> = buildList {
        val t = plan.timedSeconds
        for (a in addresses) {
            val p = profiles(a)
            val locks = p.channelsOf(ChannelType.LOCK)
            val outputs = p.channelsOf(ChannelType.OUTPUT)
            add("【${p.displayName()}】")
            if (locks.isNotEmpty()) {
                if (plan.unlockEach) add("· 私有 0x10 逐路开锁：第 ${locks.joinToString(",")} 路")
                if (plan.modbusUnlockEach) add("· MODBUS 05 逐路开锁：第 ${locks.joinToString(",")} 路（每路等待动作窗口）")
                if ((plan.unlockMany || plan.modbusUnlockMany) && locks.size >= 2) add("· 0x15 / MODBUS 0F 逐路开多个：奇数路、偶数路各一次")
                if ((plan.unlockSimultaneous || plan.modbusSimultaneous) && simultaneousTargets(p).isNotEmpty()) {
                    add("· 0x18 / MODBUS 0006 同时开：第 ${simultaneousTargets(p).joinToString(",")} 路同时动作，瞬时电流叠加")
                }
            }
            if (plan.unlockAll) {
                if (outputs.isNotEmpty() && !plan.allowPulseOnOutputs) add("· 0x14 开全部：已跳过（板上有灯/继电器通道，0x14 会脉冲全部 12 路）")
                else add("· 0x14 开全部：全部 12 路逐路各脉冲一次（4–17 s）")
            }
            if (plan.modbusExceptions && outputs.isNotEmpty()) {
                add("· M7 原子性探针：在所有输出关闭时写 0004=第 ${outputs[0]} 路 + 非法保留位；固件若错误地部分执行，该路会通电，随后立即关断并读回确认（需确认接线档案，否则用 0）")
            }
            if (outputs.isNotEmpty() && (plan.outputSingle || plan.outputMulti || plan.outputTimed || plan.modbusOutputs || plan.crossProtocolTimers)) {
                add("· 灯/继电器第 ${outputs.joinToString(",")} 路：持续输出开关、${t} s / ${2 * t} s 定时输出，结束后逐路关断并读回 0004 确认（需确认本次连接的接线档案）")
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Run

    suspend fun run(addresses: List<Int>): TestReport {
        val started = clock.wallMillis()
        val statsAtStart = client.stats.snapshot()
        val boards = ArrayList<BoardReport>()
        var cancelled = false
        var leftOn: List<OutputKey> = emptyList()
        try {
            for (address in addresses) {
                val items = ArrayList<ItemResult>()
                val versions = arrayOfNulls<Any>(2)
                try {
                    runBoard(address, items, versions)
                } catch (_: StopRequested) {
                    cancelled = true
                } finally {
                    @Suppress("UNCHECKED_CAST")
                    boards += BoardReport(address, profiles(address).displayName(), versions[0] as List<Int>?, versions[1] as Int?, items.toList())
                }
                if (cancelled) break
            }
        } finally {
            withContext(NonCancellable) {
                leftOn = client.cleanup().possiblyOn
                for (a in addresses) client.awaitPulseIdle(a)
            }
        }
        val s = client.settings
        return TestReport(
            appVersion = appVersion,
            startedAtMillis = started,
            finishedAtMillis = clock.wallMillis(),
            transport = "${client.transportKind.label}：${client.transportDescription}",
            physicalTransport = client.transportKind.physical,
            settingsSummary = "波特率 ${s.baud}，查询超时 ${s.readTimeoutMs} ms，单路动作 ${s.perLockPulseMaxMs} ms，同时脉冲 ${s.simultaneousPulseMs} ms，" +
                "动作余量 ${s.actionMarginMs} ms，读重试 ${s.readRetries}，帧间隔 ${s.interFrameGapMs} ms，迟到隔离 ${s.lateReplyGuardMs} ms，" +
                "迟到回复最长寿命 ${s.lateReplyMaxAgeMs} ms",
            plan = plan,
            boards = boards,
            possiblyEnergized = leftOn,
            cancelled = cancelled,
            commStats = (client.stats.snapshot() - statsAtStart).summary(),
        )
    }

    // ------------------------------------------------------------------------------------------
    // Item infrastructure

    private inner class Ctx(val address: Int, val id: String, val name: String) {
        val steps = ArrayList<StepRecord>()
        val metrics = LinkedHashMap<String, String>()

        fun step(text: String, verdict: Verdict, detail: String = "") {
            val s = StepRecord(text, verdict, detail)
            steps += s
            onProgress(Progress.Step(address, id, s))
        }

        fun result(summary: String) = ItemResult(id, name, Verdict.aggregate(steps.map { it.verdict }), summary, steps.toList(), metrics.toMap())
    }

    private suspend fun item(out: MutableList<ItemResult>, address: Int, id: String, name: String, block: suspend Ctx.() -> String) {
        checkStop()
        onProgress(Progress.ItemStarted(address, id, name))
        val ctx = Ctx(address, id, name)
        var stop: StopRequested? = null
        val result = try {
            ctx.result(ctx.block())
        } catch (e: StopRequested) {
            stop = e
            ctx.step("操作者中止测试", Verdict.UNVERIFIED)
            ctx.result("已中止")
        }
        out += result
        onProgress(Progress.ItemFinished(address, result))
        if (stop != null) throw stop
    }

    private fun skip(out: MutableList<ItemResult>, address: Int, id: String, name: String, reason: String) {
        val r = ItemResult(id, name, Verdict.SKIP, reason)
        out += r
        onProgress(Progress.ItemFinished(address, r))
    }

    private suspend fun runBoard(address: Int, out: MutableList<ItemResult>, versions: Array<Any?>) {
        val p = profiles(address)
        onProgress(Progress.Message("开始测试 ${p.displayName()}"))

        // T0: both protocols must report compatible versions (>= baseline 02 / 0002, reserved 00) and agree.
        val pv = client.execute(Command.GetVersion(address))
        val mv = client.executeModbus(ModbusRequest.ReadHoldingRegisters(address, Modbus.REG_VERSION, 1))
        val pvr = pv.confirmedReply()
        val mvr = (mv.confirmedReply() as? ModbusReply.Registers)?.value(Modbus.REG_VERSION)
        versions[0] = pvr?.bytes
        versions[1] = mvr
        val privOk = pvr != null && pvr.version >= VersionReply.MIN_VERSION && pvr.reserved == 0
        val privDetail = when {
            !privOk -> "要求 ≥ 02 00 且预留字节为 00"
            pvr!!.version > VersionReply.MIN_VERSION -> "高于文档基准 02 00，按新固件兼容"
            else -> "文档基准 ≥ 02 00"
        }
        val mbOk = mvr != null && mvr >= Modbus.MIN_VERSION
        val mbDetail = when {
            !mbOk -> "要求 ≥ 0002"
            mvr!! > Modbus.MIN_VERSION -> "高于文档基准 0002，按新固件兼容"
            else -> "文档基准 ≥ 0002"
        }
        val t0Steps = buildList {
            add(StepRecord("私有 0x01：${pv.describe()}", if (privOk) Verdict.PASS else Verdict.FAIL, privDetail))
            add(StepRecord("MODBUS 03 读 0001：${mv.describe()}", if (mbOk) Verdict.PASS else Verdict.FAIL, mbDetail))
            if (pvr != null && mvr != null) {
                add(
                    if (pvr.version == mvr) StepRecord("双协议版本一致：私有 ${pvr.version.hex2()} == MODBUS ${"%04X".format(mvr)}", Verdict.PASS)
                    else StepRecord("双协议版本不一致：私有 ${pvr.bytes.toHex()}，MODBUS ${"%04X".format(mvr)}", Verdict.FAIL, "同一双协议固件版本应一致"),
                )
            }
        }
        val t0 = ItemResult("T0", "在线检查（双协议版本）", Verdict.aggregate(t0Steps.map { it.verdict }), "私有 ${pvr?.bytes?.toHex() ?: "无"}，MODBUS ${mvr?.let { "%04X".format(it) } ?: "无"}", t0Steps)
        out += t0
        onProgress(Progress.ItemFinished(address, t0))
        if (t0.verdict == Verdict.FAIL) return

        val boardVersion = pvr!!.version
        if (plan.communication) item(out, address, "T1", "双协议通信（0x01 / 03 读 0001，各 ${plan.communicationCount} 次）") { communication(boardVersion) }
        if (plan.consistency) item(out, address, "T2", "反馈一致性（0x12 / 0x11×12 / 02 / 03）") { consistency() }
        if (plan.robustness) item(out, address, "T9", "私有异常帧容错（只读 0x01 诊断帧）") { robustness() }
        if (plan.modbusExceptions) item(out, address, "M7", "MODBUS 异常响应") { modbusExceptions(p) }
        if (plan.stress) item(out, address, "T10", "双协议交替压力（0x12 / 02，共 ${plan.stressCount} 次）") { stress() }

        val locks = p.channelsOf(ChannelType.LOCK)
        val outputs = p.channelsOf(ChannelType.OUTPUT)
        suspend fun needLocks(id: String, name: String, min: Int, block: suspend Ctx.() -> String) {
            if (locks.size < min) skip(out, address, id, name, "电控锁通道少于 $min 路") else item(out, address, id, name, block)
        }
        if (plan.unlockEach) needLocks("T3", "私有 0x10 逐路开锁", 1) { unlockEachPrivate(p, locks) }
        if (plan.modbusUnlockEach) needLocks("M3", "MODBUS 05 逐路开锁", 1) { unlockEachModbus(p, locks) }
        if (plan.unlockMany) needLocks("T4", "私有 0x15 逐路开多个", 2) { unlockManyPrivate(p, locks) }
        if (plan.modbusUnlockMany) needLocks("M4", "MODBUS 0F 逐路开多个", 2) { unlockManyModbus(p, locks) }
        val sim = simultaneousTargets(p)
        if (plan.unlockSimultaneous) {
            if (sim.isEmpty()) skip(out, address, "T5", "私有 0x18 同时开", "没有可用的电控锁通道")
            else item(out, address, "T5", "私有 0x18 同时开") { simultaneousPrivate(p, sim) }
        }
        if (plan.modbusSimultaneous) {
            if (sim.isEmpty()) skip(out, address, "M5", "MODBUS 06 写 0006 同时开", "没有可用的电控锁通道")
            else item(out, address, "M5", "MODBUS 06 写 0006 同时开") { simultaneousModbus(p, sim) }
        }
        if (plan.unlockAll) {
            if (outputs.isNotEmpty() && !plan.allowPulseOnOutputs) {
                skip(out, address, "T6", "私有 0x14 开全部", "板上有灯/继电器通道（${outputs.joinToString(",")}），0x14 会脉冲全部 12 路；如确认无害请在测试设置中允许")
            } else item(out, address, "T6", "私有 0x14 开全部") { unlockAllPrivate(p) }
        }

        val armed = client.safety.isArmed(address)
        suspend fun needOutputs(id: String, name: String, block: suspend Ctx.() -> String) {
            when {
                outputs.isEmpty() -> skip(out, address, id, name, "没有配置为灯/继电器的通道")
                !armed -> skip(out, address, id, name, "本次连接尚未确认该板接线档案")
                else -> item(out, address, id, name, block)
            }
        }
        if (plan.outputSingle) needOutputs("T7", "私有 0x16 持续输出（读回 0004）") { outputSingle(outputs) }
        if (plan.outputMulti) needOutputs("T8", "私有 0x1A 整体输出（读回 0004）") { outputMulti(outputs) }
        if (plan.outputTimed) needOutputs("T11", "私有 0x16 定时输出（读回 0004）") { outputTimed(outputs[0]) }
        if (plan.modbusOutputs) needOutputs("M6", "MODBUS 输出控制（06/10 写 0004、10 写 0008）") { modbusOutputs(outputs) }
        if (plan.crossProtocolTimers) {
            needOutputs("X1", "跨协议定时：重新定时与独立到期") { crossRetime(outputs) }
            needOutputs("X2", "跨协议定时：0x1A 取消 MODBUS 定时") { crossCancel(outputs[0]) }
        }
    }

    private companion object {
        const val POLL_MS = 100L
    }

    private fun simultaneousTargets(p: BoardProfile): List<Int> {
        val locks = p.channelsOf(ChannelType.LOCK)
        return if (plan.simultaneousChannels.isNotEmpty()) plan.simultaneousChannels.filter { it in locks } else locks.take(3)
    }

    // ------------------------------------------------------------------------------------------
    // Read-only items

    private class Tally {
        var firstOk = 0
        var recovered = 0
        var failed = 0
        val latencies = ArrayList<Long>()
        val errors = LinkedHashMap<String, Int>()

        /** Counts [r]; only a confirmed reply that satisfies [expected] is a success (MODBUS exceptions are failures). */
        fun add(r: CommResult<*>, expected: (Any) -> Boolean) {
            when {
                r is CommResult.Ok && r.confirmed && expected(r.reply) -> {
                    if (r.attempts == 1) firstOk++ else recovered++
                    latencies += r.latencyMs
                }
                r is CommResult.Ok && r.reply is ModbusReply.Exception -> { failed++; errors.merge("MODBUS 异常 ${(r.reply as ModbusReply.Exception).code.hex2()}", 1, Int::plus) }
                r is CommResult.Ok && r.confirmed -> { failed++; errors.merge("回复内容不符", 1, Int::plus) }
                r is CommResult.Ok -> { failed++; errors.merge("回复归属不明", 1, Int::plus) }
                r is CommResult.Failed -> { failed++; errors.merge(r.error.label, 1, Int::plus) }
            }
        }

        val total get() = firstOk + recovered + failed

        fun latencyText(): String {
            if (latencies.isEmpty()) return "无"
            val sorted = latencies.sorted()
            val p95 = sorted[(ceil(sorted.size * 0.95).toInt() - 1).coerceIn(0, sorted.size - 1)]
            return "最小 ${sorted.first()} / 平均 ${sorted.average().toLong()} / P95 $p95 / 最大 ${sorted.last()} ms"
        }

        fun fill(metrics: MutableMap<String, String>, prefix: String) {
            metrics["$prefix 首发成功"] = "$firstOk / $total"
            metrics["$prefix 重试后成功"] = "$recovered / $total"
            metrics["$prefix 最终失败"] = "$failed / $total"
            metrics["$prefix 耗时"] = latencyText()
            if (errors.isNotEmpty()) metrics["$prefix 错误分类"] = errors.entries.joinToString("，") { "${it.key} ${it.value}" }
        }

        fun verdict(min: Double): Verdict {
            if (total == 0) return Verdict.SKIP
            val first = firstOk.toDouble() / total
            val final = (firstOk + recovered).toDouble() / total
            return when {
                first >= min -> Verdict.PASS
                final >= min -> Verdict.WARN
                else -> Verdict.FAIL
            }
        }
    }

    private suspend fun Ctx.communication(expectedVersion: Int): String {
        val priv = Tally()
        val mb = Tally()
        repeat(plan.communicationCount) {
            checkStop()
            priv.add(client.execute(Command.GetVersion(address))) { it is VersionReply && it.version == expectedVersion && it.reserved == 0 }
            mb.add(client.executeModbus(ModbusRequest.ReadHoldingRegisters(address, Modbus.REG_VERSION, 1))) {
                (it as? ModbusReply.Registers)?.value(Modbus.REG_VERSION) == expectedVersion
            }
        }
        priv.fill(metrics, "私有")
        mb.fill(metrics, "MODBUS")
        step("私有 0x01：首发 ${priv.firstOk}/${priv.total}，失败 ${priv.failed}", priv.verdict(plan.minSuccessRate), priv.latencyText())
        step("MODBUS 03：首发 ${mb.firstOk}/${mb.total}，失败 ${mb.failed}", mb.verdict(plan.minSuccessRate), mb.latencyText())
        return "私有 ${priv.firstOk}/${priv.total}，MODBUS ${mb.firstOk}/${mb.total}"
    }

    private suspend fun readMaskPrivate(address: Int): Int? = client.execute(Command.ReadAll(address)).confirmedReply()?.mask

    private suspend fun readMaskModbus(address: Int): Int? =
        (client.executeModbus(ModbusRequest.ReadDiscreteInputs(address, 1, 12)).confirmedReply() as? ModbusReply.Bits)?.lockMask

    private suspend fun Ctx.consistency(): String {
        val first = readMaskPrivate(address)
        if (first == null) {
            step("私有 0x12（第一次）读取失败", Verdict.FAIL)
            return "无法读取反馈"
        }
        var failedReads = 0
        val mismatches = ArrayList<String>()
        for (ch in 1..Protocol.CHANNELS) {
            checkStop()
            val r = client.execute(Command.ReadOne(address, ch))
            val reply = r.confirmedReply()
            when {
                reply == null -> { failedReads++; step("私有 0x11 读第 $ch 路：${r.describe()}", Verdict.FAIL) }
                reply.state != LockMask.isSet(first, ch) -> mismatches += "0x11 第 $ch 路=${if (reply.state) 1 else 0}"
            }
        }
        val m02 = client.executeModbus(ModbusRequest.ReadDiscreteInputs(address, 1, 12))
        val bits = (m02.confirmedReply() as? ModbusReply.Bits)?.lockMask
        if (bits == null) { failedReads++; step("MODBUS 02：${m02.describe()}", Verdict.FAIL) } else if (bits != first) mismatches += "02=${"%03X".format(bits)}"
        val m03 = client.executeModbus(ModbusRequest.ReadHoldingRegisters(address, Modbus.REG_FEEDBACK_LO, 2))
        val regs = m03.confirmedReply() as? ModbusReply.Registers
        val lo = regs?.value(Modbus.REG_FEEDBACK_LO)
        if (regs == null || lo == null) { failedReads++; step("MODBUS 03 读 0002–0003：${m03.describe()}", Verdict.FAIL) } else {
            if (lo != first) mismatches += "03(0002)=${"%04X".format(lo)}"
            val hi = regs.value(Modbus.REG_FEEDBACK_HI)
            if (hi != 0) step("0003 反馈高半字应固定为 0000，读到 ${hi?.let { "%04X".format(it) }}", Verdict.FAIL)
        }
        val second = readMaskPrivate(address)
        metrics["0x12 快照"] = LockMask.describe(first)
        if (second == null || second != first) {
            step("前后两次 0x12 快照不同（${LockMask.describe(first)} → ${second?.let { LockMask.describe(it) } ?: "读取失败"}），期间状态变化，条件不满足", Verdict.UNVERIFIED)
            return "状态在读取期间变化，无法比对"
        }
        if (mismatches.isEmpty() && failedReads == 0) {
            step("0x12、12 路 0x11、MODBUS 02、03(0002) 一致（打开：${LockMask.describe(first)}）", Verdict.PASS)
            return "四种读法一致"
        }
        if (mismatches.isNotEmpty()) step("与 0x12（${"%03X".format(first)}）不一致：${mismatches.joinToString("；")}", Verdict.FAIL)
        return "不一致 ${mismatches.size} 处，读取失败 $failedReads 次"
    }

    private suspend fun Ctx.robustness(): String {
        val s = client.settings
        val listen = s.readTimeoutMs * 2
        // a) Half frame first, while the 0x01 reply key is clean: measures the board's ~100 ms partial-frame
        //    clean-up without any reply-quarantine wait in between.
        val half = client.sendProbe(address, DiagnosticProbe.TRUNCATED, s.readTimeoutMs)
        half.rejected?.let { step("${DiagnosticProbe.TRUNCATED.label}：发送失败（$it）", Verdict.FAIL); return "发送失败" }
        val halfMatched = (half.received + half.badFrames).filter { it.address == address && it.command == Cmd.GET_VERSION }
        if (halfMatched.isNotEmpty()) step("半帧本身得到了回复：${halfMatched.joinToString { it.describe() }}", Verdict.FAIL)
        delay(300)
        val r1 = client.execute(Command.GetVersion(address), ExecOptions(readRetries = 0, requireClean = true))
        val r1Written = when (r1) { is CommResult.Ok -> r1.writtenAtNanos; is CommResult.Failed -> r1.writtenAtNanos }
        val wait = if (r1Written != null && half.writtenAtNanos != null) "两次写出间隔 ${(r1Written - half.writtenAtNanos) / 1_000_000} ms" else "合法查询未写出"
        fun what(r: CommResult<*>) = if (r is CommResult.Ok) "回复归属不明" else "无回复"
        if (r1.confirmedReply() != null) {
            step("${DiagnosticProbe.TRUNCATED.label}（$wait）后，首个合法查询有回复", Verdict.PASS)
        } else {
            val r2 = client.execute(Command.GetVersion(address), ExecOptions(readRetries = 0, requireClean = true))
            if (r2.confirmedReply() != null) step("半帧后首个合法查询${what(r1)}（$wait），第二个恢复", Verdict.WARN, r1.describe())
            else step("半帧后连续两个合法查询：${what(r1)}、${what(r2)}（$wait）", Verdict.FAIL, r2.describe())
        }
        // b) Bad checksum: must not be answered.
        val bad = client.sendProbe(address, DiagnosticProbe.BAD_CHECKSUM, listen)
        bad.rejected?.let { step("${DiagnosticProbe.BAD_CHECKSUM.label}：发送失败（$it）", Verdict.FAIL); return "发送失败" }
        val answered = (bad.received + bad.badFrames).any { it.address == address && it.command == Cmd.GET_VERSION }
        step(
            "${DiagnosticProbe.BAD_CHECKSUM.label}（监听 ${bad.listenedMs} ms）：" + if (answered) "板子作出了回复" else "无回复",
            if (answered) Verdict.FAIL else Verdict.PASS,
            if (answered) "文档：校验错误时不回复" else "",
        )
        // c) Leading noise. MODBUS manual §2: a frame whose first byte is not DC goes to MODBUS and is dropped on a CRC error.
        val noisy = client.sendProbe(address, DiagnosticProbe.NOISE_PREFIX, listen)
        noisy.rejected?.let { step("${DiagnosticProbe.NOISE_PREFIX.label}：发送失败（$it）", Verdict.FAIL); return "发送失败" }
        val answeredNoisy = noisy.received.any { it.address == address && it.command == Cmd.GET_VERSION }
        step("${DiagnosticProbe.NOISE_PREFIX.label}（监听 ${noisy.listenedMs} ms）：" + if (answeredNoisy) "有回复" else "无回复",
            if (answeredNoisy) Verdict.WARN else Verdict.PASS,
            if (answeredNoisy) "与文档不同：首字节非 DC 的帧应按 MODBUS 处理并因 CRC 错误丢弃" else "符合文档：首字节非 DC 按 MODBUS 处理，CRC 错误丢弃")
        // d) Unsupported private command: no reply.
        val unsupported = client.execute(Command.Custom(address, 0x13, emptyList()), ExecOptions(timeoutMs = s.readTimeoutMs))
        step("不支持的私有命令 0x13：" + if (unsupported is CommResult.Ok) "有回复" else "无回复", if (unsupported is CommResult.Ok) Verdict.FAIL else Verdict.PASS, "文档：未列出的命令不回复")
        // e) Recovery: a valid read-version command after the malformed inputs.
        val after = client.execute(Command.GetVersion(address), ExecOptions(requireClean = true))
        step("异常输入后的恢复查询：${after.describe()}", if (after.confirmedReply() != null) Verdict.PASS else Verdict.FAIL)
        return "完成 4 种异常输入"
    }

    private suspend fun readOutputs(address: Int): Int? {
        val r = client.readOutputs(address)
        val regs = r.confirmedReply() as? ModbusReply.Registers ?: return null
        if (regs.value(Modbus.REG_OUTPUT_HI) != 0) return null
        return regs.value(Modbus.REG_OUTPUT_LO)
    }

    private suspend fun Ctx.modbusExceptions(p: BoardProfile): String {
        var pass = 0
        val probes = ModbusProbe.entries.filter { it != ModbusProbe.ATOMIC_RESERVED }
        for (probe in probes) {
            checkStop()
            val r = client.sendModbusProbe(address, probe)
            val exc = r.confirmedReply() as? ModbusReply.Exception
            when {
                r is CommResult.Failed && r.writtenAtNanos == null -> step("${probe.label}：未发送（${r.message}），无法验证", Verdict.UNVERIFIED)
                exc == null -> step("${probe.label}：${r.describe()}，期望异常 ${probe.expectedException.hex2()}", Verdict.FAIL)
                exc.code != probe.expectedException -> step("${probe.label}：异常 ${exc.code.hex2()}，期望 ${probe.expectedException.hex2()}", Verdict.FAIL)
                else -> { pass++; step("${probe.label}：异常 ${exc.code.hex2()}", Verdict.PASS) }
            }
        }
        // Atomicity: legal 0004 low word + illegal reserved 0005 → whole write rejected, 0004 unchanged.
        // Only with every output off (a wrongly applied 0005 write would cancel running timers); an armed lamp
        // channel as low word makes a partial application observable.
        val before = readOutputs(address)
        val lamp = p.channelsOf(ChannelType.OUTPUT).firstOrNull()?.takeIf { client.safety.isArmed(address) }
        when {
            before == null -> step("原子性：读回 0004 失败，跳过", Verdict.UNVERIFIED)
            before != 0 -> step("原子性：板上有输出开启（0004=${"%03X".format(before)}），为避免取消定时，跳过写探针", Verdict.UNVERIFIED)
            else -> {
                val low = lamp?.let { LockMask.bit(it) } ?: 0
                val r = client.sendModbusProbe(address, ModbusProbe.ATOMIC_RESERVED, low)
                val exc = r.confirmedReply() as? ModbusReply.Exception
                val notSent = r is CommResult.Failed && r.writtenAtNanos == null
                val after = if (notSent) null else readOutputs(address)
                when {
                    notSent -> step("原子性探针未发送（${(r as CommResult.Failed).message}），无法验证", Verdict.UNVERIFIED)
                    exc?.code != 3 -> step("${ModbusProbe.ATOMIC_RESERVED.label}（低字 ${"%03X".format(low)}）：${r.describe()}，期望异常 03", Verdict.FAIL)
                    after == null -> step("原子性：异常 03 正确，但随后读回 0004 失败，无法验证是否部分执行", Verdict.UNVERIFIED)
                    after != 0 -> step("原子性：异常后 0004 变为 ${"%03X".format(after)}，低字被部分执行", Verdict.FAIL)
                    lamp == null -> { pass++; step("原子性：异常 03，0004 保持 000（无已确认的灯通道，低字为 0，无法观察部分执行）", Verdict.UNVERIFIED) }
                    else -> { pass++; step("原子性：整笔写入被拒，第 $lamp 路未被置位，0004 保持 000", Verdict.PASS) }
                }
                if (lamp != null) withContext(NonCancellable) { client.cleanup() }
            }
        }
        return "$pass / ${probes.size + 1} 项符合文档"
    }

    private suspend fun Ctx.stress(): String {
        val priv = Tally()
        val mb = Tally()
        repeat(plan.stressCount) { i ->
            checkStop()
            if (i % 2 == 0) priv.add(client.execute(Command.ReadAll(address))) { it is MaskReply }
            else mb.add(client.executeModbus(ModbusRequest.ReadDiscreteInputs(address, 1, 12))) { it is ModbusReply.Bits }
        }
        priv.fill(metrics, "私有 0x12")
        mb.fill(metrics, "MODBUS 02")
        step("私有 0x12：首发 ${priv.firstOk}/${priv.total}，失败 ${priv.failed}", priv.verdict(plan.stressMinSuccessRate), priv.latencyText())
        step("MODBUS 02：首发 ${mb.firstOk}/${mb.total}，失败 ${mb.failed}", mb.verdict(plan.stressMinSuccessRate), mb.latencyText())
        return "私有 ${priv.firstOk}/${priv.total}，MODBUS ${mb.firstOk}/${mb.total}"
    }

    // ------------------------------------------------------------------------------------------
    // Pulse unlocking

    /** Waits (and in manual mode asks) until [channels] read closed; returns the ones still open. */
    private suspend fun Ctx.awaitClosed(channels: List<Int>): Set<Int> {
        if (channels.isEmpty()) return emptySet()
        var asked = false
        var deadline = clock.nanoTime() + plan.waitClosedMs * 1_000_000L
        while (true) {
            checkStop()
            val mask = readMaskPrivate(address) ?: return channels.toSet()
            val open = channels.filter { LockMask.isSet(mask, it) }.toSet()
            if (open.isEmpty()) return open
            if (plan.manualConfirm && !asked) {
                asked = true
                request("请关闭地址 $address 第 ${open.sorted().joinToString(",")} 路的柜门（反馈显示打开），然后继续")
                deadline = clock.nanoTime() + plan.waitClosedMs * 1_000_000L
                continue
            }
            if (clock.nanoTime() >= deadline) return open
            delay(200)
        }
    }

    /**
     * Judges the physical effect of pulses on [targets] from independent feedback reads before/after,
     * the board's own result (private 0x10 / mask replies) and, optionally, the operator.
     */
    private suspend fun Ctx.judgeUnlock(
        p: BoardProfile,
        targets: List<Int>,
        before: Int?,
        reportedOpen: Set<Int>,
        after: Int?,
        actionLabel: String,
        pulsed: List<Int> = targets,
    ) {
        val feedbackTargets = if (plan.requireFeedback) targets.filter { it in p.feedbackLocks() } else emptyList()
        val unverified = targets.filter { it !in feedbackTargets }.toMutableList()
        for (ch in feedbackTargets) {
            when {
                before == null || after == null -> { step("第 $ch 路：无法读取动作前后反馈", Verdict.UNVERIFIED); unverified += ch }
                LockMask.isSet(before, ch) -> { step("第 $ch 路：动作前已为打开，无法验证本次动作", Verdict.UNVERIFIED); unverified += ch }
                LockMask.isSet(after, ch) -> step("第 $ch 路：反馈由关变开", Verdict.PASS)
                ch in reportedOpen -> { step("第 $ch 路：板报打开，但独立读回为关闭，反馈证据不足", Verdict.UNVERIFIED, "门未弹开时反馈会立即回落"); unverified += ch }
                else -> step("第 $ch 路：${actionLabel}后反馈仍为关闭", Verdict.FAIL)
            }
        }
        if (before != null && after != null) {
            val newlyOpen = LockMask.channels((after or LockMask.of(reportedOpen)) and before.inv() and LockMask.of(pulsed).inv())
            val suspicious = newlyOpen.filter { val c = p.channel(it); c.type != ChannelType.UNUSED && (c.type != ChannelType.LOCK || c.feedback) }
            if (suspicious.isNotEmpty()) step("非目标通道 ${suspicious.joinToString(",")} 由关变开（疑似接线或驱动错误，也可能有人手动开门）", Verdict.FAIL)
        }
        if (plan.manualConfirm) {
            val text = targets.joinToString(",")
            when (ask("地址 $address 第 $text 路锁是否都已弹开？")) {
                Answer.YES -> step("操作者确认第 $text 路已弹开", Verdict.PASS)
                Answer.NO -> step("操作者确认第 $text 路未全部弹开", Verdict.FAIL)
                Answer.SKIP -> if (unverified.isNotEmpty()) step("第 ${unverified.joinToString(",")} 路：物理动作未验证（操作者无法观察）", Verdict.UNVERIFIED)
            }
        } else if (unverified.isNotEmpty()) {
            step("第 ${unverified.joinToString(",")} 路：通信通过，物理动作未验证（无反馈或未要求反馈，且未开启人工确认）", Verdict.UNVERIFIED)
        }
    }

    private suspend fun Ctx.unlockEachPrivate(p: BoardProfile, locks: List<Int>): String {
        var fails = 0
        for ((i, ch) in locks.withIndex()) {
            checkStop()
            if (ch in p.feedbackLocks() && plan.requireFeedback) awaitClosed(listOf(ch))
            val before = readMaskPrivate(address)
            val r = client.execute(Command.UnlockOne(address, ch))
            val reported = HashSet<Int>()
            val reply = r.confirmedReply()
            when {
                reply == null -> { step("0x10 开第 $ch 路：${r.describe()}", Verdict.FAIL); fails++ }
                reply.state -> { reported += ch; step("0x10 开第 $ch 路：板报 01 打开成功（${(r as CommResult.Ok).latencyMs} ms）", Verdict.PASS) }
                else -> {
                    val fb = ch in p.feedbackLocks() && plan.requireFeedback
                    step("0x10 开第 $ch 路：板报 00（重试后仍未打开）", if (fb) Verdict.FAIL else Verdict.WARN, "未接反馈线的锁也会报 00")
                    if (fb) fails++
                }
            }
            (r as? CommResult.Ok)?.let { metrics["0x10 第 $ch 路耗时"] = "${it.latencyMs} ms" }
            if (r is CommResult.Ok) {
                delay(plan.settleMs.toLong())
                judgeUnlock(p, listOf(ch), before, reported, readMaskPrivate(address), "0x10 开锁")
            }
            if (i < locks.lastIndex) delay(plan.unlockIntervalMs.toLong())
        }
        return if (fails == 0) "${locks.size} 路均有正确回复" else "$fails 路异常"
    }

    /** MODBUS pulses reply immediately; the action runs in the background until its window ends. */
    private suspend fun Ctx.modbusPulse(p: BoardProfile, targets: List<Int>, request: ModbusRequest, label: String) {
        checkStop()
        awaitClosed(targets.filter { it in p.feedbackLocks() && plan.requireFeedback })
        client.awaitPulseIdle(address)
        val before = readMaskModbus(address)
        val r = client.executeModbus(request)
        if (r.confirmedReply() !is ModbusReply.WriteAck) {
            step("$label：${r.describe()}", Verdict.FAIL)
            if (r !is CommResult.Ok) return
        } else {
            step("$label：已受理（${(r as CommResult.Ok).latencyMs} ms），动作在后台进行", Verdict.PASS)
        }
        // MODBUS gives no completion signal: poll the feedback (queries are allowed during a pulse task)
        // until the action window ends, and judge on what was seen open at any time.
        var seen: Int? = null
        var samples = 0
        while (clock.nanoTime() < client.pulseFreeAt(address)) {
            checkStop()
            readMaskModbus(address)?.let { seen = (seen ?: 0) or it; samples++ }
            delay(POLL_MS)
        }
        delay(plan.settleMs.toLong())
        readMaskModbus(address)?.let { seen = (seen ?: 0) or it; samples++ }
        metrics["$label 反馈采样"] = "$samples 次"
        judgeUnlock(p, targets, before, emptySet(), seen, label)
    }

    private suspend fun Ctx.unlockEachModbus(p: BoardProfile, locks: List<Int>): String {
        for (ch in locks) modbusPulse(p, listOf(ch), ModbusRequest.WriteSingleCoil(address, ch), "05 开第 $ch 路")
        return "${locks.size} 路"
    }

    private suspend fun Ctx.maskActionPrivate(p: BoardProfile, targets: List<Int>, title: String, command: Command<MaskReply>, pulsed: List<Int> = targets) {
        checkStop()
        awaitClosed(targets.filter { it in p.feedbackLocks() && plan.requireFeedback })
        val before = readMaskPrivate(address)
        val r = client.execute(command)
        val mask = r.confirmedReply()?.mask
        step("$title：${r.describe()}", if (mask == null) Verdict.FAIL else Verdict.PASS)
        if (r !is CommResult.Ok) return
        delay(plan.settleMs.toLong())
        judgeUnlock(p, targets, before, mask?.let { LockMask.channels(it).toSet() } ?: emptySet(), readMaskPrivate(address), title, pulsed)
    }

    private fun groups(locks: List<Int>) = locks.filterIndexed { i, _ -> i % 2 == 0 } to locks.filterIndexed { i, _ -> i % 2 == 1 }

    private suspend fun Ctx.unlockManyPrivate(p: BoardProfile, locks: List<Int>): String {
        val (odd, even) = groups(locks)
        maskActionPrivate(p, odd, "0x15 开第 ${odd.joinToString(",")} 路", Command.UnlockMany(address, LockMask.of(odd)))
        delay(plan.unlockIntervalMs.toLong())
        maskActionPrivate(p, even, "0x15 开第 ${even.joinToString(",")} 路", Command.UnlockMany(address, LockMask.of(even)))
        return "两组各一次（逐路顺序无法由反馈判断，只核对最终反馈）"
    }

    private suspend fun Ctx.unlockManyModbus(p: BoardProfile, locks: List<Int>): String {
        val (odd, even) = groups(locks)
        modbusPulse(p, odd, ModbusRequest.WriteMultipleCoils(address, 1, 12, LockMask.of(odd)), "0F 开第 ${odd.joinToString(",")} 路")
        modbusPulse(p, even, ModbusRequest.WriteMultipleCoils(address, 1, 12, LockMask.of(even)), "0F 开第 ${even.joinToString(",")} 路")
        return "两组各一次（逐路顺序无法由反馈判断，只核对最终反馈）"
    }

    private suspend fun Ctx.simultaneity(targets: List<Int>) {
        if (plan.manualConfirm) {
            when (ask("第 ${targets.joinToString(",")} 路是否同时弹开（而非依次）？")) {
                Answer.YES -> step("操作者确认同时动作", Verdict.PASS)
                Answer.NO -> step("操作者观察到不是同时动作", Verdict.FAIL)
                Answer.SKIP -> step("同时性未验证", Verdict.UNVERIFIED)
            }
        } else step("同时性无法由反馈判断，未验证（可开启人工确认）", Verdict.UNVERIFIED)
    }

    private suspend fun Ctx.simultaneousPrivate(p: BoardProfile, targets: List<Int>): String {
        maskActionPrivate(p, targets, "0x18 同时开第 ${targets.joinToString(",")} 路", Command.UnlockSimultaneous(address, LockMask.of(targets)))
        simultaneity(targets)
        return "第 ${targets.joinToString(",")} 路"
    }

    private suspend fun Ctx.simultaneousModbus(p: BoardProfile, targets: List<Int>): String {
        modbusPulse(p, targets, ModbusRequest.WriteSingleRegister(address, Modbus.REG_PULSE_LO, LockMask.of(targets)), "06 写 0006 同时开第 ${targets.joinToString(",")} 路")
        simultaneity(targets)
        return "第 ${targets.joinToString(",")} 路"
    }

    private suspend fun Ctx.unlockAllPrivate(p: BoardProfile): String {
        val locks = p.channelsOf(ChannelType.LOCK)
        maskActionPrivate(p, locks, "0x14 开全部", Command.UnlockAll(address), pulsed = (1..Protocol.CHANNELS).toList())
        return "全部 12 路逐路脉冲，按 ${locks.size} 路电控锁判定"
    }

    // ------------------------------------------------------------------------------------------
    // Outputs (lamp/relay channels; control word verified by trusted read-backs of 0004)

    private suspend fun Ctx.observe(question: String, yes: String, no: String) {
        if (!plan.manualConfirm) return
        when (ask(question)) {
            Answer.YES -> step(yes, Verdict.PASS)
            Answer.NO -> step(no, Verdict.FAIL)
            Answer.SKIP -> step("负载状态未验证（操作者无法观察）", Verdict.UNVERIFIED)
        }
    }

    private fun Ctx.checkAck(r: CommResult<*>, title: String): Boolean {
        val ok = r is CommResult.Ok && r.confirmed && (r.reply is AckReply || r.reply is ModbusReply.WriteAck)
        step("$title：${r.describe()}", if (ok) Verdict.PASS else if (r is CommResult.Ok && !r.confirmed) Verdict.UNVERIFIED else Verdict.FAIL)
        return ok
    }

    /** Reads back 0004 and checks the bits of [relevant] against [expected]. */
    private suspend fun Ctx.expectOutputs(label: String, expected: Int, relevant: Int): Boolean {
        val v = readOutputs(address)
        if (v == null) {
            step("$label：读回 0004 失败或归属不明", Verdict.FAIL)
            return false
        }
        val ok = v and relevant == expected and relevant
        step("$label：读回 0004=${"%03X".format(v)}，期望通电 [${LockMask.describe(expected and relevant)}]", if (ok) Verdict.PASS else Verdict.FAIL)
        return ok
    }

    /** Waits in short slices so a stop request ends the item at once (its finally block switches off). */
    private suspend fun waitUntilNanos(t: Long) {
        while (true) {
            checkStop()
            val ms = (t - clock.nanoTime()) / 1_000_000
            if (ms <= 0) return
            delay(minOf(ms, 200))
        }
    }

    private suspend fun Ctx.finishOutputs() {
        val res = withContext(NonCancellable) { client.cleanup() }
        val left = res.possiblyOn.filter { it.address == address }
        if (left.isEmpty()) step("收尾：本项开启的输出已关断，并经读回 0004 确认", Verdict.PASS)
        else step("收尾：${left.joinToString()} 无法经读回确认已关断，可能仍带电，请现场核实", Verdict.FAIL)
    }

    private suspend fun Ctx.outputSingle(outputs: List<Int>): String {
        val all = LockMask.of(outputs)
        try {
            for (ch in outputs) {
                checkStop()
                if (checkAck(client.execute(Command.SetOutput(address, ch, true)), "0x16 第 $ch 路持续开")) {
                    expectOutputs("开后", LockMask.bit(ch), all)
                    observe("地址 $address 第 $ch 路负载是否已通电？", "操作者确认第 $ch 路已通电", "操作者确认第 $ch 路未通电")
                }
                if (checkAck(client.execute(Command.SetOutput(address, ch, false)), "0x16 第 $ch 路关")) {
                    expectOutputs("关后", 0, all)
                    observe("地址 $address 第 $ch 路负载是否已断电？", "操作者确认第 $ch 路已断电", "操作者确认第 $ch 路未断电")
                }
            }
        } finally {
            finishOutputs()
        }
        return "${outputs.size} 路"
    }

    private suspend fun Ctx.outputMulti(outputs: List<Int>): String {
        val all = LockMask.of(outputs)
        try {
            if (checkAck(client.execute(Command.SetOutputs(address, all)), "0x1A 开第 ${outputs.joinToString(",")} 路")) {
                expectOutputs("全开后", all, Protocol.ALL_CHANNELS_MASK)
            }
            val keep = LockMask.bit(outputs[0])
            if (checkAck(client.execute(Command.SetOutputs(address, keep)), "0x1A 只保留第 ${outputs[0]} 路")) {
                // Document §4.9: bits 0 withdraw the continuous output.
                expectOutputs("0 位撤销持续输出（文档 4.9）", keep, Protocol.ALL_CHANNELS_MASK)
            }
            if (checkAck(client.execute(Command.SetOutputs(address, 0)), "0x1A 全 0")) expectOutputs("全关后", 0, Protocol.ALL_CHANNELS_MASK)
        } finally {
            finishOutputs()
        }
        return "${outputs.size} 路"
    }

    private suspend fun Ctx.outputTimed(ch: Int): String {
        val t = plan.timedSeconds
        val bit = LockMask.bit(ch)
        try {
            val r = client.execute(Command.SetOutputTimed(address, ch, t))
            if (!checkAck(r, "0x16 第 $ch 路定时 $t s")) return "未受理"
            val start = (r as CommResult.Ok).writtenAtNanos
            waitUntilNanos(start + t * 500_000_000L)
            expectOutputs("定时中（${t / 2.0} s）", bit, bit)
            waitUntilNanos(start + t * 1_000_000_000L + plan.timerToleranceMs * 1_000_000L)
            expectOutputs("到期后（$t s + 容差 ${plan.timerToleranceMs} ms）", 0, bit)
        } finally {
            finishOutputs()
        }
        return "$t s"
    }

    private suspend fun Ctx.modbusOutputs(outputs: List<Int>): String {
        val all = LockMask.of(outputs)
        val a = outputs[0]
        val t = plan.timedSeconds
        val reg = Modbus.REG_SINGLE_CHANNEL
        try {
            if (checkAck(client.executeModbus(ModbusRequest.WriteSingleRegister(address, Modbus.REG_OUTPUT_LO, all)), "06 写 0004=${"%03X".format(all)}")) {
                expectOutputs("06 写后", all, Protocol.ALL_CHANNELS_MASK)
            }
            if (checkAck(client.executeModbus(ModbusRequest.WriteSingleRegister(address, Modbus.REG_OUTPUT_LO, 0)), "06 写 0004=0000")) {
                expectOutputs("06 写 0 后", 0, Protocol.ALL_CHANNELS_MASK)
            }
            if (checkAck(client.executeModbus(ModbusRequest.WriteMultipleRegisters(address, Modbus.REG_OUTPUT_LO, listOf(all, 0))), "10 写 0004–0005")) {
                expectOutputs("10 写后", all, Protocol.ALL_CHANNELS_MASK)
            }
            if (checkAck(client.executeModbus(ModbusRequest.WriteMultipleRegisters(address, Modbus.REG_OUTPUT_LO, listOf(0, 0))), "10 写 0004–0005=0")) {
                expectOutputs("10 写 0 后", 0, Protocol.ALL_CHANNELS_MASK)
            }
            if (checkAck(client.executeModbus(ModbusRequest.WriteMultipleRegisters(address, reg, listOf(a, 1))), "10 写 0008 第 $a 路持续开")) {
                expectOutputs("0008 开后", LockMask.bit(a), all)
            }
            if (checkAck(client.executeModbus(ModbusRequest.WriteMultipleRegisters(address, reg, listOf(a, 0))), "10 写 0008 第 $a 路关")) {
                expectOutputs("0008 关后", 0, all)
            }
            val r = client.executeModbus(ModbusRequest.WriteMultipleRegisters(address, reg, listOf(a, 1, t)))
            if (checkAck(r, "10 写 0008 第 $a 路定时 $t s")) {
                val start = (r as CommResult.Ok).writtenAtNanos
                waitUntilNanos(start + t * 500_000_000L)
                expectOutputs("定时中", LockMask.bit(a), LockMask.bit(a))
                waitUntilNanos(start + t * 1_000_000_000L + plan.timerToleranceMs * 1_000_000L)
                expectOutputs("到期后", 0, LockMask.bit(a))
            }
        } finally {
            finishOutputs()
        }
        return "${outputs.size} 路"
    }

    /**
     * Reads back 0004 and checks [relevant] bits against [expected], but only if the read was written after
     * [notBefore] and before [notAfter]; otherwise the sample cannot prove anything (unverified).
     */
    private suspend fun Ctx.expectOutputsWithin(label: String, expected: Int, relevant: Int, notBefore: Long, notAfter: Long) {
        val r = client.readOutputs(address)
        val repliedAt = clock.nanoTime()
        val regs = r.confirmedReply() as? ModbusReply.Registers
        val v = regs?.takeIf { it.value(Modbus.REG_OUTPUT_HI) == 0 }?.value(Modbus.REG_OUTPUT_LO)
        val at = (r as? CommResult.Ok)?.writtenAtNanos
        when {
            v == null || at == null -> step("$label：读回 0004 失败或归属不明", Verdict.FAIL)
            // The board samples somewhere between the write and the reply: the whole interval must be inside the window.
            at < notBefore || repliedAt > notAfter -> step("$label：写出到回复的区间不在观测窗口内（重试或等待过长），无法判定", Verdict.UNVERIFIED)
            else -> {
                val ok = v and relevant == expected and relevant
                step("$label：读回 0004=${"%03X".format(v)}，期望通电 [${LockMask.describe(expected and relevant)}]", if (ok) Verdict.PASS else Verdict.FAIL)
            }
        }
    }

    /**
     * X1: a later timer on the same channel wins (other protocol), and channels expire independently.
     * Each deadline is computed from its own write; every observation is checked against its actual
     * sample time.
     */
    private suspend fun Ctx.crossRetime(outputs: List<Int>): String {
        val t = plan.timedSeconds
        val a = outputs[0]
        val b = outputs.getOrNull(1)
        val tol = plan.timerToleranceMs * 1_000_000L
        val sec = 1_000_000_000L
        try {
            val r1 = client.execute(Command.SetOutputTimed(address, a, t))
            if (!checkAck(r1, "私有 0x16 第 $a 路定时 $t s")) return "未受理"
            val firstDeadlineA = (r1 as CommResult.Ok).writtenAtNanos + t * sec
            val rA = client.executeModbus(ModbusRequest.WriteMultipleRegisters(address, Modbus.REG_SINGLE_CHANNEL, listOf(a, 1, 2 * t)))
            if (!checkAck(rA, "MODBUS 10 写 0008 第 $a 路重新定时 ${2 * t} s")) return "未受理"
            val aDeadline = (rA as CommResult.Ok).writtenAtNanos + 2 * t * sec
            var bDeadline: Long? = null
            if (b != null) {
                val rB = client.execute(Command.SetOutputTimed(address, b, t))
                if (checkAck(rB, "私有 0x16 第 $b 路定时 $t s")) {
                    bDeadline = (rB as CommResult.Ok).writtenAtNanos + t * sec
                    expectOutputsWithin("第 $b 路定时开启后", LockMask.bit(b), LockMask.bit(b), rB.writtenAtNanos, bDeadline - tol)
                }
            }
            // Observation after A's original deadline (and B's), before A's new deadline.
            val observeAt = maxOf(firstDeadlineA, bDeadline ?: 0L) + tol
            if (observeAt + tol >= aDeadline) {
                step("定时 ${t} s 与容差 ${plan.timerToleranceMs} ms 无法形成观测窗口（请增大定时秒数或减小容差）", Verdict.UNVERIFIED)
            } else {
                waitUntilNanos(observeAt)
                // One read-back proves both: A still on (re-timed) and B already off (independent timer).
                val bOff = b != null && bDeadline != null
                expectOutputsWithin(
                    "原定时截止点之后：第 $a 路仍开（后一次定时生效）" + if (bOff) "，第 $b 路已到期（独立计时）" else "",
                    LockMask.bit(a), LockMask.bit(a) or (if (bOff) LockMask.bit(b!!) else 0), observeAt, aDeadline - tol,
                )
            }
            waitUntilNanos(aDeadline + tol)
            expectOutputs("新截止点之后：第 $a 路已到期", 0, LockMask.bit(a))
        } finally {
            finishOutputs()
        }
        return if (b == null) "重新定时（只有 1 路输出，未测独立到期）" else "重新定时与独立到期"
    }

    /** X2: private 0x1A cancels a MODBUS timer while keeping the channel on. */
    private suspend fun Ctx.crossCancel(a: Int): String {
        val t = plan.timedSeconds
        try {
            val r = client.executeModbus(ModbusRequest.WriteMultipleRegisters(address, Modbus.REG_SINGLE_CHANNEL, listOf(a, 1, t)))
            if (!checkAck(r, "MODBUS 10 写 0008 第 $a 路定时 $t s")) return "未受理"
            val start = (r as CommResult.Ok).writtenAtNanos
            if (!checkAck(client.execute(Command.SetOutputs(address, LockMask.bit(a))), "私有 0x1A 保留第 $a 路（取消全部定时）")) return "未受理"
            waitUntilNanos(start + t * 1_000_000_000L + plan.timerToleranceMs * 1_000_000L)
            expectOutputs("原截止点之后：第 $a 路仍开（定时已被 0x1A 取消）", LockMask.bit(a), LockMask.bit(a))
            if (checkAck(client.execute(Command.SetOutput(address, a, false)), "私有 0x16 第 $a 路关")) expectOutputs("主动关断后", 0, LockMask.bit(a))
        } finally {
            finishOutputs()
        }
        return "0x1A 取消定时"
    }
}
