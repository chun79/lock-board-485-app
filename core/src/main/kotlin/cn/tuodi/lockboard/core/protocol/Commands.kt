package cn.tuodi.lockboard.core.protocol

class ProtocolException(message: String) : Exception(message)

/**
 * Timing class of a private command (docs/12路锁控板私有通讯协议-20261006.pdf).
 * 0x10/0x14/0x15/0x18 reply only after the action finished.
 */
enum class CommandKind {
    /** 0x01 / 0x11 / 0x12: immediate reply, side-effect free, may be retried. */
    READ,

    /** 0x16 / 0x1A: immediate confirmation (DATA=01). Never retried. */
    IMMEDIATE,

    /** 0x10 / 0x14 / 0x15: sequential pulses, ≈300 ms each with up to 3 retries; reply after the last. */
    PULSE_SEQUENTIAL,

    /** 0x18: one simultaneous ≈300 ms pulse without retries; reply after feedback sampling. */
    PULSE_SIMULTANEOUS,
}

/** A typed private request with its reply decoder. */
sealed class Command<out R : Reply>(override val address: Int, val code: Int, val kind: CommandKind) : RequestInfo {
    abstract val data: List<Int>
    abstract override val title: String

    /** Validates and decodes a reply whose direction/address/command already match this request. */
    abstract fun decode(frame: Frame): R

    fun encode(): List<Int> = FrameCodec.encode(address, code, data)

    /** Locks pulsed by this command (drives timeout and pulse-task pacing). */
    open fun pulsedChannels(): List<Int> = emptyList()

    protected fun requireLen(frame: Frame, vararg allowed: Int) {
        if (frame.data.size !in allowed) {
            throw ProtocolException("回复 LEN=${frame.data.size}，期望 ${allowed.joinToString("/")}")
        }
    }

    protected fun decodeMask(frame: Frame): MaskReply {
        requireLen(frame, 2)
        val mask = LockMask.fromBytes(frame.data[0], frame.data[1])
        if (mask and Protocol.UNUSED_MASK_BITS != 0) throw ProtocolException("反馈 bit12～15 应为 0，收到 ${"%04X".format(mask)}")
        return MaskReply(mask)
    }

    protected fun decodeAck(frame: Frame): AckReply {
        requireLen(frame, 1)
        if (frame.data[0] != 1) throw ProtocolException("确认值应为 01，收到 ${frame.data[0].hex2()}")
        return AckReply
    }

    protected fun decodeSingle(frame: Frame, channel: Int): SingleLockReply {
        requireLen(frame, 2)
        if (frame.data[0] != channel) throw ProtocolException("回复锁号 ${frame.data[0]}，与请求 $channel 不符")
        val v = frame.data[1]
        if (v !in 0..1) throw ProtocolException("回复值 ${v.hex2()} 不是 00/01")
        return SingleLockReply(channel, v == 1)
    }

    init {
        require(address in 0..0xFF) { "地址超出范围：$address" }
    }

    class GetVersion(address: Int) : Command<VersionReply>(address, Cmd.GET_VERSION, CommandKind.READ) {
        override val data = emptyList<Int>()
        override val title = "读版本"
        override fun decode(frame: Frame): VersionReply {
            requireLen(frame, 2)
            return VersionReply(frame.data[0], frame.data[1])
        }
    }

    /** 0x10: reply [lock, 01 opened / 00 still closed after retries] arrives when the action ends. */
    class UnlockOne(address: Int, val channel: Int) : Command<SingleLockReply>(address, Cmd.UNLOCK_ONE, CommandKind.PULSE_SEQUENTIAL) {
        override val data = listOf(channel)
        override val title = "开锁 #$channel"
        override fun pulsedChannels() = listOf(channel)
        override fun decode(frame: Frame) = decodeSingle(frame, channel)
    }

    /** 0x11: reply [lock, 01 open / 00 closed]; illegal lock numbers are not answered. */
    class ReadOne(address: Int, val channel: Int) : Command<SingleLockReply>(address, Cmd.READ_ONE, CommandKind.READ) {
        override val data = listOf(channel)
        override val title = "读锁 #$channel"
        override fun decode(frame: Frame) = decodeSingle(frame, channel)
    }

    class ReadAll(address: Int) : Command<MaskReply>(address, Cmd.READ_ALL, CommandKind.READ) {
        override val data = emptyList<Int>()
        override val title = "读全部反馈"
        override fun decode(frame: Frame) = decodeMask(frame)
    }

    /** 0x14: locks 1..12 one after another (4.2–16.8 s); reply after the last. */
    class UnlockAll(address: Int) : Command<MaskReply>(address, Cmd.UNLOCK_ALL, CommandKind.PULSE_SEQUENTIAL) {
        override val data = emptyList<Int>()
        override val title = "开全部锁（逐路）"
        override fun pulsedChannels() = (1..Protocol.CHANNELS).toList()
        override fun decode(frame: Frame) = decodeMask(frame)
    }

    /** 0x15: selected locks in ascending order. 0000 is a legal no-op answered at once. */
    class UnlockMany(address: Int, val mask: Int) : Command<MaskReply>(address, Cmd.UNLOCK_MANY, CommandKind.PULSE_SEQUENTIAL) {
        override val data = LockMask.toBytes(mask)
        override val title = "逐路开多个锁 [${LockMask.describe(mask)}]"
        override fun pulsedChannels() = LockMask.channels(mask)
        override fun decode(frame: Frame) = decodeMask(frame)
    }

    /** 0x16 LEN=2: continuous on / off (off also cancels this channel's timer). */
    class SetOutput(address: Int, val channel: Int, val on: Boolean) : Command<AckReply>(address, Cmd.SET_OUTPUT, CommandKind.IMMEDIATE) {
        override val data = listOf(channel, if (on) 1 else 0)
        override val title = "持续输出 #$channel ${if (on) "开" else "关"}"
        override fun decode(frame: Frame) = decodeAck(frame)
    }

    /** 0x16 LEN=4: on for [seconds] (1..65535), then the board withdraws the continuous output itself. */
    class SetOutputTimed(address: Int, val channel: Int, val seconds: Int) : Command<AckReply>(address, Cmd.SET_OUTPUT, CommandKind.IMMEDIATE) {
        init {
            require(seconds in 1..0xFFFF) { "定时秒数须为 1～65535" }
        }
        override val data = listOf(channel, 1, (seconds ushr 8) and 0xFF, seconds and 0xFF)
        override val title = "定时输出 #$channel ${seconds} s"
        override fun decode(frame: Frame) = decodeAck(frame)
    }

    /** 0x18: selected locks pulsed together once (~300 ms, no retries). 0000 is a legal no-op. */
    class UnlockSimultaneous(address: Int, val mask: Int) : Command<MaskReply>(address, Cmd.UNLOCK_SIMULTANEOUS, CommandKind.PULSE_SIMULTANEOUS) {
        override val data = LockMask.toBytes(mask)
        override val title = "同时开多个锁 [${LockMask.describe(mask)}]"
        override fun pulsedChannels() = LockMask.channels(mask)
        override fun decode(frame: Frame) = decodeMask(frame)
    }

    /** 0x1A: continuous-output bitmap for all 12 channels (0 = off); cancels every channel timer. */
    class SetOutputs(address: Int, val mask: Int) : Command<AckReply>(address, Cmd.SET_OUTPUTS, CommandKind.IMMEDIATE) {
        override val data = LockMask.toBytes(mask)
        override val title = "整体持续输出 [${LockMask.describe(mask)}]"
        override fun decode(frame: Frame) = decodeAck(frame)
    }

    /** Arbitrary well-formed frame built in the debug terminal. Never retried. */
    class Custom(address: Int, code: Int, override val data: List<Int>) : Command<RawReply>(address, code, kindOf(code)) {
        override val title = "自定义 0x${code.hex2()}"
        override fun pulsedChannels(): List<Int> = when (code) {
            Cmd.UNLOCK_ONE -> data.take(1).filter { Protocol.isValidChannel(it) }
            Cmd.UNLOCK_ALL -> (1..Protocol.CHANNELS).toList()
            Cmd.UNLOCK_MANY, Cmd.UNLOCK_SIMULTANEOUS -> if (data.size == 2) LockMask.channels(LockMask.fromBytes(data[0], data[1]) and Protocol.ALL_CHANNELS_MASK) else emptyList()
            else -> emptyList()
        }
        override fun decode(frame: Frame) = RawReply(frame.data)

        private companion object {
            fun kindOf(code: Int) = when (code) {
                Cmd.UNLOCK_ONE, Cmd.UNLOCK_ALL, Cmd.UNLOCK_MANY -> CommandKind.PULSE_SEQUENTIAL
                Cmd.UNLOCK_SIMULTANEOUS -> CommandKind.PULSE_SIMULTANEOUS
                Cmd.GET_VERSION, Cmd.READ_ONE, Cmd.READ_ALL -> CommandKind.READ
                else -> CommandKind.IMMEDIATE
            }
        }
    }
}

/** Anything a result can refer to (private command or MODBUS request). */
interface RequestInfo {
    val address: Int
    val title: String
}

/** A decoded reply of either protocol. */
interface Reply {
    fun summary(): String
}

/** 0x01 reply [version, reserved]; the current firmware answers 02 00. */
data class VersionReply(val version: Int, val reserved: Int) : Reply {
    val bytes get() = listOf(version, reserved)
    override fun summary() = "版本 ${version.hex2()} ${reserved.hex2()}"

    companion object {
        const val EXPECTED_VERSION = 0x02
    }
}

/** 0x10: [state] = opened successfully; 0x11: [state] = feedback open. */
data class SingleLockReply(val channel: Int, val state: Boolean) : Reply {
    override fun summary() = "锁 $channel：${if (state) "01" else "00"}"
}

data class MaskReply(val mask: Int) : Reply {
    fun isOpen(channel: Int) = LockMask.isSet(mask, channel)
    override fun summary() = "打开 [${LockMask.describe(mask)}]"
}

/** 0x16 / 0x1A acceptance (DATA = 01). Says nothing about which command or channel it answers. */
data object AckReply : Reply {
    override fun summary() = "已受理"
}

data class RawReply(val bytes: List<Int>) : Reply {
    override fun summary() = "数据 [${bytes.toHex()}]"
}
