package cn.tuodi.lockboard.core.protocol

/**
 * 12-bit lock mask. Inferred from the document examples (`00 07` = locks 1..3, `0F FF` = all 12):
 * DATA[0] is the high byte, DATA[1] the low byte, and lock n (1..12) is bit (n - 1).
 */
object LockMask {
    fun bit(channel: Int): Int {
        require(Protocol.isValidChannel(channel)) { "通道号必须为 1..${Protocol.CHANNELS}：$channel" }
        return 1 shl (channel - 1)
    }

    fun of(channels: Iterable<Int>): Int = channels.fold(0) { acc, ch -> acc or bit(ch) }

    fun channels(mask: Int): List<Int> = (1..Protocol.CHANNELS).filter { mask and (1 shl (it - 1)) != 0 }

    fun isSet(mask: Int, channel: Int): Boolean = mask and bit(channel) != 0

    fun toBytes(mask: Int): List<Int> {
        require(mask in 0..0xFFFF) { "掩码超出 16 位" }
        return listOf((mask shr 8) and 0xFF, mask and 0xFF)
    }

    fun fromBytes(high: Int, low: Int): Int = ((high and 0xFF) shl 8) or (low and 0xFF)

    fun describe(mask: Int): String {
        val chs = channels(mask)
        return if (chs.isEmpty()) "无" else chs.joinToString(",")
    }
}
