package cn.tuodi.lockboard.core.config

import cn.tuodi.lockboard.core.protocol.LockMask
import cn.tuodi.lockboard.core.protocol.Protocol

enum class ChannelType(val label: String) {
    /** Electric lock: pulses only. Continuous output may burn it (protocol §4.7). */
    LOCK("电控锁"),

    /** Lamp / relay / other load that tolerates continuous output (0x16 / 0x1A). */
    OUTPUT("灯/继电器"),

    /** Nothing connected. */
    UNUSED("未使用"),
}

data class ChannelProfile(
    val type: ChannelType = ChannelType.LOCK,
    val label: String = "",
    /** Lock feedback wiring present: 0x11/0x12 reflect the lock state. */
    val feedback: Boolean = true,
)

/** Wiring profile of the board at one DIP address. The default (all locks) forbids continuous output. */
data class BoardProfile(
    val address: Int,
    val name: String = "",
    val channels: List<ChannelProfile> = List(Protocol.CHANNELS) { ChannelProfile() },
) {
    init {
        require(channels.size == Protocol.CHANNELS) { "每块板必须有 ${Protocol.CHANNELS} 路配置" }
    }

    fun channel(ch: Int): ChannelProfile = channels[ch - 1]

    fun channelsOf(type: ChannelType): List<Int> = (1..Protocol.CHANNELS).filter { channel(it).type == type }

    fun maskOf(type: ChannelType): Int = LockMask.of(channelsOf(type))

    /** Lock channels whose feedback can be used to verify a physical action. */
    fun feedbackLocks(): List<Int> = channelsOf(ChannelType.LOCK).filter { channel(it).feedback }

    fun displayName(): String = if (name.isBlank()) "地址 $address" else "$name（地址 $address）"

    fun withChannel(ch: Int, profile: ChannelProfile): BoardProfile =
        copy(channels = channels.toMutableList().also { it[ch - 1] = profile })

    companion object {
        fun default(address: Int) = BoardProfile(address)
    }
}

/** DIP switch helper: Address = S1·2⁰ + S2·2¹ + S3·2² + S4·2³ (ON = 1). */
object DipSwitch {
    /** Switch states for switches 1..4 (true = ON). */
    fun switchesFor(address: Int): List<Boolean> {
        require(address in 0..15) { "拨码地址只能为 0..15" }
        return (0 until 4).map { address and (1 shl it) != 0 }
    }

    fun addressOf(switches: List<Boolean>): Int {
        require(switches.size == 4) { "拨码开关为 4 位" }
        return switches.foldIndexed(0) { i, acc, on -> if (on) acc or (1 shl i) else acc }
    }

    /** Text in the document's order (switch 4 → 1), e.g. "OFF OFF OFF ON" for address 1. */
    fun describe(address: Int): String = switchesFor(address).reversed().joinToString(" ") { if (it) "ON" else "OFF" }
}
