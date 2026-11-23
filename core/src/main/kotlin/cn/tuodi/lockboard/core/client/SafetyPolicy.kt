package cn.tuodi.lockboard.core.client

import cn.tuodi.lockboard.core.config.BoardProfile
import cn.tuodi.lockboard.core.config.ChannelType
import cn.tuodi.lockboard.core.modbus.Modbus
import cn.tuodi.lockboard.core.modbus.ModbusRequest
import cn.tuodi.lockboard.core.protocol.Cmd
import cn.tuodi.lockboard.core.protocol.Command
import cn.tuodi.lockboard.core.protocol.LockMask
import cn.tuodi.lockboard.core.protocol.Protocol
import java.util.concurrent.ConcurrentHashMap

/**
 * Single gate in front of every transmission of both protocols (control page, tests, terminal). It is
 * evaluated again inside the transaction lock right before the write.
 *
 * A write that energises a channel beyond a lock pulse — private 0x16 on (continuous or timed), 0x1A set
 * bits, MODBUS 0004 set bits, MODBUS 10 → 0008 "on" — is allowed only if the session is not closing,
 * the operator confirmed this board's wiring profile in this connection (snapshot still current), and:
 *  - the channel is a lamp/relay (OUTPUT), or
 *  - the channel is a lock (LOCK), the write is *timed* with seconds ≤ [maxLockTimedSeconds] (0 = never)
 *    and the channel is not still registered as possibly energised (re-timing would extend the on-time).
 * Writes that cancel timers while keeping outputs on (MODBUS 0005 alone, per the MODBUS manual §6) and
 * writes with undocumented effects (0007 alone) are refused. Broadcast (MODBUS address 0) never energises;
 * broadcast pulses need an explicit confirmation.
 */
class SafetyPolicy(
    private val profiles: (Int) -> BoardProfile,
    private val maxLockTimedSeconds: () -> Int = { 0 },
) {
    /** Address → the exact profile the operator confirmed. */
    private val armed = ConcurrentHashMap<Int, BoardProfile>()

    @Volatile
    private var lockedDown = false

    /** Set by the client: whether a channel is still registered as possibly energised. */
    @Volatile
    var isEnergized: (OutputKey) -> Boolean = { false }

    fun arm(address: Int): Boolean {
        if (lockedDown || address !in Protocol.MIN_ADDRESS..Protocol.MAX_ADDRESS) return false
        armed[address] = profiles(address)
        if (lockedDown) { armed.clear(); return false }
        return true
    }

    fun disarm(address: Int) { armed.remove(address) }

    /** Profile edited: the stored confirmation is dropped (not merely compared). */
    fun onProfileChanged(address: Int) { armed.remove(address) }

    fun lockDown() {
        lockedDown = true
        armed.clear()
    }

    val isLockedDown get() = lockedDown

    fun isArmed(address: Int): Boolean = !lockedDown && armed[address]?.let { it == profiles(address) } == true

    fun armedAddresses(): Set<Int> = armed.keys.filter { isArmed(it) }.toSet()

    // ------------------------------------------------------------------------------------------

    fun check(command: Command<*>): String? = checkPrivate(command.address, command.code, command.data)

    private val pulseCodes = setOf(Cmd.UNLOCK_ONE, Cmd.UNLOCK_ALL, Cmd.UNLOCK_MANY, Cmd.UNLOCK_SIMULTANEOUS)

    fun checkPrivate(address: Int, code: Int, data: List<Int>): String? = when {
        lockedDown && code in pulseCodes -> "正在断开，不再允许开锁脉冲"
        else -> checkPrivateEffect(address, code, data)
    }

    private fun checkPrivateEffect(address: Int, code: Int, data: List<Int>): String? = when (code) {
        Cmd.SET_OUTPUT -> when (data.size) {
            2 -> if (data[1] == 0) null else energize(address, data[0], timedSeconds = null)
            4 -> when {
                data[1] == 0 -> null
                else -> {
                    val secs = (data[2] shl 8) or data[3]
                    energize(address, data[0], timedSeconds = secs.takeIf { it > 0 })
                }
            }
            else -> "0x16 数据段必须为 2 或 4 字节，拒绝发送"
        }
        Cmd.SET_OUTPUTS -> {
            if (data.size != 2) "0x1A 数据段必须为 2 字节位图，拒绝发送"
            else energizeMask(address, LockMask.fromBytes(data[0], data[1]))
        }
        else -> null
    }

    fun checkModbus(request: ModbusRequest, broadcastConfirmed: Boolean = false): String? {
        val a = request.address
        val broadcast = a == Modbus.BROADCAST
        val pulse = request.pulsedChannels().isNotEmpty() || request.function == Modbus.FC_WRITE_SINGLE_COIL ||
            request.function == Modbus.FC_WRITE_MULTIPLE_COILS
        if (lockedDown && pulse && request !is ModbusRequest.Custom) return "正在断开，不再允许开锁脉冲"
        return when (request) {
            is ModbusRequest.ReadDiscreteInputs, is ModbusRequest.ReadHoldingRegisters -> null
            is ModbusRequest.WriteSingleCoil, is ModbusRequest.WriteMultipleCoils ->
                if (broadcast && !broadcastConfirmed) "广播脉冲会同时打开总线上所有板的锁，需要二次确认" else null
            is ModbusRequest.WriteSingleRegister -> when (request.register) {
                Modbus.REG_OUTPUT_LO -> energizeMask(a, request.value)
                Modbus.REG_OUTPUT_HI -> "单独写 0005 会取消全部单路定时并使定时输出变为持续通电（MODBUS 手册第 13、15 页），拒绝发送"
                Modbus.REG_PULSE_LO -> if (broadcast && !broadcastConfirmed && request.value != 0) "广播脉冲需要二次确认" else null
                Modbus.REG_PULSE_HI -> "单独写 0007 的作用文档未说明，拒绝发送"
                else -> null // other registers are rejected by the board with an exception
            }
            is ModbusRequest.WriteMultipleRegisters -> checkWriteRegisters(request, broadcastConfirmed)
            is ModbusRequest.Custom -> {
                // A terminal frame: decode it as typed request if possible, otherwise it is an unsupported function.
                val typed = ModbusRequest.fromFrame(request.encode())
                if (typed != null && typed !is ModbusRequest.Custom) checkModbus(typed, broadcastConfirmed)
                else if (broadcast && pulse && !broadcastConfirmed) "广播需要二次确认" else null
            }
        }
    }

    private fun checkWriteRegisters(r: ModbusRequest.WriteMultipleRegisters, broadcastConfirmed: Boolean): String? {
        val a = r.address
        if (r.start == Modbus.REG_SINGLE_CHANNEL) {
            val v = r.values
            if (v.size < 2) return null // incomplete block: exception 02
            val ch = v[0]
            return when {
                v[1] == 0 -> null
                v.size >= 3 && v[2] > 0 -> energize(a, ch, timedSeconds = v[2])
                else -> energize(a, ch, timedSeconds = null)
            }
        }
        val w = r.writes()
        if (Modbus.REG_OUTPUT_HI in w && Modbus.REG_OUTPUT_LO !in w) {
            return "写 0005 而不同时写 0004，会使定时输出变为持续通电，拒绝发送"
        }
        if (Modbus.REG_PULSE_HI in w && Modbus.REG_PULSE_LO !in w) return "单独写 0007 的作用文档未说明，拒绝发送"
        if ((w[Modbus.REG_OUTPUT_HI] ?: 0) != 0 || (w[Modbus.REG_PULSE_HI] ?: 0) != 0) return "保留高半字必须为 0000"
        w[Modbus.REG_OUTPUT_LO]?.let { energizeMask(a, it)?.let { reason -> return reason } }
        val pulseMask = w[Modbus.REG_PULSE_LO] ?: 0
        if (a == Modbus.BROADCAST && pulseMask != 0 && !broadcastConfirmed) return "广播脉冲需要二次确认"
        return null
    }

    private fun energizeMask(address: Int, mask: Int): String? {
        if (mask and Protocol.ALL_CHANNELS_MASK.inv() != 0) return "位图高 4 位必须为 0"
        for (ch in LockMask.channels(mask)) energize(address, ch, timedSeconds = null)?.let { return it }
        return null
    }

    private fun energize(address: Int, channel: Int, timedSeconds: Int?): String? {
        if (lockedDown) return "正在断开，不再允许任何上电输出"
        if (address == Modbus.BROADCAST) return "广播不允许持续或定时上电"
        if (!Protocol.isValidChannel(channel)) return "通道号 $channel 超出 1～12"
        if (!isArmed(address)) return "地址 $address 的接线档案尚未在本次连接中确认（或确认后已修改），不允许上电输出"
        val profile = profiles(address).channel(channel)
        return when (profile.type) {
            ChannelType.OUTPUT -> null
            ChannelType.UNUSED -> "第 $channel 路配置为未使用，不允许上电输出"
            ChannelType.LOCK -> {
                val limit = maxLockTimedSeconds()
                when {
                    timedSeconds == null -> "第 $channel 路为电控锁，不允许持续通电（可能烧坏电控锁）"
                    limit <= 0 -> "第 $channel 路为电控锁；设置中未允许电控锁定时通电"
                    timedSeconds > limit -> "第 $channel 路为电控锁，定时 $timedSeconds s 超过允许的 $limit s"
                    isEnergized(OutputKey(address, channel)) -> "第 $channel 路仍登记为可能带电，重新定时会延长通电，拒绝发送"
                    else -> null
                }
            }
        }
    }
}
