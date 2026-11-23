package cn.tuodi.lockboard.core.modbus

import cn.tuodi.lockboard.core.protocol.LockMask
import cn.tuodi.lockboard.core.protocol.Protocol
import cn.tuodi.lockboard.core.protocol.Reply
import cn.tuodi.lockboard.core.protocol.RequestInfo
import cn.tuodi.lockboard.core.protocol.hex2
import cn.tuodi.lockboard.core.protocol.toHex

/** MODBUS RTU map of the 12-channel lock board (docs/12路锁控板MODBUS-RTU协议-20261006.pdf). */
object Modbus {
    const val BROADCAST = 0

    const val FC_READ_DISCRETE_INPUTS = 0x02
    const val FC_READ_HOLDING_REGISTERS = 0x03
    const val FC_WRITE_SINGLE_COIL = 0x05
    const val FC_WRITE_SINGLE_REGISTER = 0x06
    const val FC_WRITE_MULTIPLE_COILS = 0x0F
    const val FC_WRITE_MULTIPLE_REGISTERS = 0x10

    /** Discrete inputs / coils 0001..000C = locks 1..12 (on-wire value, no -1). */
    const val FIRST_POINT = 0x0001
    const val LAST_POINT = 0x000C

    const val REG_VERSION = 0x0001
    const val REG_FEEDBACK_LO = 0x0002
    const val REG_FEEDBACK_HI = 0x0003
    const val REG_OUTPUT_LO = 0x0004
    const val REG_OUTPUT_HI = 0x0005
    const val REG_PULSE_LO = 0x0006
    const val REG_PULSE_HI = 0x0007
    const val REG_SINGLE_CHANNEL = 0x0008
    const val REG_SINGLE_STATE = 0x0009
    const val REG_SINGLE_SECONDS = 0x000A
    const val LAST_REGISTER = 0x000A

    const val EXPECTED_VERSION = 0x0002
    const val COIL_ON = 0xFF00

    fun describeFunction(fc: Int): String = when (fc and 0x7F) {
        FC_READ_DISCRETE_INPUTS -> "读离散输入"
        FC_READ_HOLDING_REGISTERS -> "读保持寄存器"
        FC_WRITE_SINGLE_COIL -> "写单线圈"
        FC_WRITE_SINGLE_REGISTER -> "写单寄存器"
        FC_WRITE_MULTIPLE_COILS -> "写多线圈"
        FC_WRITE_MULTIPLE_REGISTERS -> "写多寄存器"
        else -> "功能码 0x${(fc and 0x7F).hex2()}"
    }

    fun describeException(code: Int): String = when (code) {
        1 -> "01 不支持的功能码"
        2 -> "02 锁号/地址/数量超范围或命令块不完整"
        3 -> "03 非法状态/数值、保留位非零、开启时间为零、长度错误或写只读寄存器"
        else -> "${code.hex2()} 未定义异常"
    }
}

object Crc16 {
    /** MODBUS CRC16: init 0xFFFF, reflected polynomial 0xA001. */
    fun of(bytes: List<Int>, from: Int = 0, to: Int = bytes.size): Int {
        var crc = 0xFFFF
        for (i in from until to) {
            crc = crc xor (bytes[i] and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc
    }

    /** Appends the CRC, low byte first. */
    fun append(body: List<Int>): List<Int> {
        val c = of(body)
        return body + (c and 0xFF) + ((c ushr 8) and 0xFF)
    }

    fun valid(frame: List<Int>): Boolean =
        frame.size >= 4 && of(frame, 0, frame.size - 2) == (frame[frame.size - 2] or (frame[frame.size - 1] shl 8))
}

private fun u16(v: Int): List<Int> {
    require(v in 0..0xFFFF) { "16 位数值超出范围：$v" }
    return listOf((v ushr 8) and 0xFF, v and 0xFF)
}

class ModbusProtocolException(message: String) : Exception(message)

/** How the client paces and times a MODBUS request. */
enum class ModbusKind {
    READ,

    /** Starts a background pulse task (05, 0F, 06/10 writing 0006): replies immediately. */
    PULSE,

    /** Writes the continuous/timed output control (0004/0005 or 0008..000A). */
    OUTPUT,

    OTHER,
}

/**
 * A MODBUS RTU request. Values are deliberately not range-checked against the document here so that
 * the exception tests can send illegal requests; [docViolation] tells whether the request is legal.
 */
sealed class ModbusRequest(override val address: Int, val function: Int) : RequestInfo {
    init {
        require(address in 0..0xFF) { "地址超出范围" }
    }

    /** Address .. last data byte (CRC excluded). */
    abstract val body: List<Int>
    abstract override val title: String
    abstract val kind: ModbusKind

    fun encode(): List<Int> = Crc16.append(body)

    val isBroadcast get() = address == Modbus.BROADCAST

    /** Length of a normal response, or null when the function code is not one of the six supported. */
    abstract val normalResponseLength: Int?

    /** Null when the request is legal per the document, otherwise why the board will reject it. */
    abstract fun docViolation(): String?

    /** Locks that this request pulses (for timing). */
    open fun pulsedChannels(): List<Int> = emptyList()

    /** Decodes a CRC-valid response frame whose address and (non-exception) function match. */
    abstract fun decodeNormal(frame: List<Int>): ModbusReply

    protected fun requireEcho(frame: List<Int>, n: Int): ModbusReply {
        val sent = encode()
        if (frame.take(n) != sent.take(n)) throw ModbusProtocolException("回复与请求不一致：${frame.toHex()}")
        return ModbusReply.WriteAck(frame)
    }

    class ReadDiscreteInputs(address: Int, val start: Int = Modbus.FIRST_POINT, val quantity: Int = 12) :
        ModbusRequest(address, Modbus.FC_READ_DISCRETE_INPUTS) {
        override val body = listOf(address, function) + u16(start) + u16(quantity)
        override val title = "02 读反馈 ${start}…${start + quantity - 1}"
        override val kind = ModbusKind.READ
        override val normalResponseLength get() = 3 + byteCount + 2
        val byteCount get() = (quantity + 7) / 8
        override fun docViolation(): String? = when {
            quantity !in 1..12 -> "数量须为 1～12"
            start < Modbus.FIRST_POINT || start + quantity - 1 > Modbus.LAST_POINT -> "起始地址加数量减 1 不得超过 000C"
            else -> null
        }
        override fun decodeNormal(frame: List<Int>): ModbusReply {
            if (frame[2] != byteCount) throw ModbusProtocolException("字节数 ${frame[2]}，期望 $byteCount")
            var bits = 0
            for (i in 0 until byteCount) bits = bits or (frame[3 + i] shl (8 * i))
            val unused = bits and ((1 shl quantity) - 1).inv()
            if (unused != 0) throw ModbusProtocolException("末字节未使用位不为 0")
            return ModbusReply.Bits(start, quantity, bits)
        }
    }

    class ReadHoldingRegisters(address: Int, val start: Int, val quantity: Int) :
        ModbusRequest(address, Modbus.FC_READ_HOLDING_REGISTERS) {
        override val body = listOf(address, function) + u16(start) + u16(quantity)
        override val title = "03 读寄存器 ${"%04X".format(start)}×$quantity"
        override val kind = ModbusKind.READ
        override val normalResponseLength get() = 3 + 2 * quantity + 2
        override fun docViolation(): String? = when {
            quantity !in 1..10 -> "数量须为 1～10"
            start < 1 || start + quantity - 1 > Modbus.LAST_REGISTER -> "起始地址 0001～000A，末地址不得超过 000A"
            else -> null
        }
        override fun decodeNormal(frame: List<Int>): ModbusReply {
            if (frame[2] != 2 * quantity) throw ModbusProtocolException("字节数 ${frame[2]}，期望 ${2 * quantity}")
            return ModbusReply.Registers(start, (0 until quantity).map { (frame[3 + 2 * it] shl 8) or frame[4 + 2 * it] })
        }
    }

    /** 05: only FF00 (pulse) is legal; 0000 is sent only by the exception test. */
    class WriteSingleCoil(address: Int, val coil: Int, val value: Int = Modbus.COIL_ON) :
        ModbusRequest(address, Modbus.FC_WRITE_SINGLE_COIL) {
        override val body = listOf(address, function) + u16(coil) + u16(value)
        override val title = "05 脉冲开锁 #$coil" + if (value != Modbus.COIL_ON) "（值 ${"%04X".format(value)}）" else ""
        override val kind = ModbusKind.PULSE
        override val normalResponseLength = 8
        override fun docViolation(): String? = when {
            coil !in Modbus.FIRST_POINT..Modbus.LAST_POINT -> "线圈 0001～000C"
            value != Modbus.COIL_ON -> "只接受 FF00"
            else -> null
        }
        override fun pulsedChannels() = if (docViolation() == null) listOf(coil) else emptyList()
        override fun decodeNormal(frame: List<Int>) = requireEcho(frame, 6)
    }

    class WriteSingleRegister(address: Int, val register: Int, val value: Int) :
        ModbusRequest(address, Modbus.FC_WRITE_SINGLE_REGISTER) {
        override val body = listOf(address, function) + u16(register) + u16(value)
        override val title = "06 写 ${"%04X".format(register)}=${"%04X".format(value)}"
        override val kind = when (register) {
            Modbus.REG_PULSE_LO, Modbus.REG_PULSE_HI -> ModbusKind.PULSE
            Modbus.REG_OUTPUT_LO, Modbus.REG_OUTPUT_HI -> ModbusKind.OUTPUT
            else -> ModbusKind.OTHER
        }
        override val normalResponseLength = 8
        override fun docViolation(): String? = when (register) {
            Modbus.REG_OUTPUT_LO, Modbus.REG_PULSE_LO -> if (value and 0xF000 != 0) "范围 0000～0FFF" else null
            Modbus.REG_OUTPUT_HI, Modbus.REG_PULSE_HI -> if (value != 0) "保留高半字仅接受 0000" else null
            else -> "06 仅可写 0004～0007"
        }
        override fun pulsedChannels() =
            if (register == Modbus.REG_PULSE_LO && docViolation() == null) LockMask.channels(value) else emptyList()
        override fun decodeNormal(frame: List<Int>) = requireEcho(frame, 6)
    }

    /** 0F: [bits] bit0 = lock [start]. */
    class WriteMultipleCoils(address: Int, val start: Int, val quantity: Int, val bits: Int) :
        ModbusRequest(address, Modbus.FC_WRITE_MULTIPLE_COILS) {
        val byteCount get() = (quantity + 7) / 8
        override val body = listOf(address, function) + u16(start) + u16(quantity) + byteCount +
            (0 until byteCount).map { (bits ushr (8 * it)) and 0xFF }
        override val title = "0F 逐路脉冲 起始 #$start ×$quantity [${channels().joinToString(",")}]"
        override val kind = ModbusKind.PULSE
        override val normalResponseLength = 8
        fun channels(): List<Int> = (0 until quantity).filter { bits and (1 shl it) != 0 }.map { start + it }
        override fun docViolation(): String? = when {
            quantity !in 1..12 -> "数量须为 1～12"
            start < Modbus.FIRST_POINT || start + quantity - 1 > Modbus.LAST_POINT -> "超出 0001～000C"
            bits and ((1 shl quantity) - 1) == 0 -> "全零掩码返回异常 03"
            else -> null
        }
        override fun pulsedChannels() = if (docViolation() == null) channels() else emptyList()
        override fun decodeNormal(frame: List<Int>) = requireEcho(frame, 6)
    }

    class WriteMultipleRegisters(address: Int, val start: Int, val values: List<Int>) :
        ModbusRequest(address, Modbus.FC_WRITE_MULTIPLE_REGISTERS) {
        override val body = listOf(address, function) + u16(start) + u16(values.size) + (2 * values.size) + values.flatMap { u16(it) }
        override val title = "10 写 ${"%04X".format(start)}×${values.size} [${values.joinToString(" ") { "%04X".format(it) }}]"
        override val kind = when {
            start == Modbus.REG_SINGLE_CHANNEL -> ModbusKind.OUTPUT
            start <= Modbus.REG_OUTPUT_HI && start + values.size - 1 >= Modbus.REG_OUTPUT_LO -> ModbusKind.OUTPUT
            else -> ModbusKind.PULSE
        }
        override val normalResponseLength = 8

        /** Register → value map of this write. */
        fun writes(): Map<Int, Int> = values.indices.associate { start + it to values[it] }

        override fun docViolation(): String? {
            if (values.isEmpty()) return "至少写 1 个寄存器"
            if (start == Modbus.REG_SINGLE_CHANNEL) {
                if (values.size !in 2..3) return "0008 命令块必须写 2 或 3 个寄存器"
                if (values[0] !in 1..12) return "锁号 0001～000C"
                if (values[1] !in 0..1) return "状态 0000/0001"
                if (values.size == 3 && values[1] == 1 && values[2] == 0) return "开启时秒数不得为 0"
                return null
            }
            val end = start + values.size - 1
            if (start < Modbus.REG_OUTPUT_LO || end > Modbus.REG_PULSE_HI) return "位图须写 0004～0007 内的 1～4 个连续寄存器"
            for ((reg, v) in writes()) {
                when (reg) {
                    Modbus.REG_OUTPUT_LO, Modbus.REG_PULSE_LO -> if (v and 0xF000 != 0) return "${"%04X".format(reg)} 范围 0000～0FFF"
                    else -> if (v != 0) return "${"%04X".format(reg)} 为保留高半字，仅接受 0000"
                }
            }
            return null
        }

        override fun pulsedChannels(): List<Int> =
            if (docViolation() == null) writes()[Modbus.REG_PULSE_LO]?.let { LockMask.channels(it) } ?: emptyList() else emptyList()

        override fun decodeNormal(frame: List<Int>) = requireEcho(frame, 6)
    }

    /** Terminal: arbitrary function and payload; the response length is inferred from the function code. */
    class Custom(address: Int, function: Int, val payload: List<Int>) : ModbusRequest(address, function) {
        override val body = listOf(address, function) + payload
        override val title = "MODBUS 自定义 ${function.hex2()}"
        override val kind = ModbusKind.OTHER
        override val normalResponseLength: Int? = when (function) {
            Modbus.FC_WRITE_SINGLE_COIL, Modbus.FC_WRITE_SINGLE_REGISTER, Modbus.FC_WRITE_MULTIPLE_COILS, Modbus.FC_WRITE_MULTIPLE_REGISTERS -> 8
            else -> null // 02/03 lengths come from the byte-count field
        }
        override fun docViolation(): String? = null
        override fun decodeNormal(frame: List<Int>) = ModbusReply.Raw(frame)
    }

    companion object {
        /** Builds the typed request represented by a complete CRC-valid request frame (terminal decoding). */
        fun fromFrame(frame: List<Int>): ModbusRequest? {
            if (frame.size < 4 || !Crc16.valid(frame)) return null
            val a = frame[0]
            val fc = frame[1]
            val d = frame.subList(2, frame.size - 2)
            fun w(i: Int) = (d[i] shl 8) or d[i + 1]
            return when {
                fc == Modbus.FC_READ_DISCRETE_INPUTS && d.size == 4 -> ReadDiscreteInputs(a, w(0), w(2))
                fc == Modbus.FC_READ_HOLDING_REGISTERS && d.size == 4 -> ReadHoldingRegisters(a, w(0), w(2))
                fc == Modbus.FC_WRITE_SINGLE_COIL && d.size == 4 -> WriteSingleCoil(a, w(0), w(2))
                fc == Modbus.FC_WRITE_SINGLE_REGISTER && d.size == 4 -> WriteSingleRegister(a, w(0), w(2))
                fc == Modbus.FC_WRITE_MULTIPLE_COILS && d.size >= 5 && d[4] == d.size - 5 && d[4] == (w(2) + 7) / 8 -> {
                    var bits = 0
                    for (i in 0 until d[4]) bits = bits or (d[5 + i] shl (8 * i))
                    WriteMultipleCoils(a, w(0), w(2), bits)
                }
                fc == Modbus.FC_WRITE_MULTIPLE_REGISTERS && d.size >= 5 && d[4] == d.size - 5 && d[4] == 2 * w(2) ->
                    WriteMultipleRegisters(a, w(0), (0 until w(2)).map { w(5 + 2 * it) })
                else -> Custom(a, fc, d)
            }
        }
    }
}

sealed interface ModbusReply : Reply {

    /** 02: [bits] bit0 = lock [start]. */
    data class Bits(val start: Int, val quantity: Int, val bits: Int) : ModbusReply {
        /** As a 12-bit lock mask (bit0 = lock 1). */
        val lockMask: Int get() = (bits shl (start - 1)) and Protocol.ALL_CHANNELS_MASK
        override fun summary() = "反馈 [${LockMask.describe(lockMask)}]"
    }

    data class Registers(val start: Int, val values: List<Int>) : ModbusReply {
        fun value(register: Int): Int? = values.getOrNull(register - start)
        override fun summary() = values.mapIndexed { i, v -> "${"%04X".format(start + i)}=${"%04X".format(v)}" }.joinToString(" ")
    }

    data class WriteAck(val frame: List<Int>) : ModbusReply {
        override fun summary() = "已受理"
    }

    data class Exception(val function: Int, val code: Int) : ModbusReply {
        override fun summary() = "异常 ${Modbus.describeException(code)}"
    }

    data class Raw(val frame: List<Int>) : ModbusReply {
        override fun summary() = "回复 [${frame.toHex()}]"
    }

    /** Broadcast write: executed by every board, never answered. */
    data object BroadcastSent : ModbusReply {
        override fun summary() = "广播已发送（不回复）"
    }
}

/**
 * Finds a response to [request] in a byte buffer: the first index where address and function (or its
 * exception code) match, the length implied by the function code fits, and the CRC is valid.
 */
object ModbusResponseLocator {
    data class Match(val start: Int, val frame: List<Int>)

    /** Returns the match, or null when no complete valid response is in [buf] yet. */
    fun find(buf: List<Int>, request: ModbusRequest): Match? {
        for (i in buf.indices) {
            if (buf[i] != request.address || i + 1 >= buf.size) continue
            val fc = buf[i + 1]
            val len = when {
                fc == (request.function or 0x80) -> 5
                fc != request.function -> continue
                request.function == Modbus.FC_READ_DISCRETE_INPUTS || request.function == Modbus.FC_READ_HOLDING_REGISTERS -> {
                    if (i + 2 >= buf.size) continue
                    val expected = (request as? ModbusRequest.ReadDiscreteInputs)?.byteCount
                        ?: (request as? ModbusRequest.ReadHoldingRegisters)?.let { 2 * it.quantity }
                    val bc = buf[i + 2]
                    if (expected != null && bc != expected) continue
                    3 + bc + 2
                }
                else -> request.normalResponseLength ?: continue
            }
            if (i + len > buf.size) continue
            val frame = buf.subList(i, i + len).toList()
            if (Crc16.valid(frame)) return Match(i, frame)
        }
        return null
    }
}
