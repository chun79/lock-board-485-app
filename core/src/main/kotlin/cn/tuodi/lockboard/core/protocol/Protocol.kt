package cn.tuodi.lockboard.core.protocol

/**
 * Constants of the private protocol of the DC_锁控_12 board (firmware LOCK_12_MODBUS_TIMED_20261006).
 *
 * Frame: `DC | DIR | ADDR | CMD | LEN | DATA[LEN] | SUM`, SUM = (ADDR + CMD + LEN + ΣDATA) % 256.
 * DIR is 0x02 for host → board and 0xFE for board → host.
 */
object Protocol {
    const val HEAD = 0xDC
    const val CHANNELS = 12
    const val MIN_ADDRESS = 1
    const val MAX_ADDRESS = 15
    const val DEFAULT_BAUD = 9600

    /** All 12 lock bits. */
    const val ALL_CHANNELS_MASK = 0x0FFF

    /** Bits 12..15 never correspond to a channel on a 12-channel board. */
    const val UNUSED_MASK_BITS = 0xF000

    /** Parser sanity limit; the protocol never sends more than 3 data bytes. */
    const val DEFAULT_MAX_DATA_LEN = 64

    fun isValidAddress(address: Int) = address in MIN_ADDRESS..MAX_ADDRESS
    fun isValidChannel(channel: Int) = channel in 1..CHANNELS
}

enum class Direction(val code: Int) {
    HOST_TO_BOARD(0x02),
    BOARD_TO_HOST(0xFE);

    companion object {
        fun of(code: Int): Direction? = entries.firstOrNull { it.code == code }
    }
}

/** Private command codes (docs/12路锁控板私有通讯协议-20261006.pdf §1). */
object Cmd {
    const val GET_VERSION = 0x01
    const val UNLOCK_ONE = 0x10
    const val READ_ONE = 0x11
    const val READ_ALL = 0x12
    const val UNLOCK_ALL = 0x14
    const val UNLOCK_MANY = 0x15
    const val SET_OUTPUT = 0x16
    const val UNLOCK_SIMULTANEOUS = 0x18
    const val SET_OUTPUTS = 0x1A

    /** Codes the firmware explicitly does not support (no reply). */
    val UNSUPPORTED = listOf(0x02, 0x03, 0x04, 0x13, 0x17, 0x19, 0x1B)

    val SUPPORTED = listOf(GET_VERSION, UNLOCK_ONE, READ_ONE, READ_ALL, UNLOCK_ALL, UNLOCK_MANY, SET_OUTPUT, UNLOCK_SIMULTANEOUS, SET_OUTPUTS)

    fun describe(code: Int): String = when (code) {
        GET_VERSION -> "查询版本"
        UNLOCK_ONE -> "单路脉冲开锁"
        READ_ONE -> "查询单锁"
        READ_ALL -> "查询全部"
        UNLOCK_ALL -> "全部逐路开锁"
        UNLOCK_MANY -> "多锁逐路开锁"
        SET_OUTPUT -> "单路持续/定时输出"
        UNLOCK_SIMULTANEOUS -> "多锁同时脉冲"
        SET_OUTPUTS -> "整体持续输出"
        in UNSUPPORTED -> "不支持（不回复）"
        else -> "未定义（不回复）"
    }
}

fun Int.hex2(): String = "%02X".format(this and 0xFF)

fun List<Int>.toHex(): String = joinToString(" ") { it.hex2() }

fun ByteArray.toUnsignedList(): List<Int> = map { it.toInt() and 0xFF }

fun List<Int>.toByteArray(): ByteArray = ByteArray(size) { this[it].toByte() }

/**
 * Parses user-entered hex such as "dc 02 01 01 00 02", "DC0201010002" or "0xdc,0x02".
 * Throws [IllegalArgumentException] with a Chinese message on malformed input.
 */
fun parseHex(text: String): List<Int> {
    val cleaned = text.replace(Regex("0[xX]"), " ").replace(Regex("[,;:\\s]+"), " ").trim()
    if (cleaned.isEmpty()) return emptyList()
    val tokens = cleaned.split(" ").flatMap { token ->
        require(token.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "非法字符：$token" }
        if (token.length <= 2) listOf(token) else {
            require(token.length % 2 == 0) { "十六进制长度必须为偶数：$token" }
            token.chunked(2)
        }
    }
    return tokens.map { it.toInt(16) }
}
