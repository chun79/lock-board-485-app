package cn.tuodi.lockboard.core.config

import cn.tuodi.lockboard.core.client.ClientSettings
import cn.tuodi.lockboard.core.protocol.Protocol
import cn.tuodi.lockboard.core.sim.SimBoardConfig
import cn.tuodi.lockboard.core.sim.SimConfig
import cn.tuodi.lockboard.core.testing.TestPlan
import org.json.JSONArray
import org.json.JSONObject

enum class TransportType(val label: String) {
    SIMULATOR("模拟器"),
    USB("USB 转 485"),
    UART("板载串口"),
    TCP("TCP 串口服务器"),
}

enum class Parity(val label: String) { NONE("无"), ODD("奇"), EVEN("偶") }

/** Protocol default is 9600 8N1; other values only for non-standard firmware or adapters. */
data class SerialParams(
    val baud: Int = Protocol.DEFAULT_BAUD,
    val dataBits: Int = 8,
    val parity: Parity = Parity.NONE,
    val stopBits: Int = 1,
) {
    val isProtocolDefault get() = this == SerialParams()
    fun describe() = "$baud ${dataBits}${parity.name.first()}$stopBits"
}

data class ConnectionConfig(
    val type: TransportType = TransportType.SIMULATOR,
    /** UsbDevice.deviceName of the last used adapter (informational; re-selected on each connect). */
    val usbDeviceName: String = "",
    val uartPath: String = "/dev/ttyS1",
    /** Enable kernel RS485 mode (TIOCSRS485, RTS on send) for UARTs without auto-direction. */
    val uartRs485Ioctl: Boolean = false,
    val tcpHost: String = "192.168.1.200",
    val tcpPort: Int = 8899,
)

data class AppConfig(
    val connection: ConnectionConfig = ConnectionConfig(),
    val serial: SerialParams = SerialParams(),
    val client: ClientSettings = ClientSettings(),
    val plan: TestPlan = TestPlan(),
    val sim: SimConfig = SimConfig(),
    val boards: Map<Int, BoardProfile> = emptyMap(),
    val pollIntervalMs: Int = 1000,
    val scanTimeoutMs: Int = 200,
    val enduranceCycles: Int = 100,
    val enduranceIntervalMs: Int = 2000,
    /** Longest timed output (0x16 LEN=4 / MODBUS 0008) allowed on a LOCK channel; 0 = never. */
    val maxLockTimedSeconds: Int = 0,
) {
    fun profile(address: Int): BoardProfile = boards[address] ?: BoardProfile.default(address)

    fun withProfile(profile: BoardProfile) = copy(boards = boards + (profile.address to profile))

    /** Client settings with the serial baud rate applied (used for wire-time estimates). */
    fun effectiveClientSettings() = client.copy(baud = serial.baud).validated()
}

/** JSON (org.json) persistence. Missing keys fall back to defaults so old files keep loading. */
object ConfigJson {
    fun toJson(c: AppConfig): JSONObject = JSONObject().apply {
        put("version", 1)
        put("connection", JSONObject().apply {
            put("type", c.connection.type.name)
            put("usbDeviceName", c.connection.usbDeviceName)
            put("uartPath", c.connection.uartPath)
            put("uartRs485Ioctl", c.connection.uartRs485Ioctl)
            put("tcpHost", c.connection.tcpHost)
            put("tcpPort", c.connection.tcpPort)
        })
        put("serial", JSONObject().apply {
            put("baud", c.serial.baud)
            put("dataBits", c.serial.dataBits)
            put("parity", c.serial.parity.name)
            put("stopBits", c.serial.stopBits)
        })
        put("client", JSONObject().apply {
            put("readTimeoutMs", c.client.readTimeoutMs)
            put("readRetries", c.client.readRetries)
            put("perLockPulseMaxMs", c.client.perLockPulseMaxMs)
            put("simultaneousPulseMs", c.client.simultaneousPulseMs)
            put("actionMarginMs", c.client.actionMarginMs)
            put("interFrameGapMs", c.client.interFrameGapMs)
            put("lateReplyGuardMs", c.client.lateReplyGuardMs)
            put("interByteTimeoutMs", c.client.interByteTimeoutMs)
            put("lateReplyMaxAgeMs", c.client.lateReplyMaxAgeMs)
            put("discardTxEcho", c.client.discardTxEcho)
        })
        put("plan", planToJson(c.plan))
        put("sim", simToJson(c.sim))
        put("boards", JSONArray(c.boards.values.sortedBy { it.address }.map { profileToJson(it) }))
        put("pollIntervalMs", c.pollIntervalMs)
        put("scanTimeoutMs", c.scanTimeoutMs)
        put("enduranceCycles", c.enduranceCycles)
        put("enduranceIntervalMs", c.enduranceIntervalMs)
        put("maxLockTimedSeconds", c.maxLockTimedSeconds)
    }

    fun fromJson(o: JSONObject): AppConfig {
        val d = AppConfig()
        val conn = o.optJSONObject("connection")
        val serial = o.optJSONObject("serial")
        val client = o.optJSONObject("client")
        return AppConfig(
            connection = if (conn == null) d.connection else ConnectionConfig(
                type = enumOr(conn.optString("type"), d.connection.type),
                usbDeviceName = conn.optString("usbDeviceName", d.connection.usbDeviceName),
                uartPath = conn.optString("uartPath", d.connection.uartPath),
                uartRs485Ioctl = conn.optBoolean("uartRs485Ioctl", d.connection.uartRs485Ioctl),
                tcpHost = conn.optString("tcpHost", d.connection.tcpHost),
                tcpPort = conn.optInt("tcpPort", d.connection.tcpPort),
            ),
            serial = if (serial == null) d.serial else SerialParams(
                baud = serial.optInt("baud", d.serial.baud),
                dataBits = serial.optInt("dataBits", d.serial.dataBits).coerceIn(5, 8),
                parity = enumOr(serial.optString("parity"), d.serial.parity),
                stopBits = serial.optInt("stopBits", d.serial.stopBits).coerceIn(1, 2),
            ),
            client = if (client == null) d.client else ClientSettings(
                readTimeoutMs = client.optInt("readTimeoutMs", d.client.readTimeoutMs),
                readRetries = client.optInt("readRetries", d.client.readRetries),
                perLockPulseMaxMs = client.optInt("perLockPulseMaxMs", d.client.perLockPulseMaxMs),
                simultaneousPulseMs = client.optInt("simultaneousPulseMs", d.client.simultaneousPulseMs),
                actionMarginMs = client.optInt("actionMarginMs", d.client.actionMarginMs),
                interFrameGapMs = client.optInt("interFrameGapMs", d.client.interFrameGapMs),
                lateReplyGuardMs = client.optInt("lateReplyGuardMs", d.client.lateReplyGuardMs),
                interByteTimeoutMs = client.optInt("interByteTimeoutMs", d.client.interByteTimeoutMs),
                lateReplyMaxAgeMs = client.optInt("lateReplyMaxAgeMs", d.client.lateReplyMaxAgeMs),
                discardTxEcho = client.optBoolean("discardTxEcho", d.client.discardTxEcho),
            ).validated(),
            plan = o.optJSONObject("plan")?.let { planFromJson(it) } ?: d.plan,
            sim = o.optJSONObject("sim")?.let { simFromJson(it) } ?: d.sim,
            boards = o.optJSONArray("boards")?.let { arr ->
                (0 until arr.length()).mapNotNull { runCatching { profileFromJson(arr.getJSONObject(it)) }.getOrNull() }
                    .associateBy { it.address }
            } ?: d.boards,
            pollIntervalMs = o.optInt("pollIntervalMs", d.pollIntervalMs).coerceIn(200, 60_000),
            scanTimeoutMs = o.optInt("scanTimeoutMs", d.scanTimeoutMs).coerceIn(50, 5_000),
            enduranceCycles = o.optInt("enduranceCycles", d.enduranceCycles).coerceIn(1, 1_000_000),
            enduranceIntervalMs = o.optInt("enduranceIntervalMs", d.enduranceIntervalMs).coerceIn(0, 3_600_000),
            maxLockTimedSeconds = o.optInt("maxLockTimedSeconds", d.maxLockTimedSeconds).coerceIn(0, 60),
        )
    }

    fun encode(c: AppConfig): String = toJson(c).toString(2)

    fun decode(text: String): AppConfig = fromJson(JSONObject(text))

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private fun ints(arr: JSONArray?): List<Int> = arr?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList()

    fun profileToJson(p: BoardProfile) = JSONObject().apply {
        put("address", p.address)
        put("name", p.name)
        put("channels", JSONArray(p.channels.map { ch ->
            JSONObject().put("type", ch.type.name).put("label", ch.label).put("feedback", ch.feedback)
        }))
    }

    fun profileFromJson(o: JSONObject): BoardProfile {
        val address = o.getInt("address")
        val arr = o.optJSONArray("channels")
        val channels = List(Protocol.CHANNELS) { i ->
            val c = arr?.optJSONObject(i)
            if (c == null) ChannelProfile() else ChannelProfile(
                type = enumOr(c.optString("type"), ChannelType.LOCK),
                label = c.optString("label", ""),
                feedback = c.optBoolean("feedback", true),
            )
        }
        return BoardProfile(address, o.optString("name", ""), channels)
    }

    private val planBooleans: List<Pair<String, (TestPlan) -> Boolean>> = listOf(
        "communication" to { it.communication }, "consistency" to { it.consistency }, "robustness" to { it.robustness },
        "modbusExceptions" to { it.modbusExceptions }, "stress" to { it.stress }, "unlockEach" to { it.unlockEach },
        "modbusUnlockEach" to { it.modbusUnlockEach }, "unlockMany" to { it.unlockMany }, "modbusUnlockMany" to { it.modbusUnlockMany },
        "unlockSimultaneous" to { it.unlockSimultaneous }, "modbusSimultaneous" to { it.modbusSimultaneous }, "unlockAll" to { it.unlockAll },
        "allowPulseOnOutputs" to { it.allowPulseOnOutputs }, "outputSingle" to { it.outputSingle }, "outputMulti" to { it.outputMulti },
        "outputTimed" to { it.outputTimed }, "modbusOutputs" to { it.modbusOutputs }, "crossProtocolTimers" to { it.crossProtocolTimers },
        "manualConfirm" to { it.manualConfirm }, "requireFeedback" to { it.requireFeedback },
    )

    private fun planToJson(p: TestPlan) = JSONObject().apply {
        planBooleans.forEach { (k, f) -> put(k, f(p)) }
        put("communicationCount", p.communicationCount); put("stressCount", p.stressCount)
        put("simultaneousChannels", JSONArray(p.simultaneousChannels))
        put("timedSeconds", p.timedSeconds); put("timerToleranceMs", p.timerToleranceMs)
        put("minSuccessRate", p.minSuccessRate); put("stressMinSuccessRate", p.stressMinSuccessRate)
        put("settleMs", p.settleMs); put("unlockIntervalMs", p.unlockIntervalMs); put("waitClosedMs", p.waitClosedMs)
    }

    private fun planFromJson(o: JSONObject): TestPlan {
        val d = TestPlan()
        fun b(k: String, def: Boolean) = o.optBoolean(k, def)
        return TestPlan(
            communication = b("communication", d.communication),
            communicationCount = o.optInt("communicationCount", d.communicationCount),
            consistency = b("consistency", d.consistency),
            robustness = b("robustness", d.robustness),
            modbusExceptions = b("modbusExceptions", d.modbusExceptions),
            stress = b("stress", d.stress),
            stressCount = o.optInt("stressCount", d.stressCount),
            unlockEach = b("unlockEach", d.unlockEach),
            modbusUnlockEach = b("modbusUnlockEach", d.modbusUnlockEach),
            unlockMany = b("unlockMany", d.unlockMany),
            modbusUnlockMany = b("modbusUnlockMany", d.modbusUnlockMany),
            unlockSimultaneous = b("unlockSimultaneous", d.unlockSimultaneous),
            modbusSimultaneous = b("modbusSimultaneous", d.modbusSimultaneous),
            simultaneousChannels = ints(o.optJSONArray("simultaneousChannels")),
            unlockAll = b("unlockAll", d.unlockAll),
            allowPulseOnOutputs = b("allowPulseOnOutputs", d.allowPulseOnOutputs),
            outputSingle = b("outputSingle", d.outputSingle),
            outputMulti = b("outputMulti", d.outputMulti),
            outputTimed = b("outputTimed", d.outputTimed),
            modbusOutputs = b("modbusOutputs", d.modbusOutputs),
            crossProtocolTimers = b("crossProtocolTimers", d.crossProtocolTimers),
            timedSeconds = o.optInt("timedSeconds", d.timedSeconds),
            timerToleranceMs = o.optInt("timerToleranceMs", d.timerToleranceMs),
            manualConfirm = b("manualConfirm", d.manualConfirm),
            requireFeedback = b("requireFeedback", d.requireFeedback),
            minSuccessRate = o.optDouble("minSuccessRate", d.minSuccessRate),
            stressMinSuccessRate = o.optDouble("stressMinSuccessRate", d.stressMinSuccessRate),
            settleMs = o.optInt("settleMs", d.settleMs),
            unlockIntervalMs = o.optInt("unlockIntervalMs", d.unlockIntervalMs),
            waitClosedMs = o.optInt("waitClosedMs", d.waitClosedMs),
        ).validated()
    }

    private fun simToJson(s: SimConfig) = JSONObject().apply {
        put("boards", JSONArray(s.boards.map { b ->
            JSONObject().apply {
                put("address", b.address)
                put("noFeedbackChannels", JSONArray(b.noFeedbackChannels.sorted()))
                put("deadChannels", JSONArray(b.deadChannels.sorted()))
                put("stuckOpenChannels", JSONArray(b.stuckOpenChannels.sorted()))
                put("miswired", JSONObject(b.miswired.mapKeys { it.key.toString() }))
            }
        }))
        put("autoCloseMs", s.autoCloseMs)
        put("pulseOnMs", s.pulseOnMs)
        put("pulseSettleMs", s.pulseSettleMs)
        put("maxRetries", s.maxRetries)
        put("responseDelayMs", s.responseDelayMs)
        put("extraDelayMs", s.extraDelayMs)
        put("dropRate", s.dropRate)
        put("badChecksumRate", s.badChecksumRate)
        put("echo", s.echo)
    }

    private fun simFromJson(o: JSONObject): SimConfig {
        val d = SimConfig()
        val boards = o.optJSONArray("boards")?.let { arr ->
            (0 until arr.length()).map { i ->
                val b = arr.getJSONObject(i)
                val mis = b.optJSONObject("miswired")
                SimBoardConfig(
                    address = b.getInt("address").coerceIn(0, 255),
                    noFeedbackChannels = ints(b.optJSONArray("noFeedbackChannels")).toSet(),
                    deadChannels = ints(b.optJSONArray("deadChannels")).toSet(),
                    stuckOpenChannels = ints(b.optJSONArray("stuckOpenChannels")).toSet(),
                    miswired = mis?.keys()?.asSequence()?.associate { it.toInt() to mis.getInt(it) } ?: emptyMap(),
                )
            }.distinctBy { it.address }
        } ?: d.boards
        return SimConfig(
            boards = boards,
            autoCloseMs = o.optInt("autoCloseMs", d.autoCloseMs).coerceIn(0, 600_000),
            pulseOnMs = o.optInt("pulseOnMs", d.pulseOnMs).coerceIn(1, 10_000),
            pulseSettleMs = o.optInt("pulseSettleMs", d.pulseSettleMs).coerceIn(0, 10_000),
            maxRetries = o.optInt("maxRetries", d.maxRetries).coerceIn(0, 10),
            responseDelayMs = o.optInt("responseDelayMs", d.responseDelayMs).coerceIn(0, 10_000),
            extraDelayMs = o.optInt("extraDelayMs", d.extraDelayMs).coerceIn(0, 10_000),
            dropRate = o.optDouble("dropRate", d.dropRate).coerceIn(0.0, 1.0),
            badChecksumRate = o.optDouble("badChecksumRate", d.badChecksumRate).coerceIn(0.0, 1.0),
            echo = o.optBoolean("echo", d.echo),
        )
    }
}
