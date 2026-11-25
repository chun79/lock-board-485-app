package cn.tuodi.lockboard.core.sim

import cn.tuodi.lockboard.core.client.ByteTransport
import cn.tuodi.lockboard.core.client.Clock
import cn.tuodi.lockboard.core.client.SystemClock
import cn.tuodi.lockboard.core.client.TransportKind
import cn.tuodi.lockboard.core.modbus.Crc16
import cn.tuodi.lockboard.core.modbus.Modbus
import cn.tuodi.lockboard.core.protocol.Cmd
import cn.tuodi.lockboard.core.protocol.Direction
import cn.tuodi.lockboard.core.protocol.Frame
import cn.tuodi.lockboard.core.protocol.FrameCodec
import cn.tuodi.lockboard.core.protocol.FrameParser
import cn.tuodi.lockboard.core.protocol.LockMask
import cn.tuodi.lockboard.core.protocol.ParseEvent
import cn.tuodi.lockboard.core.protocol.Protocol
import cn.tuodi.lockboard.core.protocol.VersionReply
import cn.tuodi.lockboard.core.protocol.toByteArray
import java.io.IOException
import java.util.Random
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class SimBoardConfig(
    val address: Int,
    /** Feedback input not wired: state always reads 0. */
    val noFeedbackChannels: Set<Int> = emptySet(),
    /** Driver dead: pulses do nothing (the board retries, then reports 00). */
    val deadChannels: Set<Int> = emptySet(),
    /** Feedback stuck at "open". */
    val stuckOpenChannels: Set<Int> = emptySet(),
    /** Wiring fault: pulsing key actually opens value. */
    val miswired: Map<Int, Int> = emptyMap(),
    /** Firmware version reported by private 0x01 and MODBUS 0x0001 (defaults to baseline 02 / 0002). */
    val privateVersion: Int = VersionReply.MIN_VERSION,
    val privateReserved: Int = 0,
    val modbusVersion: Int = Modbus.MIN_VERSION,
)

data class SimConfig(
    val boards: List<SimBoardConfig> = listOf(SimBoardConfig(1), SimBoardConfig(2)),
    /** A lock reads open for this long after it opened (door closed again); 0 = until [SimulatedBus.closeDoor]. */
    val autoCloseMs: Int = 3000,
    /** Per attempt: coil energised this long, then [pulseSettleMs] before the feedback is sampled. */
    val pulseOnMs: Int = 300,
    val pulseSettleMs: Int = 50,
    /** Extra attempts of a sequential pulse when the lock did not open. */
    val maxRetries: Int = 3,
    val responseDelayMs: Int = 8,
    val extraDelayMs: Int = 0,
    val dropRate: Double = 0.0,
    val badChecksumRate: Double = 0.0,
    /** Adapter echoes transmitted bytes back (some USB-485 dongles do). */
    val echo: Boolean = false,
    val baud: Int = Protocol.DEFAULT_BAUD,
    val seed: Long = 20261123L,
)

/** Observable state of one simulated board (for UI and tests). */
data class SimBoardState(
    val address: Int,
    val lockOpen: List<Boolean>,
    /** Continuous-output control word (register 0004). */
    val outputOn: List<Boolean>,
    /** Remaining seconds of a channel timer, or null. */
    val timerRemainingMs: List<Long?>,
    /** A pulse task is running. */
    val pulseActive: Boolean,
)

/**
 * Simulated RS485 bus with lock boards running firmware LOCK_12_MODBUS_TIMED_20261006: private protocol
 * and MODBUS RTU on the same line, sharing outputs, timers and the single pulse task.
 */
class SimulatedBus(
    config: SimConfig = SimConfig(),
    private val clock: Clock = SystemClock,
) : ByteTransport {
    override val kind = TransportKind.SIMULATOR
    override val description get() = "模拟总线（板地址 ${boards.keys.sorted().joinToString(",")}）"

    @Volatile
    var config: SimConfig = config
        private set

    private val lock = ReentrantLock()
    private val dataReady = lock.newCondition()
    private val pending = ArrayList<Scheduled>()
    private val available = ArrayDeque<Int>()
    private var boards: Map<Int, SimBoard> = config.boards.associate { it.address to SimBoard(it) }
    private val hostParser = FrameParser()
    private var lastHostByteAt = 0L
    private var random = Random(config.seed)
    private var closed = false
    private val recentHostFrames = ArrayDeque<Frame>()
    private val recentModbusFrames = ArrayDeque<List<Int>>()

    private class Scheduled(val at: Long, val bytes: List<Int>)

    /** The most recent private host frames the bus saw (bounded). */
    val receivedHostFrames: List<Frame> get() = lock.withLock { recentHostFrames.toList() }

    /** The most recent MODBUS request frames the bus saw (bounded). */
    val receivedModbusFrames: List<List<Int>> get() = lock.withLock { recentModbusFrames.toList() }

    fun reconfigure(newConfig: SimConfig) = lock.withLock {
        config = newConfig
        val old = boards
        boards = newConfig.boards.associate { cfg -> cfg.address to (old[cfg.address]?.also { it.config = cfg } ?: SimBoard(cfg)) }
        random = Random(newConfig.seed)
    }

    fun closeDoor(address: Int, channel: Int) = lock.withLock { boards[address]?.closeDoor(channel) }

    fun boardStates(): List<SimBoardState> = lock.withLock {
        val now = clock.nanoTime()
        boards.values.sortedBy { it.config.address }.map { it.snapshot(now) }
    }

    // ------------------------------------------------------------------------------------------
    // ByteTransport

    override fun write(bytes: ByteArray) {
        lock.withLock {
            if (closed) throw IOException("模拟总线已关闭")
            val now = clock.nanoTime()
            val data = bytes.map { it.toInt() and 0xFF }
            if (config.echo) schedule(now, data)
            // The board drops a partial private frame after ~100 ms of silence.
            if (hostParser.pendingBytes.isNotEmpty() && now - lastHostByteAt > 100_000_000L) hostParser.reset()
            if (data.isNotEmpty() && data[0] != Protocol.HEAD && hostParser.pendingBytes.isEmpty()) {
                handleModbus(data, now)
                return
            }
            lastHostByteAt = now
            for (event in hostParser.feed(data)) {
                if (event !is ParseEvent.FrameReceived) continue // bad checksum / garbage: silently ignored
                val frame = event.frame
                if (frame.direction != Direction.HOST_TO_BOARD) continue
                recentHostFrames.addLast(frame)
                while (recentHostFrames.size > MAX_RECORDED) recentHostFrames.removeFirst()
                boards[frame.address]?.handlePrivate(frame, now)
            }
        }
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        lock.lock()
        try {
            val deadline = clock.nanoTime() + timeoutMs * 1_000_000L
            while (true) {
                if (closed) throw IOException("模拟总线已关闭")
                val now = clock.nanoTime()
                promote(now)
                if (available.isNotEmpty()) {
                    var n = 0
                    while (n < buffer.size && available.isNotEmpty()) buffer[n++] = available.removeFirst().toByte()
                    return n
                }
                if (now >= deadline) return 0
                val nextAt = pending.minOfOrNull { it.at } ?: deadline
                dataReady.awaitNanos(minOf(deadline, nextAt) - now)
            }
        } finally {
            lock.unlock()
        }
    }

    override fun drainInput(): ByteArray = lock.withLock {
        promote(clock.nanoTime())
        val out = available.toList().toByteArray()
        available.clear()
        out
    }

    override fun close() = lock.withLock {
        closed = true
        dataReady.signalAll()
    }

    private fun schedule(at: Long, bytes: List<Int>): Scheduled {
        val s = Scheduled(at, bytes)
        pending += s
        dataReady.signalAll()
        return s
    }

    private fun promote(now: Long) {
        val due = pending.filter { it.at <= now }.sortedBy { it.at }
        pending.removeAll(due.toSet())
        due.forEach { available.addAll(it.bytes) }
    }

    /** Applies fault injection and the response delay; returns the scheduled reply (null if dropped). */
    private fun reply(bytes: List<Int>, requestLen: Int, at: Long, crc: Boolean): Scheduled? {
        if (random.nextDouble() < config.dropRate) return null
        var out = bytes
        // Corrupt the last byte: the private SUM or the MODBUS CRC high byte.
        if (random.nextDouble() < config.badChecksumRate) out = out.dropLast(1) + ((out.last() xor 0x5A) and 0xFF)
        val wire = (requestLen + out.size) * 10_000L / config.baud
        return schedule(at + (config.responseDelayMs + config.extraDelayMs + wire) * 1_000_000L, out)
    }

    // ------------------------------------------------------------------------------------------
    // MODBUS RTU

    private fun handleModbus(f: List<Int>, now: Long) {
        if (f.size < 4 || !Crc16.valid(f)) return // too short / CRC error: silently dropped
        recentModbusFrames.addLast(f)
        while (recentModbusFrames.size > MAX_RECORDED) recentModbusFrames.removeFirst()
        val a = f[0]
        if (a == Modbus.BROADCAST) {
            boards.values.forEach { it.handleModbus(f, now, broadcast = true) }
            return
        }
        val resp = boards[a]?.handleModbus(f, now, broadcast = false) ?: return
        reply(Crc16.append(resp), f.size, now, crc = true)
    }

    // ------------------------------------------------------------------------------------------

    private inner class SimBoard(@Volatile var config: SimBoardConfig) {
        private val bus get() = this@SimulatedBus.config
        private val openedAt = LongArray(12) { Long.MIN_VALUE }
        private val manuallyOpen = BooleanArray(12)
        private var continuous = 0
        private val timerDeadline = LongArray(12)
        private var task: PulseTask? = null

        private inner class PulseTask(
            val start: Long,
            val end: Long,
            /** (opened channel, time it opens). */
            val openings: List<Pair<Int, Long>>,
            /** Channels whose feedback read open after their pulse (the board's own success criterion). */
            val succeeded: Set<Int>,
            var reply: Scheduled? = null,
        )

        /** Whether the board's feedback for [ch] will read open after pulsing it. */
        private fun feedbackOpensAfterPulse(ch: Int, now: Long): Boolean {
            if (ch in config.stuckOpenChannels) return true
            if (ch in config.noFeedbackChannels) return false
            val target = config.miswired[ch] ?: ch
            val works = ch !in config.deadChannels && target in 1..12
            return isOpen(ch, now) || (works && target == ch)
        }

        fun closeDoor(ch: Int) {
            if (ch !in 1..12) return
            openedAt[ch - 1] = Long.MIN_VALUE
            manuallyOpen[ch - 1] = false
        }

        private fun advance(now: Long) {
            for (i in 0 until 12) {
                if (timerDeadline[i] != 0L && timerDeadline[i] <= now) {
                    timerDeadline[i] = 0
                    continuous = continuous and (1 shl i).inv()
                }
            }
            val t = task ?: return
            for ((ch, at) in t.openings) if (at <= now && openedAt[ch - 1] < at) { openedAt[ch - 1] = at; manuallyOpen[ch - 1] = true }
            if (t.end <= now) task = null
        }

        private fun isOpen(ch: Int, now: Long): Boolean {
            if (ch in config.stuckOpenChannels) return true
            if (ch in config.noFeedbackChannels) return false
            val at = openedAt[ch - 1]
            if (at == Long.MIN_VALUE || at > now) return false
            return if (bus.autoCloseMs <= 0) manuallyOpen[ch - 1] else now - at < bus.autoCloseMs * 1_000_000L
        }

        fun mask(now: Long): Int {
            advance(now)
            return LockMask.of((1..12).filter { isOpen(it, now) })
        }

        fun snapshot(now: Long): SimBoardState {
            advance(now)
            return SimBoardState(
                config.address,
                (1..12).map { isOpen(it, now) },
                (1..12).map { continuous and (1 shl (it - 1)) != 0 },
                (1..12).map { ch -> timerDeadline[ch - 1].takeIf { it != 0L }?.let { (it - now) / 1_000_000 } },
                task != null,
            )
        }

        /** Starts a pulse task, replacing a running one (its pending openings and reply are withdrawn). */
        private fun startTask(channels: List<Int>, sequential: Boolean, now: Long): PulseTask {
            advance(now)
            task?.let { old -> old.reply?.let { pending.remove(it) } }
            val step = (bus.pulseOnMs + bus.pulseSettleMs) * 1_000_000L
            val openings = ArrayList<Pair<Int, Long>>()
            val succeeded = HashSet<Int>()
            var t = now
            for (ch in channels) {
                val target = config.miswired[ch] ?: ch
                if (ch !in config.deadChannels && target in 1..12) openings += target to (t + bus.pulseOnMs * 1_000_000L)
                val ok = feedbackOpensAfterPulse(ch, now)
                if (ok) succeeded += ch
                // Sequential tasks retry (up to maxRetries) while the board's own feedback is not open.
                if (sequential) t += (if (ok) 1 else 1 + bus.maxRetries) * step
            }
            if (!sequential) t += step
            val newTask = PulseTask(now, t, openings, succeeded)
            task = newTask
            return newTask
        }

        // ---------------- private protocol ----------------

        fun handlePrivate(f: Frame, now: Long) {
            advance(now)
            val d = f.data
            fun send(data: List<Int>, at: Long = now): Scheduled? =
                reply(FrameCodec.encode(config.address, f.command, data, Direction.BOARD_TO_HOST), f.raw.size, at, crc = false)
            when (f.command) {
                Cmd.GET_VERSION -> if (d.isEmpty()) send(listOf(config.privateVersion and 0xFF, config.privateReserved and 0xFF))
                Cmd.READ_ONE -> {
                    val ch = d.singleOrNull()?.takeIf { it in 1..12 } ?: return
                    send(listOf(ch, if (isOpen(ch, now)) 1 else 0))
                }
                Cmd.READ_ALL -> if (d.isEmpty()) send(LockMask.toBytes(mask(now)))
                Cmd.UNLOCK_ONE -> {
                    val ch = d.singleOrNull()?.takeIf { it in 1..12 } ?: return
                    val t = startTask(listOf(ch), sequential = true, now)
                    t.reply = send(listOf(ch, if (ch in t.succeeded) 1 else 0), t.end)
                }
                Cmd.UNLOCK_ALL -> {
                    if (d.isNotEmpty()) return
                    val t = startTask((1..12).toList(), sequential = true, now)
                    t.reply = send(LockMask.toBytes(maskAt(t.end)), t.end)
                }
                Cmd.UNLOCK_MANY, Cmd.UNLOCK_SIMULTANEOUS -> {
                    if (d.size != 2) return
                    val m = LockMask.fromBytes(d[0], d[1])
                    if (m and Protocol.UNUSED_MASK_BITS != 0) return // silently rejected
                    if (m == 0) { send(LockMask.toBytes(mask(now))); return } // legal no-op
                    val t = startTask(LockMask.channels(m), sequential = f.command == Cmd.UNLOCK_MANY, now)
                    t.reply = send(LockMask.toBytes(maskAt(t.end)), t.end)
                }
                Cmd.SET_OUTPUT -> {
                    val ok = when (d.size) {
                        2 -> d[0] in 1..12 && d[1] in 0..1 && setChannel(d[0], d[1] == 1, null, now)
                        4 -> d[0] in 1..12 && d[1] in 0..1 && run {
                            val secs = (d[2] shl 8) or d[3]
                            if (d[1] == 1 && secs == 0) false else setChannel(d[0], d[1] == 1, if (d[1] == 1) secs else null, now)
                        }
                        else -> false
                    }
                    if (ok) send(listOf(1))
                }
                Cmd.SET_OUTPUTS -> {
                    if (d.size != 2) return
                    val m = LockMask.fromBytes(d[0], d[1])
                    if (m and Protocol.UNUSED_MASK_BITS != 0) return
                    setAll(m)
                    send(listOf(1))
                }
                else -> Unit // unsupported: no reply
            }
        }

        /** Feedback mask as it will be when the task completes (openings up to [at] applied). */
        private fun maskAt(at: Long): Int = LockMask.of((1..12).filter { ch ->
            if (ch in config.stuckOpenChannels) return@filter true
            if (ch in config.noFeedbackChannels) return@filter false
            val opened = task?.openings?.filter { it.first == ch && it.second <= at }?.maxOfOrNull { it.second }
            val last = maxOf(openedAt[ch - 1], opened ?: Long.MIN_VALUE)
            last != Long.MIN_VALUE && (bus.autoCloseMs <= 0 || at - last < bus.autoCloseMs * 1_000_000L)
        })

        private fun setChannel(ch: Int, on: Boolean, seconds: Int?, now: Long): Boolean {
            val bit = 1 shl (ch - 1)
            continuous = if (on) continuous or bit else continuous and bit.inv()
            timerDeadline[ch - 1] = if (on && seconds != null) now + seconds * 1_000_000_000L else 0L
            return true
        }

        private fun setAll(mask: Int) {
            continuous = mask
            timerDeadline.fill(0)
        }

        // ---------------- MODBUS RTU ----------------

        /** Returns the response body (without CRC), or null for no response. */
        fun handleModbus(f: List<Int>, now: Long, broadcast: Boolean): List<Int>? {
            advance(now)
            val a = config.address
            val fc = f[1]
            fun exc(code: Int) = if (broadcast) null else listOf(a, fc or 0x80, code)
            fun w(i: Int) = (f[i] shl 8) or f[i + 1]
            when (fc) {
                Modbus.FC_READ_DISCRETE_INPUTS, Modbus.FC_READ_HOLDING_REGISTERS -> {
                    if (broadcast) return null
                    if (f.size != 8) return exc(3)
                    val start = w(2)
                    val qty = w(4)
                    if (fc == Modbus.FC_READ_DISCRETE_INPUTS) {
                        if (qty !in 1..12 || start < 1 || start + qty - 1 > 12) return exc(2)
                        val bits = (mask(now) shr (start - 1)) and ((1 shl qty) - 1)
                        val bc = (qty + 7) / 8
                        return listOf(a, fc, bc) + (0 until bc).map { (bits shr (8 * it)) and 0xFF }
                    }
                    if (qty !in 1..10 || start < 1 || start + qty - 1 > Modbus.LAST_REGISTER) return exc(2)
                    val regs = (start until start + qty).map { reg ->
                        when (reg) {
                            Modbus.REG_VERSION -> config.modbusVersion and 0xFFFF
                            Modbus.REG_FEEDBACK_LO -> mask(now)
                            Modbus.REG_OUTPUT_LO -> continuous
                            else -> 0
                        }
                    }
                    return listOf(a, fc, 2 * qty) + regs.flatMap { listOf((it shr 8) and 0xFF, it and 0xFF) }
                }
                Modbus.FC_WRITE_SINGLE_COIL -> {
                    if (f.size != 8) return exc(3)
                    val coil = w(2)
                    if (coil !in 1..12) return exc(2)
                    if (w(4) != Modbus.COIL_ON) return exc(3)
                    startTask(listOf(coil), sequential = true, now)
                    return if (broadcast) null else f.dropLast(2)
                }
                Modbus.FC_WRITE_SINGLE_REGISTER -> {
                    if (f.size != 8) return exc(3)
                    val reg = w(2)
                    val v = w(4)
                    when (reg) {
                        Modbus.REG_OUTPUT_LO -> { if (v and 0xF000 != 0) return exc(3); setAll(v) }
                        Modbus.REG_OUTPUT_HI -> { if (v != 0) return exc(3); timerDeadline.fill(0) }
                        Modbus.REG_PULSE_LO -> { if (v and 0xF000 != 0) return exc(3); if (v != 0) startTask(LockMask.channels(v), sequential = false, now) }
                        Modbus.REG_PULSE_HI -> if (v != 0) return exc(3)
                        Modbus.REG_VERSION, Modbus.REG_FEEDBACK_LO, Modbus.REG_FEEDBACK_HI -> return exc(3)
                        else -> return exc(2)
                    }
                    return if (broadcast) null else f.dropLast(2)
                }
                Modbus.FC_WRITE_MULTIPLE_COILS -> {
                    if (f.size < 9 || f[6] != f.size - 9) return exc(3)
                    val start = w(2)
                    val qty = w(4)
                    if (qty !in 1..12 || start < 1 || start + qty - 1 > 12) return exc(2)
                    if (f[6] != (qty + 7) / 8) return exc(3)
                    var bits = 0
                    for (i in 0 until f[6]) bits = bits or (f[7 + i] shl (8 * i))
                    bits = bits and ((1 shl qty) - 1)
                    if (bits == 0) return exc(3)
                    startTask((0 until qty).filter { bits and (1 shl it) != 0 }.map { start + it }, sequential = true, now)
                    return if (broadcast) null else f.take(6)
                }
                Modbus.FC_WRITE_MULTIPLE_REGISTERS -> {
                    if (f.size < 9 || f[6] != f.size - 9) return exc(3)
                    val start = w(2)
                    val qty = w(4)
                    if (f[6] != 2 * qty || qty == 0) return exc(3)
                    val values = (0 until qty).map { w(7 + 2 * it) }
                    if (start == Modbus.REG_SINGLE_CHANNEL) {
                        if (qty !in 2..3) return exc(2)
                        val ch = values[0]
                        val st = values[1]
                        if (ch !in 1..12) return exc(2)
                        if (st !in 0..1) return exc(3)
                        if (qty == 3 && st == 1 && values[2] == 0) return exc(3)
                        setChannel(ch, st == 1, if (st == 1 && qty == 3) values[2] else null, now)
                        return if (broadcast) null else f.take(6)
                    }
                    val end = start + qty - 1
                    if (start < Modbus.REG_OUTPUT_LO || end > Modbus.REG_PULSE_HI) return exc(2)
                    val writes = (start..end).zip(values).toMap()
                    writes[Modbus.REG_OUTPUT_LO]?.let { if (it and 0xF000 != 0) return exc(3) }
                    writes[Modbus.REG_OUTPUT_HI]?.let { if (it != 0) return exc(3) }
                    writes[Modbus.REG_PULSE_LO]?.let { if (it and 0xF000 != 0) return exc(3) }
                    writes[Modbus.REG_PULSE_HI]?.let { if (it != 0) return exc(3) }
                    if (Modbus.REG_OUTPUT_LO in writes || Modbus.REG_OUTPUT_HI in writes) {
                        timerDeadline.fill(0)
                        writes[Modbus.REG_OUTPUT_LO]?.let { continuous = it }
                    }
                    writes[Modbus.REG_PULSE_LO]?.takeIf { it != 0 }?.let { startTask(LockMask.channels(it), sequential = false, now) }
                    return if (broadcast) null else f.take(6)
                }
                else -> return exc(1)
            }
        }
    }

    private companion object {
        const val MAX_RECORDED = 2000
    }
}
