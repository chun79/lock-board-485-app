package cn.tuodi.lockboard.core.client

import cn.tuodi.lockboard.core.modbus.Modbus
import cn.tuodi.lockboard.core.modbus.ModbusKind
import cn.tuodi.lockboard.core.modbus.ModbusProtocolException
import cn.tuodi.lockboard.core.modbus.ModbusReply
import cn.tuodi.lockboard.core.modbus.ModbusRequest
import cn.tuodi.lockboard.core.modbus.ModbusResponseLocator
import cn.tuodi.lockboard.core.protocol.Cmd
import cn.tuodi.lockboard.core.protocol.Command
import cn.tuodi.lockboard.core.protocol.CommandKind
import cn.tuodi.lockboard.core.protocol.Direction
import cn.tuodi.lockboard.core.protocol.Frame
import cn.tuodi.lockboard.core.protocol.FrameCodec
import cn.tuodi.lockboard.core.protocol.FrameParser
import cn.tuodi.lockboard.core.protocol.LockMask
import cn.tuodi.lockboard.core.protocol.ParseEvent
import cn.tuodi.lockboard.core.protocol.Protocol
import cn.tuodi.lockboard.core.protocol.ProtocolException
import cn.tuodi.lockboard.core.protocol.Reply
import cn.tuodi.lockboard.core.protocol.RequestInfo
import cn.tuodi.lockboard.core.protocol.toByteArray
import cn.tuodi.lockboard.core.protocol.toHex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Fixed, read-only (0x01) malformed private inputs used by the robustness test T9. */
enum class DiagnosticProbe(val label: String) {
    BAD_CHECKSUM("校验错误的读版本帧"),
    TRUNCATED("只发前 4 字节的读版本半帧"),
    NOISE_PREFIX("前置杂散字节 00 FF 55 的读版本帧");

    fun bytes(address: Int): List<Int> {
        val good = FrameCodec.encode(address, Cmd.GET_VERSION)
        return when (this) {
            BAD_CHECKSUM -> good.dropLast(1) + ((good.last() + 0x5A) and 0xFF)
            TRUNCATED -> good.take(4)
            NOISE_PREFIX -> listOf(0x00, 0xFF, 0x55) + good
        }
    }
}

/**
 * MODBUS negative probes (test M7). Each must be answered with [expectedException]; each is harmless
 * even if executed: reads, a coil write of 0000 (no action), "off" of a non-existent channel, and a
 * 0004–0005 write that keeps the current outputs (only the reserved half is illegal). The caller must not
 * have timed outputs running on the board when sending [ATOMIC_RESERVED] (a 0005 write cancels timers).
 */
enum class ModbusProbe(val label: String, val expectedException: Int) {
    COIL_ZERO("05 写线圈 0001 值 0000", 3),
    UNSUPPORTED_FUNCTION("功能码 01（读线圈，不支持）", 1),
    READ_OUT_OF_RANGE("03 读 000B（超范围）", 2),
    CHANNEL_13("10 写 0008 锁号 13（关闭）", 2),
    ATOMIC_RESERVED("10 写 0004–0005 =［当前输出, 0001］", 3);

    fun request(address: Int, currentOutputs: Int): ModbusRequest = when (this) {
        COIL_ZERO -> ModbusRequest.WriteSingleCoil(address, 1, 0x0000)
        UNSUPPORTED_FUNCTION -> ModbusRequest.Custom(address, 0x01, listOf(0x00, 0x01, 0x00, 0x01))
        READ_OUT_OF_RANGE -> ModbusRequest.ReadHoldingRegisters(address, 0x000B, 1)
        CHANNEL_13 -> ModbusRequest.WriteMultipleRegisters(address, Modbus.REG_SINGLE_CHANNEL, listOf(13, 0))
        // [currentOutputs] here is the low word to try: 0, or an armed lamp channel so partial application is observable.
        ATOMIC_RESERVED -> ModbusRequest.WriteMultipleRegisters(address, Modbus.REG_OUTPUT_LO, listOf(currentOutputs and 0x0FFF, 0x0001))
    }
}

/** Per-request options; the shared [ClientSettings] stay untouched. */
data class ExecOptions(
    val timeoutMs: Int? = null,
    val readRetries: Int? = null,
    val lateGuardMs: Int? = null,
    /** Wait (inside the lock) until no earlier same-shape request to this board can still be answered. */
    val requireClean: Boolean = false,
    /** Operator explicitly confirmed a MODBUS broadcast pulse. */
    val broadcastConfirmed: Boolean = false,
)

/** Result of a raw terminal send or diagnostic probe. */
data class RawExchange(
    val sent: List<Int>,
    /** Board → host private frames received. */
    val received: List<Frame>,
    /** Board → host private frames with a bad checksum. */
    val badFrames: List<Frame>,
    /** Bytes not forming a private frame (MODBUS replies show up here and in [modbusReply]). */
    val garbage: List<Int>,
    val listenedMs: Long,
    val rejected: String? = null,
    /** Monotonic time the bytes were handed to the transport (null when nothing was written). */
    val writtenAtNanos: Long? = null,
    /** Decoded MODBUS reply when the raw frame was a MODBUS request. */
    val modbusReply: ModbusReply? = null,
)

data class CleanupResult(
    /** Channels whose continuous output could not be confirmed off by a trusted read-back. */
    val possiblyOn: List<OutputKey>,
    /** A pulse task may still drive outputs until this monotonic time (null = none). */
    val pulseActiveUntilNanos: Long?,
)

data class StatsSnapshot(
    val txFrames: Long = 0,
    val rxFrames: Long = 0,
    val timeouts: Long = 0,
    val checksumErrors: Long = 0,
    val strayFrames: Long = 0,
    val garbageBytes: Long = 0,
) {
    operator fun minus(o: StatsSnapshot) = StatsSnapshot(
        txFrames - o.txFrames, rxFrames - o.rxFrames, timeouts - o.timeouts,
        checksumErrors - o.checksumErrors, strayFrames - o.strayFrames, garbageBytes - o.garbageBytes,
    )

    fun summary() = "发送 $txFrames 帧，有效回复 $rxFrames，超时 $timeouts，校验错误 $checksumErrors，" +
        "无关/迟到帧 $strayFrames，杂散字节 $garbageBytes"
}

class ClientStats {
    val txFrames = AtomicLong()
    val rxFrames = AtomicLong()
    val timeouts = AtomicLong()
    val checksumErrors = AtomicLong()
    val strayFrames = AtomicLong()
    val garbageBytes = AtomicLong()

    fun snapshot() = StatsSnapshot(txFrames.get(), rxFrames.get(), timeouts.get(), checksumErrors.get(), strayFrames.get(), garbageBytes.get())

    fun summary() = snapshot().summary()
}

/**
 * Half-duplex master for one bus speaking both protocols of the board (private DC-frames and MODBUS RTU).
 * Transactions are serialised by a mutex and run on [ioDispatcher].
 *
 * Per transaction: [pulse pacing] → lock → late-reply guard of a previous timeout → inter-frame gap →
 * drain → (optional) wait until the reply key is clean → **final safety check + output registration +
 * reply-hygiene bookkeeping** → write → read in ≤ 50 ms slices until the matching reply or the deadline.
 *
 * Reply hygiene: replies carry no request id and some have identical shapes (e.g. any MODBUS 03 of two
 * registers). A request written while an earlier request with the same key went unanswered within
 * [ClientSettings.lateReplyMaxAgeMs] gets an *ambiguous* reply; ambiguous replies never confirm anything.
 */
class LockBoardClient(
    private val transport: ByteTransport,
    settings: ClientSettings,
    val log: CommLog,
    val safety: SafetyPolicy,
    private val clock: Clock = SystemClock,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Closeable {
    @Volatile
    var settings: ClientSettings = settings.validated()
        set(value) { field = value.validated() }

    val transportKind: TransportKind get() = transport.kind
    val transportDescription: String get() = transport.description
    val outputs = OutputTracker()
    val stats = ClientStats()

    @Volatile
    var failure: IOException? = null
        private set

    val isOpen: Boolean get() = failure == null

    /** Invoked on the transaction thread whenever a request receives a confirmed, non-broadcast reply. */
    @Volatile
    var onConfirmedReply: (() -> Unit)? = null

    /** Monotonic time of the most recent write (any transaction). */
    @Volatile
    var lastWriteNanos: Long = 0L
        private set

    private val mutex = Mutex()
    private val readBuffer = ByteArray(512)
    private var lastLineActivity = clock.nanoTime()
    private var quarantineUntil = 0L

    /** Reply-hygiene key → monotonic time until which a late reply may still arrive. */
    private val dirtyUntil = ConcurrentHashMap<String, Long>()

    /** Address (0 = broadcast) → monotonic time until which a pulse task may still run. */
    private val pulseBusyUntil = ConcurrentHashMap<Int, Long>()

    init {
        safety.isEnergized = { outputs.isTracked(it) }
    }

    private val maxAgeNanos get() = settings.lateReplyMaxAgeMs * 1_000_000L

    // ------------------------------------------------------------------------------------------
    // Public API

    suspend fun <R : Reply> execute(command: Command<R>, options: ExecOptions = ExecOptions()): CommResult<R> {
        safety.check(command)?.let { return rejected(command, command.encode(), it) }
        val s = settings
        val channels = command.pulsedChannels()
        val sequential = command.kind == CommandKind.PULSE_SEQUENTIAL
        val timeout = options.timeoutMs ?: when (command.kind) {
            CommandKind.READ, CommandKind.IMMEDIATE -> s.readTimeoutMs
            else -> if (channels.isEmpty()) s.readTimeoutMs else s.pulseWindowMs(sequential, channels.size).toInt()
        }
        return transaction(
            info = command,
            address = command.address,
            bytes = command.encode(),
            isRead = command.kind == CommandKind.READ,
            timeoutMs = timeout,
            options = options,
            hygieneKey = "P:${command.address}:${command.code}",
            pulse = channels.takeIf { it.isNotEmpty() }?.let { PulseSpec(it.size, sequential) },
            finalCheck = { safety.check(command) },
            energized = { energizedByPrivate(command.address, command.code, command.data) },
            matcher = { PrivateMatcher(command) },
            broadcastReply = null,
            afterOk = { ok ->
                // A private pulse command answers after its action: an attributable reply ends the window.
                if (channels.isNotEmpty() && ok.confirmed) pulseBusyUntil.remove(command.address)
                ok
            },
        )
    }

    suspend fun executeModbus(raw: ModbusRequest, options: ExecOptions = ExecOptions()): CommResult<ModbusReply> {
        // One request object for document check, policy, reply matching and pulse pacing.
        val request = if (raw is ModbusRequest.Custom) ModbusRequest.fromFrame(raw.encode()) ?: raw else raw
        request.docViolation()?.let { return rejected(request, request.encode(), "不符合协议文档：$it") }
        safety.checkModbus(request, options.broadcastConfirmed)?.let { return rejected(request, request.encode(), it) }
        return modbusTransaction(request, options)
    }

    /**
     * Trusted read-back of the continuous-output control word (0004..0005). Waits until no earlier MODBUS
     * reply to this board can still arrive, and applies the result to [outputs] inside the transaction.
     */
    suspend fun readOutputs(address: Int): CommResult<ModbusReply> =
        modbusTransaction(ModbusRequest.ReadHoldingRegisters(address, Modbus.REG_OUTPUT_LO, 2), ExecOptions(requireClean = true), applyReadback = true)

    /**
     * M7 negative probe: a fixed illegal unicast request the board must answer with an exception. Sent
     * without the document/policy checks because each probe is harmless even if a faulty firmware
     * executed it (see [ModbusProbe]); the session lock-down still applies.
     */
    suspend fun sendModbusProbe(address: Int, probe: ModbusProbe, currentOutputs: Int = 0): CommResult<ModbusReply> {
        val request = probe.request(address, currentOutputs)
        if (address !in Protocol.MIN_ADDRESS..Protocol.MAX_ADDRESS) return rejected(request, request.encode(), "探针只能单播到地址 1～15")
        if (probe == ModbusProbe.ATOMIC_RESERVED) {
            // The precondition is checked by this client itself: a trusted read-back must show every output off.
            val rb = readOutputs(address).confirmedReply() as? ModbusReply.Registers
            if (rb?.value(Modbus.REG_OUTPUT_LO) != 0 || rb.value(Modbus.REG_OUTPUT_HI) != 0) {
                return rejected(request, request.encode(), "原子性探针要求可信读回 0004=0（当前 ${rb?.value(Modbus.REG_OUTPUT_LO)?.let { "%03X".format(it) } ?: "读取失败"}）")
            }
        }
        val guard: () -> String? = {
            when {
                safety.isLockedDown -> "正在断开"
                probe != ModbusProbe.ATOMIC_RESERVED -> null
                // The reserved write cancels timers if it were (wrongly) applied: only with every output off,
                // and its low word must be a write the policy would allow.
                outputs.energized().any { it.address == address } -> "该板仍有登记的输出，不能发送原子性探针"
                else -> safety.checkModbus(ModbusRequest.WriteMultipleRegisters(address, Modbus.REG_OUTPUT_LO, listOf(currentOutputs and 0x0FFF, 0)))
            }
        }
        guard()?.let { return rejected(request, request.encode(), it) }
        return modbusTransaction(request, ExecOptions(requireClean = true), finalCheck = guard)
    }

    /** Monotonic time from which a new pulse task to [address] will not replace a running one. */
    fun pulseFreeAt(address: Int): Long =
        if (address == Modbus.BROADCAST) pulseBusyUntil.values.maxOrNull() ?: 0L
        else maxOf(pulseBusyUntil[address] ?: 0L, pulseBusyUntil[Modbus.BROADCAST] ?: 0L)

    /** Waits until any pulse task to [address] has had time to finish. */
    suspend fun awaitPulseIdle(address: Int) {
        val wait = (pulseFreeAt(address) - clock.nanoTime()) / 1_000_000
        if (wait > 0) delay(wait + 1)
    }

    /** Returns once every transaction admitted before this call has completed. */
    suspend fun barrier() = mutex.withLock { }

    /** State that must survive a reconnect to the same bus (see [importCarry]). */
    data class Carry(
        val outputs: List<OutputKey>,
        val pulseBusyUntil: Map<Int, Long>,
        val dirtyUntil: Map<String, Long>,
    ) {
        val isEmpty get() = outputs.isEmpty() && pulseBusyUntil.isEmpty() && dirtyUntil.isEmpty()
    }

    fun exportCarry(): Carry {
        val now = clock.nanoTime()
        return Carry(
            outputs.energized(),
            pulseBusyUntil.filterValues { it > now },
            dirtyUntil.filterValues { it > now },
        )
    }

    /** A new session inherits unconfirmed outputs, running pulse windows and reply quarantine. */
    fun importCarry(carry: Carry) {
        outputs.registerPossiblyOn(carry.outputs)
        carry.pulseBusyUntil.forEach { (a, t) -> pulseBusyUntil.merge(a, t) { x, y -> maxOf(x, y) } }
        carry.dirtyUntil.forEach { (k, t) -> dirtyUntil.merge(k, t) { x, y -> maxOf(x, y) } }
    }

    // ------------------------------------------------------------------------------------------
    // Transaction engine

    private data class PulseSpec(val channels: Int, val sequential: Boolean)

    private class Decoded<R : Reply>(val raw: List<Int>, val reply: R?, val protocolError: String?, val receivedAt: Long)

    private interface ReplyMatcher<R : Reply> {
        fun feed(bytes: List<Int>, t: Long): Decoded<R>?
        fun stalled(t: Long): Decoded<R>?
        fun hasPending(): Boolean
        fun finish()
    }

    private fun rejected(info: RequestInfo, bytes: List<Int>, reason: String): CommResult.Failed {
        log.add(LogKind.WARN, "拒绝发送 ${info.title}：$reason", bytes)
        return CommResult.Failed(info, CommError.REJECTED, reason, 0)
    }

    private suspend fun modbusTransaction(
        request: ModbusRequest,
        options: ExecOptions,
        applyReadback: Boolean = false,
        finalCheck: () -> String? = { safety.checkModbus(request, options.broadcastConfirmed) },
    ): CommResult<ModbusReply> {
        val channels = request.pulsedChannels()
        val sequential = request.function == Modbus.FC_WRITE_SINGLE_COIL || request.function == Modbus.FC_WRITE_MULTIPLE_COILS
        return transaction(
            info = request,
            address = request.address,
            bytes = request.encode(),
            isRead = request.kind == ModbusKind.READ,
            timeoutMs = options.timeoutMs ?: settings.readTimeoutMs,
            options = options,
            hygieneKey = if (request.isBroadcast) null else "M:${request.address}",
            pulse = channels.takeIf { it.isNotEmpty() }?.let { PulseSpec(it.size, sequential) },
            finalCheck = finalCheck,
            energized = { energizedByModbus(request) },
            matcher = { ModbusMatcher(request) },
            broadcastReply = if (request.isBroadcast) ModbusReply.BroadcastSent else null,
            afterOk = { ok ->
                if (applyReadback && ok.confirmed) {
                    val regs = ok.reply as? ModbusReply.Registers
                    val lo = regs?.value(Modbus.REG_OUTPUT_LO)
                    if (regs != null && lo != null && regs.value(Modbus.REG_OUTPUT_HI) == 0 && lo and 0xF000 == 0) {
                        outputs.applyReadback(request.address, lo)
                    }
                }
                ok
            },
        )
    }

    private suspend fun <R : Reply> transaction(
        info: RequestInfo,
        address: Int,
        bytes: List<Int>,
        isRead: Boolean,
        timeoutMs: Int,
        options: ExecOptions,
        hygieneKey: String?,
        pulse: PulseSpec?,
        finalCheck: () -> String?,
        energized: () -> List<OutputKey>,
        matcher: () -> ReplyMatcher<R>,
        broadcastReply: R?,
        afterOk: (CommResult.Ok<R>) -> CommResult.Ok<R>,
    ): CommResult<R> {
        failure?.let { return CommResult.Failed(info, CommError.NOT_CONNECTED, it.message ?: "", 0) }
        return withBus(address, pulse != null) {
            withContext(ioDispatcher) {
                val s = settings
                val retries = if (isRead) (options.readRetries ?: s.readRetries).coerceAtLeast(0) else 0
                var last: CommResult.Failed? = null
                for (attempt in 1..retries + 1) {
                    if (attempt > 1) log.add(LogKind.INFO, "${info.title}：第 $attempt 次尝试（读类请求自动重试）")
                    var writtenAt: Long? = null
                    val result: CommResult<R> = try {
                        attemptOnce(info, address, bytes, isRead, timeoutMs, options, hygieneKey, pulse, finalCheck, energized,
                            matcher(), broadcastReply, afterOk, attempt) { writtenAt = it }
                    } catch (e: IOException) {
                        markBroken(e)
                        return@withContext CommResult.Failed(info, CommError.TRANSPORT, e.message ?: e.javaClass.simpleName, attempt, writtenAtNanos = writtenAt)
                    }
                    when (result) {
                        is CommResult.Ok -> return@withContext result
                        is CommResult.Failed -> {
                            last = result
                            if (result.error != CommError.TIMEOUT) return@withContext result
                        }
                    }
                }
                last!!
            }
        }
    }

    /** Acquires the bus; pulse requests additionally wait until no pulse task can be replaced. */
    private suspend fun <T> withBus(address: Int, pulse: Boolean, block: suspend () -> T): T {
        if (!pulse) return mutex.withLock { block() }
        while (true) {
            val waitMs = (pulseFreeAt(address) - clock.nanoTime()) / 1_000_000
            if (waitMs > 0) delay(waitMs + 1)
            mutex.lock()
            try {
                if (pulseFreeAt(address) <= clock.nanoTime()) return block()
            } finally {
                mutex.unlock()
            }
        }
    }

    private suspend fun <R : Reply> attemptOnce(
        info: RequestInfo,
        address: Int,
        bytes: List<Int>,
        isRead: Boolean,
        timeoutMs: Int,
        options: ExecOptions,
        hygieneKey: String?,
        pulse: PulseSpec?,
        finalCheck: () -> String?,
        energized: () -> List<OutputKey>,
        m: ReplyMatcher<R>,
        broadcastReply: R?,
        afterOk: (CommResult.Ok<R>) -> CommResult.Ok<R>,
        attempt: Int,
        onWritten: (Long) -> Unit,
    ): CommResult<R> {
        val s = settings
        prepareLine(s, options.lateGuardMs)
        if (options.requireClean && hygieneKey != null) waitClean(hygieneKey, s)
        // Final authorisation at the moment of transmission (disarm, profile edit or lockDown while queued).
        finalCheck()?.let { return rejected(info, bytes, it) }
        outputs.registerPossiblyOn(energized())
        val writeAt = clock.nanoTime()
        val ambiguous = hygieneKey != null && onWriteHygiene(hygieneKey, writeAt)
        var answered = false
        // A write that throws may still have reached the bus: register the full action window before it.
        pulse?.let { p -> pulseBusyUntil.merge(address, writeAt + (s.wireTimeMs(bytes.size) + s.pulseWindowMs(p.sequential, p.channels)) * 1_000_000) { a, b -> maxOf(a, b) } }
        try {
            lastWriteNanos = writeAt
            onWritten(writeAt)
            transport.write(bytes.toByteArray())
            stats.txFrames.incrementAndGet()
            log.add(LogKind.TX, info.title, bytes)
            val sentAt = clock.nanoTime() + s.wireTimeMs(bytes.size) * 1_000_000
            lastLineActivity = sentAt
            pulse?.let { p ->
                val until = sentAt + s.pulseWindowMs(p.sequential, p.channels) * 1_000_000
                pulseBusyUntil.merge(address, until) { a, b -> maxOf(a, b) }
            }
            if (broadcastReply != null) {
                answered = true
                delay(s.interFrameGapMs.toLong() + 50)
                return afterOk(CommResult.Ok(info, broadcastReply, emptyList(), 0, attempt, writtenAtNanos = writeAt))
            }
            val deadline = sentAt + timeoutMs * 1_000_000L
            var lastByteAt = clock.nanoTime()
            while (true) {
                currentCoroutineContext().ensureActive()
                val now = clock.nanoTime()
                if (now >= deadline) break
                val n = transport.read(readBuffer, ((deadline - now) / 1_000_000).coerceIn(1, 50).toInt())
                val t = clock.nanoTime()
                val d = when {
                    n > 0 -> {
                        lastByteAt = t
                        lastLineActivity = t
                        m.feed(List(n) { readBuffer[it].toInt() and 0xFF }, t)
                    }
                    m.hasPending() && t - lastByteAt >= s.interByteTimeoutMs * 1_000_000L -> m.stalled(t)
                    else -> null
                }
                if (d != null) {
                    answered = true
                    m.finish()
                    if (d.protocolError != null) {
                        log.add(LogKind.ERROR, "${info.title}：${d.protocolError}", d.raw)
                        return CommResult.Failed(info, CommError.PROTOCOL, d.protocolError, attempt, d.raw, writeAt)
                    }
                    val latency = ((d.receivedAt - sentAt) / 1_000_000).coerceAtLeast(0)
                    val notes = if (ambiguous) listOf("更早的同类请求仍可能迟到回复，本回复归属不明") else emptyList()
                    val ok = afterOk(CommResult.Ok(info, d.reply!!, d.raw, latency, attempt, notes, ambiguous, writeAt))
                    if (ok.confirmed && isConfirmedTransactionReply(info, ok.raw)) onConfirmedReply?.invoke()
                    return ok
                }
            }
            m.finish()
            quarantineUntil = clock.nanoTime() + (options.lateGuardMs ?: s.lateReplyGuardMs) * 1_000_000L
            stats.timeouts.incrementAndGet()
            val error = if (isRead) CommError.TIMEOUT else CommError.OUTCOME_UNKNOWN
            log.add(LogKind.WARN, "${info.title}：${error.label}（$timeoutMs ms）")
            return CommResult.Failed(info, error, "$timeoutMs ms 内无回复", attempt, writtenAtNanos = writeAt)
        } finally {
            // Written but not answered (timeout, cancellation, I/O error): its reply may still arrive late.
            if (!answered && hygieneKey != null) markDirty(hygieneKey, writeAt)
        }
    }

    private fun markDirty(key: String, writeAt: Long) {
        dirtyUntil.merge(key, writeAt + maxAgeNanos) { a, b -> maxOf(a, b) }
    }

    /** Records a write; returns true if the key was dirty (the reply will be ambiguous), extending the dirty period. */
    private fun onWriteHygiene(key: String, writeAt: Long): Boolean {
        val until = dirtyUntil[key] ?: return false
        if (until <= writeAt) {
            dirtyUntil.remove(key)
            return false
        }
        markDirty(key, writeAt)
        return true
    }

    /** Inside the lock: discard whatever arrives until [key] is clean. */
    private suspend fun waitClean(key: String, s: ClientSettings) {
        val until = dirtyUntil[key] ?: return
        if (until <= clock.nanoTime()) return
        log.add(LogKind.INFO, "等待更早的未回复请求过期（${(until - clock.nanoTime()) / 1_000_000} ms）后再发送")
        val lateParser = FrameParser()
        while (clock.nanoTime() < until) {
            currentCoroutineContext().ensureActive()
            val n = transport.read(readBuffer, ((until - clock.nanoTime()) / 1_000_000).coerceIn(1, 50).toInt())
            if (n > 0) {
                lastLineActivity = clock.nanoTime()
                logLate(lateParser.feed(readBuffer, n))
            }
        }
        dirtyUntil.remove(key)
        prepareLine(s, 0)
    }

    private inner class PrivateMatcher<R : Reply>(private val command: Command<R>) : ReplyMatcher<R> {
        private val parser = FrameParser()

        private fun handle(events: List<ParseEvent>, t: Long): Decoded<R>? {
            var result: Decoded<R>? = null
            for (event in events) {
                when (event) {
                    is ParseEvent.FrameReceived -> {
                        val f = event.frame
                        when {
                            f.direction == Direction.HOST_TO_BOARD -> log.add(LogKind.RX_ECHO, "主机方向帧（回显或其他主机）：${f.describe()}", f.raw)
                            result == null && f.address == command.address && f.command == command.code -> {
                                stats.rxFrames.incrementAndGet()
                                log.add(LogKind.RX, f.describe(), f.raw)
                                result = try {
                                    Decoded(f.raw, command.decode(f), null, t)
                                } catch (e: ProtocolException) {
                                    Decoded(f.raw, null, e.message ?: "格式异常", t)
                                }
                            }
                            else -> {
                                stats.strayFrames.incrementAndGet()
                                log.add(LogKind.RX_STRAY, "与请求不匹配，已忽略：${f.describe()}", f.raw)
                            }
                        }
                    }
                    is ParseEvent.BadChecksum -> {
                        stats.checksumErrors.incrementAndGet()
                        log.add(LogKind.RX_BAD, event.frame.describe(), event.frame.raw)
                    }
                    is ParseEvent.Garbage -> {
                        stats.garbageBytes.addAndGet(event.bytes.size.toLong())
                        log.add(LogKind.GARBAGE, event.reason, event.bytes)
                    }
                }
            }
            return result
        }

        override fun feed(bytes: List<Int>, t: Long) = handle(parser.feed(bytes), t)
        override fun stalled(t: Long) = handle(parser.flushStalled(), t)
        override fun hasPending() = parser.pendingBytes.isNotEmpty()
        override fun finish() {
            val rest = parser.reset()
            if (rest.isNotEmpty()) {
                stats.garbageBytes.addAndGet(rest.size.toLong())
                log.add(LogKind.GARBAGE, "不完整的数据", rest)
            }
        }
    }

    private inner class ModbusMatcher(private val request: ModbusRequest) : ReplyMatcher<ModbusReply> {
        private val buf = ArrayList<Int>()
        private val sent = request.encode()
        private var echoPending = settings.discardTxEcho

        override fun feed(bytes: List<Int>, t: Long): Decoded<ModbusReply>? {
            buf += bytes
            if (echoPending) {
                val i = indexOf(buf, sent)
                if (i >= 0) {
                    log.add(LogKind.RX_ECHO, "发送回显，已丢弃", sent)
                    buf.subList(i, i + sent.size).clear()
                    echoPending = false
                } else return null
            }
            val match = ModbusResponseLocator.find(buf, request) ?: return null
            if (match.start > 0) {
                val junk = buf.subList(0, match.start).toList()
                stats.garbageBytes.addAndGet(junk.size.toLong())
                log.add(LogKind.GARBAGE, "回复前的无关字节", junk)
            }
            buf.subList(0, match.start + match.frame.size).clear()
            stats.rxFrames.incrementAndGet()
            val f = match.frame
            log.add(LogKind.RX, "MODBUS ${Modbus.describeFunction(f[1])}${if (f[1] and 0x80 != 0) " 异常" else ""}", f)
            return try {
                val reply = if (f[1] == (request.function or 0x80)) ModbusReply.Exception(request.function, f[2]) else request.decodeNormal(f)
                Decoded(f, reply, null, t)
            } catch (e: ModbusProtocolException) {
                Decoded(f, null, e.message ?: "格式异常", t)
            }
        }

        override fun stalled(t: Long): Decoded<ModbusReply>? = null
        override fun hasPending() = false
        override fun finish() {
            if (buf.isNotEmpty()) {
                stats.garbageBytes.addAndGet(buf.size.toLong())
                log.add(LogKind.GARBAGE, "未匹配的字节", buf.toList())
                buf.clear()
            }
        }
    }

    private fun indexOf(haystack: List<Int>, needle: List<Int>): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        for (i in 0..haystack.size - needle.size) if (haystack.subList(i, i + needle.size) == needle) return i
        return -1
    }

    /** Late-reply guard, inter-frame gap, then drain. Anything heard here is logged as late. */
    private suspend fun prepareLine(s: ClientSettings, guardOverrideMs: Int?) {
        val lateParser = FrameParser()
        val guardEnd = if (guardOverrideMs != null && quarantineUntil > 0) {
            minOf(quarantineUntil, clock.nanoTime() + guardOverrideMs * 1_000_000L)
        } else quarantineUntil
        while (clock.nanoTime() < guardEnd) {
            currentCoroutineContext().ensureActive()
            val wait = ((guardEnd - clock.nanoTime()) / 1_000_000).coerceIn(1, 50).toInt()
            val n = transport.read(readBuffer, wait)
            if (n > 0) {
                lastLineActivity = clock.nanoTime()
                logLate(lateParser.feed(readBuffer, n))
            }
        }
        quarantineUntil = 0
        val gapWait = (lastLineActivity + s.interFrameGapMs * 1_000_000L - clock.nanoTime()) / 1_000_000
        if (gapWait > 0) delay(gapWait)
        val drained = transport.drainInput()
        if (drained.isNotEmpty()) logLate(lateParser.feed(drained))
        val rest = lateParser.reset()
        if (rest.isNotEmpty()) log.add(LogKind.RX_LATE, "发送前清除的残留字节", rest)
    }

    private fun logLate(events: List<ParseEvent>) {
        for (e in events) {
            when (e) {
                is ParseEvent.FrameReceived -> {
                    stats.strayFrames.incrementAndGet()
                    log.add(LogKind.RX_LATE, "迟到或残留帧，已丢弃：${e.frame.describe()}", e.frame.raw)
                }
                is ParseEvent.BadChecksum -> {
                    stats.checksumErrors.incrementAndGet()
                    log.add(LogKind.RX_LATE, "迟到坏帧，已丢弃：${e.frame.describe()}", e.frame.raw)
                }
                is ParseEvent.Garbage -> {
                    stats.garbageBytes.addAndGet(e.bytes.size.toLong())
                    log.add(LogKind.RX_LATE, "迟到字节已丢弃（可能是 MODBUS 回复）", e.bytes)
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Output registration

    private fun energizedByPrivate(address: Int, code: Int, data: List<Int>): List<OutputKey> = when {
        code == Cmd.SET_OUTPUT && data.size >= 2 && data[1] != 0 -> listOf(OutputKey(address, data[0]))
        code == Cmd.SET_OUTPUTS && data.size == 2 ->
            LockMask.channels(LockMask.fromBytes(data[0], data[1]) and Protocol.ALL_CHANNELS_MASK).map { OutputKey(address, it) }
        else -> emptyList()
    }

    private fun energizedByModbus(r: ModbusRequest): List<OutputKey> = when (r) {
        is ModbusRequest.WriteSingleRegister ->
            if (r.register == Modbus.REG_OUTPUT_LO) LockMask.channels(r.value and Protocol.ALL_CHANNELS_MASK).map { OutputKey(r.address, it) } else emptyList()
        is ModbusRequest.WriteMultipleRegisters -> when {
            r.start == Modbus.REG_SINGLE_CHANNEL && r.values.size >= 2 && r.values[1] != 0 && r.values[0] in 1..12 -> listOf(OutputKey(r.address, r.values[0]))
            else -> r.writes()[Modbus.REG_OUTPUT_LO]?.let { v -> LockMask.channels(v and Protocol.ALL_CHANNELS_MASK).map { OutputKey(r.address, it) } } ?: emptyList()
        }
        is ModbusRequest.Custom -> ModbusRequest.fromFrame(r.encode())?.takeIf { it !is ModbusRequest.Custom }?.let { energizedByModbus(it) } ?: emptyList()
        else -> emptyList()
    }

    /**
     * Switches off every registered channel (private 0x16 off), then confirms with trusted read-backs of
     * 0004, even if the caller is being cancelled. Returns what could not be confirmed off and whether a
     * pulse task may still be driving outputs.
     */
    suspend fun cleanup(): CleanupResult = withContext(NonCancellable) {
        barrier()
        repeat(3) {
            val keys = outputs.energized()
            if (keys.isEmpty() || !isOpen) return@repeat
            for (key in keys) execute(Command.SetOutput(key.address, key.channel, on = false))
            for (address in keys.map { it.address }.distinct()) readOutputs(address)
        }
        val left = outputs.energized()
        if (left.isNotEmpty()) log.add(LogKind.ERROR, "以下通道无法通过读回确认已关断，可能仍带电：${left.joinToString()}")
        val pulseUntil = pulseBusyUntil.values.maxOrNull()?.takeIf { it > clock.nanoTime() }
        if (pulseUntil != null) log.add(LogKind.WARN, "仍有脉冲任务可能在执行，约 ${(pulseUntil - clock.nanoTime()) / 1_000_000} ms 后结束")
        CleanupResult(left, pulseUntil)
    }

    // ------------------------------------------------------------------------------------------
    // Raw terminal and private diagnostic probes

    /**
     * Debug-terminal send. On a physical bus the bytes must be exactly one complete frame — a private
     * host frame or a CRC-valid MODBUS request — passing [SafetyPolicy]; arbitrary bytes only on the simulator.
     */
    suspend fun sendRaw(bytes: List<Int>, listenMs: Int, broadcastConfirmed: Boolean = false): RawExchange {
        validateRaw(bytes, broadcastConfirmed)?.let { reason ->
            log.add(LogKind.WARN, "拒绝发送原始数据：$reason", bytes)
            return RawExchange(bytes, emptyList(), emptyList(), emptyList(), 0, reason)
        }
        return exchange(bytes, listenMs, "终端原始发送", checkRaw = true, broadcastConfirmed = broadcastConfirmed)
    }

    /** T9 robustness probe: fixed read-only frames only, so it bypasses the complete-frame rule safely. */
    suspend fun sendProbe(address: Int, probe: DiagnosticProbe, listenMs: Int): RawExchange =
        // Sent only once an earlier unanswered 0x01 to this board can no longer reply late.
        exchange(probe.bytes(address), listenMs, "诊断：${probe.label}", checkRaw = false, broadcastConfirmed = false,
            cleanKey = "P:$address:${Cmd.GET_VERSION}")

    private fun modbusFrameOf(bytes: List<Int>): ModbusRequest? =
        if (bytes.isNotEmpty() && bytes[0] != Protocol.HEAD) ModbusRequest.fromFrame(bytes) else null

    private suspend fun exchange(
        bytes: List<Int>,
        listenMs: Int,
        title: String,
        checkRaw: Boolean,
        broadcastConfirmed: Boolean,
        cleanKey: String? = null,
    ): RawExchange {
        failure?.let { return RawExchange(bytes, emptyList(), emptyList(), emptyList(), 0, "未连接：${it.message}") }
        val modbus = modbusFrameOf(bytes)
        val privateFrame = if (modbus == null) FrameCodec.decodeSingle(bytes) else null
        val pulse = when {
            modbus != null -> modbus.pulsedChannels().isNotEmpty()
            privateFrame != null -> Command.Custom(privateFrame.address, privateFrame.command, privateFrame.data).pulsedChannels().isNotEmpty()
            else -> false
        }
        val address = modbus?.address ?: privateFrame?.address ?: -1
        return withBus(address, pulse) {
            withContext(ioDispatcher) {
                try {
                    rawExchange(bytes, listenMs, title, checkRaw, broadcastConfirmed, modbus, privateFrame, cleanKey)
                } catch (e: IOException) {
                    markBroken(e)
                    RawExchange(bytes, emptyList(), emptyList(), emptyList(), 0, "链路错误：${e.message}")
                }
            }
        }
    }

    private suspend fun rawExchange(
        bytes: List<Int>,
        listenMs: Int,
        title: String,
        checkRaw: Boolean,
        broadcastConfirmed: Boolean,
        modbus: ModbusRequest?,
        privateFrame: Frame?,
        cleanKey: String?,
    ): RawExchange {
        val s = settings
        prepareLine(s, null)
        cleanKey?.let { waitClean(it, s) }
        if (checkRaw) {
            validateRaw(bytes, broadcastConfirmed)?.let { reason ->
                log.add(LogKind.WARN, "拒绝发送原始数据：$reason", bytes)
                return RawExchange(bytes, emptyList(), emptyList(), emptyList(), 0, reason)
            }
        }
        val key = when {
            modbus != null && !modbus.isBroadcast -> "M:${modbus.address}"
            privateFrame != null -> "P:${privateFrame.address}:${privateFrame.command}"
            else -> null
        }
        modbus?.let { outputs.registerPossiblyOn(energizedByModbus(it)) }
        privateFrame?.let { outputs.registerPossiblyOn(energizedByPrivate(it.address, it.command, it.data)) }
        val writtenAt = clock.nanoTime()
        val ambiguous = key != null && onWriteHygiene(key, writtenAt)
        var answered = false
        val pulseChannels = modbus?.pulsedChannels() ?: privateFrame?.let { Command.Custom(it.address, it.command, it.data).pulsedChannels() } ?: emptyList()
        val pulseSequential = modbus?.let { it.function == Modbus.FC_WRITE_SINGLE_COIL || it.function == Modbus.FC_WRITE_MULTIPLE_COILS }
            ?: (privateFrame?.command != Cmd.UNLOCK_SIMULTANEOUS)
        val pulseAddress = modbus?.address ?: privateFrame?.address
        // A write that throws may still have reached the bus: register the full action window before it.
        if (pulseChannels.isNotEmpty() && pulseAddress != null) {
            val until = writtenAt + (s.wireTimeMs(bytes.size) + s.pulseWindowMs(pulseSequential, pulseChannels.size)) * 1_000_000
            pulseBusyUntil.merge(pulseAddress, until) { a, b -> maxOf(a, b) }
        }
        try {
            lastWriteNanos = writtenAt
            transport.write(bytes.toByteArray())
            stats.txFrames.incrementAndGet()
            log.add(LogKind.TX, title, bytes)
            val start = clock.nanoTime()
            val sentAt = start + s.wireTimeMs(bytes.size) * 1_000_000
            lastLineActivity = sentAt
            if (pulseChannels.isNotEmpty() && pulseAddress != null) {
                val until = sentAt + s.pulseWindowMs(pulseSequential, pulseChannels.size) * 1_000_000
                pulseBusyUntil.merge(pulseAddress, until) { a, b -> maxOf(a, b) }
            }
            val deadline = sentAt + listenMs * 1_000_000L
            val parser = FrameParser()
            val all = ArrayList<Int>()
            val received = ArrayList<Frame>()
            val bad = ArrayList<Frame>()
            val garbage = ArrayList<Int>()
            var lastByteAt = clock.nanoTime()
            while (true) {
                currentCoroutineContext().ensureActive()
                val now = clock.nanoTime()
                if (now >= deadline) break
                val n = transport.read(readBuffer, ((deadline - now) / 1_000_000).coerceIn(1, 50).toInt())
                val t = clock.nanoTime()
                val events = when {
                    n > 0 -> {
                        lastByteAt = t
                        lastLineActivity = t
                        for (i in 0 until n) all += readBuffer[i].toInt() and 0xFF
                        parser.feed(readBuffer, n)
                    }
                    parser.pendingBytes.isNotEmpty() && t - lastByteAt >= s.interByteTimeoutMs * 1_000_000L -> parser.flushStalled()
                    else -> emptyList()
                }
                for (e in events) when (e) {
                    is ParseEvent.FrameReceived -> if (e.frame.direction == Direction.BOARD_TO_HOST) {
                        stats.rxFrames.incrementAndGet()
                        received += e.frame
                        log.add(LogKind.RX, e.frame.describe(), e.frame.raw)
                    } else {
                        log.add(LogKind.RX_ECHO, "主机方向帧：${e.frame.describe()}", e.frame.raw)
                    }
                    is ParseEvent.BadChecksum -> {
                        stats.checksumErrors.incrementAndGet()
                        if (e.frame.direction == Direction.BOARD_TO_HOST) bad += e.frame
                        log.add(LogKind.RX_BAD, e.frame.describe(), e.frame.raw)
                    }
                    is ParseEvent.Garbage -> garbage += e.bytes
                }
            }
            garbage += parser.reset()
            var modbusReply: ModbusReply? = null
            if (modbus != null && !modbus.isBroadcast) {
                val match = ModbusResponseLocator.find(if (s.discardTxEcho) dropFirst(all, modbus.encode()) else all, modbus)
                if (match != null) {
                    val f = match.frame
                    modbusReply = if (f[1] == (modbus.function or 0x80)) ModbusReply.Exception(modbus.function, f[2])
                    else runCatching { modbus.decodeNormal(f) }.getOrElse { ModbusReply.Raw(f) }
                    log.add(LogKind.RX, "MODBUS 回复：${modbusReply.summary()}", f)
                }
            }
            if (garbage.isNotEmpty() && modbusReply == null) {
                stats.garbageBytes.addAndGet(garbage.size.toLong())
                log.add(LogKind.GARBAGE, "非私有帧字节", garbage)
            }
            val confirmedRawReply = !ambiguous && when {
                modbus != null -> !modbus.isBroadcast && modbusReply != null && modbusReply !is ModbusReply.Raw
                privateFrame != null && privateFrame.checksumValid -> received.any {
                    it.address == privateFrame.address && it.command == privateFrame.command &&
                        Command.isConfirmedReplyFrame(privateFrame.command, privateFrame.data, it)
                }
                else -> false
            }
            answered = when {
                modbus != null -> modbus.isBroadcast || modbusReply != null
                privateFrame != null -> received.any { it.address == privateFrame.address && it.command == privateFrame.command }
                else -> true
            }
            if (confirmedRawReply) onConfirmedReply?.invoke()
            if (!answered) quarantineUntil = clock.nanoTime() + s.lateReplyGuardMs * 1_000_000L
            return RawExchange(bytes, received, bad, garbage, (clock.nanoTime() - start) / 1_000_000, writtenAtNanos = writtenAt, modbusReply = modbusReply)
        } finally {
            if (!answered && key != null) markDirty(key, writtenAt)
        }
    }

    private fun isConfirmedTransactionReply(info: RequestInfo, raw: List<Int>): Boolean {
        if (info !is Command.Custom) return true
        val reply = FrameCodec.decodeSingle(raw) ?: return false
        return Command.isConfirmedReplyFrame(info.code, info.data, reply)
    }

    private fun dropFirst(all: List<Int>, sent: List<Int>): List<Int> {
        val i = indexOf(all, sent)
        return if (i < 0) all else all.subList(0, i) + all.subList(i + sent.size, all.size)
    }

    /** Null when [bytes] may be sent from the terminal, otherwise the reason it is refused. */
    fun validateRaw(bytes: List<Int>, broadcastConfirmed: Boolean = false): String? {
        if (bytes.isEmpty()) return "没有数据"
        if (bytes.size > 300) return "单次最多 300 字节"
        modbusFrameOf(bytes)?.let { req -> return safety.checkModbus(req, broadcastConfirmed)?.let { "MODBUS 帧：$it" } }
        val parser = FrameParser()
        val events = parser.feed(bytes)
        val frames = events.mapNotNull {
            when (it) { is ParseEvent.FrameReceived -> it.frame; is ParseEvent.BadChecksum -> it.frame; else -> null }
        }
        val malformed = parser.pendingBytes.isNotEmpty() || events.any { it is ParseEvent.Garbage }
        if (transport.kind.physical) {
            if (malformed || frames.size != 1) return "真实总线上每次只允许发送 1 个完整帧（私有 DC 02 帧，或 CRC 正确的 MODBUS 请求帧）"
            if (frames[0].direction != Direction.HOST_TO_BOARD) return "真实总线上只允许发送主机方向（DC 02）的私有帧"
        }
        for (f in frames) safety.checkPrivate(f.address, f.command, f.data)?.let { return "帧 [${f.raw.toHex()}]：$it" }
        return null
    }

    private fun markBroken(e: IOException) {
        if (failure == null) {
            failure = e
            log.add(LogKind.ERROR, "链路断开：${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun close() {
        if (failure == null) failure = IOException("已断开")
        runCatching { transport.close() }
    }
}
