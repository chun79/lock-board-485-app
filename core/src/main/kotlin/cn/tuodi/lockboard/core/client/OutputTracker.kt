package cn.tuodi.lockboard.core.client

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class OutputKey(val address: Int, val channel: Int) : Comparable<OutputKey> {
    override fun compareTo(other: OutputKey) = compareValuesBy(this, other, { it.address }, { it.channel })
    override fun toString() = "地址$address-通道$channel"
}

enum class OutputState(val label: String) {
    /** A command that energises this channel was written; no trusted read-back since. */
    POSSIBLY_ON("可能带电"),

    /** A trusted read-back of register 0004 showed this channel's continuous output on. */
    READBACK_ON("读回为开"),
}

/**
 * Channels this app may have energised with continuous or timed output. An entry is registered inside
 * the transaction lock **before** the energising frame is written. ACKs never remove entries (they carry
 * no channel); only a trusted MODBUS read-back of 0004 showing the bit 0, or an explicit operator
 * confirmation, does. A cleared entry means the continuous part is off; a pulse task may still drive
 * the output until its window ends (actual output = continuous OR pulse).
 */
class OutputTracker {
    private val _state = MutableStateFlow<Map<OutputKey, OutputState>>(emptyMap())
    val state: StateFlow<Map<OutputKey, OutputState>> = _state.asStateFlow()

    /** Unique per registration (process-wide), so an operator confirmation only clears what was observed. */
    private val generations = HashMap<OutputKey, Long>()

    @Synchronized
    fun registerPossiblyOn(keys: Collection<OutputKey>) {
        if (keys.isEmpty()) return
        keys.forEach { generations[it] = NEXT_GENERATION.getAndIncrement() }
        _state.update { current -> current + keys.associateWith { OutputState.POSSIBLY_ON } }
    }

    @Synchronized
    fun generation(key: OutputKey): Long? = if (isTracked(key)) generations[key] else null

    /** Applies a trusted 0004 read-back of [address]: tracked bits 0 are cleared, bits 1 marked READBACK_ON. */
    fun applyReadback(address: Int, outputBitmap: Int) = _state.update { current ->
        current.mapNotNull { (k, v) ->
            when {
                k.address != address -> k to v
                outputBitmap and (1 shl (k.channel - 1)) == 0 -> null
                else -> k to OutputState.READBACK_ON
            }
        }.toMap()
    }

    /**
     * Operator confirmed on site that the load is de-energised (logged by the caller). Only the registration
     * of [generation] (the one shown when the operator started observing) is removed.
     */
    @Synchronized
    fun operatorConfirmedOff(key: OutputKey, generation: Long): Boolean {
        if (generations[key] != generation || !isTracked(key)) return false
        _state.update { it - key }
        return true
    }

    fun energized(): List<OutputKey> = _state.value.keys.sorted()

    fun isTracked(key: OutputKey) = key in _state.value

    private companion object {
        val NEXT_GENERATION = java.util.concurrent.atomic.AtomicLong(1)
    }
}
