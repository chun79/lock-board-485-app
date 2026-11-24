package cn.tuodi.lockboard.app

import cn.tuodi.lockboard.core.client.CommLog
import cn.tuodi.lockboard.core.client.CommResult
import cn.tuodi.lockboard.core.client.ExecOptions
import cn.tuodi.lockboard.core.client.LockBoardClient
import cn.tuodi.lockboard.core.client.LogEntry
import cn.tuodi.lockboard.core.client.LogKind
import cn.tuodi.lockboard.core.client.OutputKey
import cn.tuodi.lockboard.core.client.OutputState
import cn.tuodi.lockboard.core.client.SafetyPolicy
import cn.tuodi.lockboard.core.config.AppConfig
import cn.tuodi.lockboard.core.config.BoardProfile
import cn.tuodi.lockboard.core.config.ChannelType
import cn.tuodi.lockboard.core.config.TransportType
import cn.tuodi.lockboard.core.modbus.Modbus
import cn.tuodi.lockboard.core.modbus.ModbusReply
import cn.tuodi.lockboard.core.modbus.ModbusRequest
import cn.tuodi.lockboard.core.protocol.Command
import cn.tuodi.lockboard.core.protocol.LockMask
import cn.tuodi.lockboard.core.protocol.MaskReply
import cn.tuodi.lockboard.core.protocol.Protocol
import cn.tuodi.lockboard.core.protocol.Reply
import cn.tuodi.lockboard.core.protocol.SingleLockReply
import cn.tuodi.lockboard.core.protocol.VersionReply
import cn.tuodi.lockboard.core.protocol.parseHex
import cn.tuodi.lockboard.core.protocol.toHex
import cn.tuodi.lockboard.core.sim.SimBoardState
import cn.tuodi.lockboard.core.sim.SimulatedBus
import cn.tuodi.lockboard.core.testing.Answer
import cn.tuodi.lockboard.core.testing.EndurancePlan
import cn.tuodi.lockboard.core.testing.EnduranceRunner
import cn.tuodi.lockboard.core.testing.EnduranceStatus
import cn.tuodi.lockboard.core.testing.Operator
import cn.tuodi.lockboard.core.testing.Progress
import cn.tuodi.lockboard.core.testing.ReportFormatter
import cn.tuodi.lockboard.core.testing.TestReport
import cn.tuodi.lockboard.core.testing.TestRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Outputs carried from another USB adapter's session, waiting for the operator. */
data class PendingCarry(val key: String, val label: String, val outputs: List<OutputKey>)

const val UNIDENTIFIED_USB = "USB?"

enum class LinkStatus(val label: String) {
    DISCONNECTED("未连接"),
    CONNECTING("连接中"),
    PROBING("通道已打开，探测板卡中…"),
    NO_BOARD("通道已打开（未检测到板卡）"),
    CONNECTED("已连接"),
    DISCONNECTING("断开中"),
    LOST("链路断开");

    /** True while a transport is open (probing, no board detected, or board confirmed online). */
    val isOpen: Boolean get() = this == PROBING || this == NO_BOARD || this == CONNECTED
    val editable: Boolean get() = this == DISCONNECTED || this == LOST
}

/** Which protocol the control page uses for its commands (the board speaks both). */
enum class ControlProtocol(val label: String) {
    PRIVATE("私有协议"),
    MODBUS("MODBUS RTU"),
}

data class LinkState(
    val status: LinkStatus = LinkStatus.DISCONNECTED,
    val description: String = "",
    val message: String = "",
    val simulated: Boolean = false,
)

data class ScanEntry(
    val address: Int,
    /** Private 0x01 reply bytes (null = no reply). */
    val privateVersion: List<Int>?,
    /** MODBUS register 0001 (null = no reply). */
    val modbusVersion: Int?,
    val latencyMs: Long?,
    val error: String?,
    val modbusException: Int? = null,
) {
    /** Online = at least one protocol answered with a confirmed reply. */
    val online get() = privateVersion != null || modbusVersion != null || modbusException != null
    val bothProtocols get() = privateVersion != null && (modbusVersion != null || modbusException != null)
}

data class ScanState(
    val running: Boolean = false,
    val current: Int = 0,
    val entries: List<ScanEntry> = emptyList(),
    val finishedAtMillis: Long? = null,
) {
    val online: List<ScanEntry> get() = entries.filter { it.online }
}

/** Last known state of the selected board; values only from confirmed replies. */
data class BoardState(
    val address: Int = 1,
    val privateVersion: List<Int>? = null,
    val modbusVersion: Int? = null,
    val mask: Int? = null,
    val singles: Map<Int, Boolean> = emptyMap(),
    /** Last trusted read-back of the continuous-output control word (register 0004). */
    val outputs: Int? = null,
    val updatedAtMillis: Long? = null,
    val lastError: String? = null,
)

data class OperatorPrompt(val id: Long, val text: String, val isRequest: Boolean, val answer: CompletableDeferred<Answer>)

data class TestState(
    val running: Boolean = false,
    val lines: List<String> = emptyList(),
    val report: TestReport? = null,
    val reportText: File? = null,
    val reportJson: File? = null,
)

data class EnduranceState(val running: Boolean = false, val status: EnduranceStatus? = null, val file: File? = null)

/**
 * Single owner of the bus session; lives in [LockBoardApp] so Activity recreation keeps the link.
 * All public methods are called from the main thread.
 */
class AppController(
    private val store: ConfigStore,
    private val factory: TransportFactory,
    private val scope: CoroutineScope,
    private val exportDir: File,
    val appVersion: String,
) {
    val config: StateFlow<AppConfig> = store.config
    val log = CommLog()

    private val _link = MutableStateFlow(LinkState())
    val link: StateFlow<LinkState> = _link.asStateFlow()

    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan.asStateFlow()

    private val _board = MutableStateFlow(BoardState())
    val board: StateFlow<BoardState> = _board.asStateFlow()

    private val _protocol = MutableStateFlow(ControlProtocol.PRIVATE)
    val protocol: StateFlow<ControlProtocol> = _protocol.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _lastResult = MutableStateFlow("")
    val lastResult: StateFlow<String> = _lastResult.asStateFlow()

    private val _polling = MutableStateFlow(false)
    val polling: StateFlow<Boolean> = _polling.asStateFlow()

    private val _armed = MutableStateFlow<Set<Int>>(emptySet())
    val armed: StateFlow<Set<Int>> = _armed.asStateFlow()

    private val _outputs = MutableStateFlow<Map<OutputKey, OutputState>>(emptyMap())
    val outputs: StateFlow<Map<OutputKey, OutputState>> = _outputs.asStateFlow()

    private val _prompt = MutableStateFlow<OperatorPrompt?>(null)
    val prompt: StateFlow<OperatorPrompt?> = _prompt.asStateFlow()

    private val _test = MutableStateFlow(TestState())
    val test: StateFlow<TestState> = _test.asStateFlow()

    private val _endurance = MutableStateFlow(EnduranceState())
    val endurance: StateFlow<EnduranceState> = _endurance.asStateFlow()

    private val _logEntries = MutableStateFlow<List<LogEntry>>(emptyList())
    val logEntries: StateFlow<List<LogEntry>> = _logEntries.asStateFlow()

    private val _simStates = MutableStateFlow<List<SimBoardState>>(emptyList())
    val simStates: StateFlow<List<SimBoardState>> = _simStates.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var client: LockBoardClient? = null
    private var simBus: SimulatedBus? = null
    private val sessionJobs = ArrayList<Job>()
    private var probeJob: Job? = null
    private var openNote: String? = null
    private var pollJob: Job? = null
    private var testJob: Job? = null
    private var testRunner: TestRunner? = null
    private var enduranceJob: Job? = null
    private var enduranceRunner: EnduranceRunner? = null
    private var promptSeq = 0L
    private var connectSeq = 0L

    /** Client currently being closed (one close per client). */
    private var closing: LockBoardClient? = null

    /** Per bus: unconfirmed outputs, pulse windows and reply quarantine handed from a closed session to the next. */
    private val carries = java.util.concurrent.ConcurrentHashMap<String, LockBoardClient.Carry>()

    /** Session → bus key it was opened with. */
    private val busKeys = java.util.concurrent.ConcurrentHashMap<LockBoardClient, String>()

    /** Carried outputs of other USB adapters, shown on the connect page for the operator to adopt or discard. */
    private val _pendingCarries = MutableStateFlow<List<PendingCarry>>(emptyList())
    val pendingCarries: StateFlow<List<PendingCarry>> = _pendingCarries.asStateFlow()

    /**
     * Identity of a bus. SIM / UART path / TCP endpoint are stable; a USB adapter is identified by
     * VID:PID:serial. Adapters without a serial number share [UNIDENTIFIED_USB], which is never imported
     * automatically (another adapter's zero read-back must not clear its registrations).
     */
    private fun busKey(cfg: AppConfig, opened: OpenedTransport): String = when (cfg.connection.type) {
        TransportType.SIMULATOR -> "SIM"
        TransportType.UART -> "UART:${cfg.connection.uartPath.trim()}"
        TransportType.TCP -> "TCP:${cfg.connection.tcpHost.trim()}:${cfg.connection.tcpPort}"
        TransportType.USB -> opened.busId ?: UNIDENTIFIED_USB
    }

    private fun isUsbKey(key: String) = key.startsWith("USB")

    private fun mergeCarry(a: LockBoardClient.Carry, b: LockBoardClient.Carry) = LockBoardClient.Carry(
        (a.outputs + b.outputs).distinct().sorted(),
        (a.pulseBusyUntil.keys + b.pulseBusyUntil.keys).associateWith { maxOf(a.pulseBusyUntil[it] ?: 0L, b.pulseBusyUntil[it] ?: 0L) },
        (a.dirtyUntil.keys + b.dirtyUntil.keys).associateWith { maxOf(a.dirtyUntil[it] ?: 0L, b.dirtyUntil[it] ?: 0L) },
    )

    private fun refreshPending() {
        val key = client?.let { busKeys[it] }
        _pendingCarries.value = if (key == null || !isUsbKey(key)) emptyList() else carries.entries
            .filter { (k, v) -> isUsbKey(k) && (k != key || k == UNIDENTIFIED_USB) && v.outputs.isNotEmpty() }
            .map { (k, v) -> PendingCarry(k, if (k == UNIDENTIFIED_USB) "无序列号的 USB 适配器" else k, v.outputs) }
            .sortedBy { it.key }
    }

    /** Operator: the current USB link is the same bus as [key] — import its registrations and read them back. */
    fun adoptCarry(key: String) {
        val c = client ?: return
        val carry = carries.remove(key) ?: return refreshPending()
        c.importCarry(carry)
        refreshPending()
        log.add(LogKind.WARN, "操作者确认当前总线即 ${key}，带入遗留登记 ${carry.outputs.joinToString()}，正在读回核对")
        verifyCarried(c, carry.outputs, "读回遗留的输出")
    }

    /** Reads back carried outputs; reports what stays unverified and notices a broken link. */
    private fun verifyCarried(
        c: LockBoardClient,
        keys: List<OutputKey>,
        title: String,
        onDone: ((Boolean) -> Unit)? = null,
    ) {
        beginManual(title)
        scope.launch {
            var anyConfirmed = false
            try {
                for (a in keys.map { it.address }.distinct()) {
                    val r = c.readOutputs(a)
                    if (r is CommResult.Ok && r.confirmed) {
                        anyConfirmed = true
                        markBoardOnline(c, "地址 $a 读回成功")
                    }
                }
            } finally {
                endManual()
            }
            val open = keys.filter { c.outputs.isTracked(it) }
            _lastResult.value = if (open.isEmpty()) "遗留输出的登记均已清除（读回 0004 显示关断，或操作者现场确认）" else "遗留输出未能确认关断：${open.joinToString()}（读回失败或仍开启）"
            if (open.isNotEmpty()) toast("遗留输出未能确认关断：${open.joinToString()}")
            checkLink()
            onDone?.invoke(anyConfirmed)
        }
    }

    /** Operator confirmed on site that the outputs carried under [key] are de-energised. */
    fun discardCarry(key: String) {
        val carry = carries[key] ?: return refreshPending()
        carries[key] = carry.copy(outputs = emptyList())
        refreshPending()
        log.add(LogKind.WARN, "操作者现场确认 $key 遗留的 ${carry.outputs.joinToString()} 已断电，清除登记")
    }

    /** Manual bus tasks in flight (main thread only); a scan may start only when this is zero. */
    private var manualInFlight = 0

    /** Set by stop/disconnect: current and future operator prompts return immediately. */
    @Volatile
    private var testStopping = false

    private val logSignal = Channel<Unit>(Channel.CONFLATED)

    init {
        log.addListener { logSignal.trySend(Unit) }
        scope.launch {
            for (u in logSignal) {
                _logEntries.value = log.snapshot().takeLast(UI_LOG_LIMIT)
                delay(200)
            }
        }
        // Keep the live client's timing in sync; drop the wiring confirmation of any edited profile.
        scope.launch {
            var previous = config.value.boards
            config.collect { cfg ->
                val c = client
                if (c != null) {
                    c.settings = settingsFor(cfg, c.transportKind.physical)
                    for (a in (previous.keys + cfg.boards.keys)) {
                        if (previous[a] != cfg.boards[a] && c.safety.isArmed(a)) {
                            log.add(LogKind.WARN, "地址 $a 的接线档案已修改，撤销本次连接的持续输出确认")
                            toast("地址 $a 档案已修改，请重新确认接线")
                        }
                        if (previous[a] != cfg.boards[a]) c.safety.onProfileChanged(a)
                    }
                    _armed.value = c.safety.armedAddresses()
                }
                previous = cfg.boards
            }
        }
    }

    fun updateConfig(transform: (AppConfig) -> AppConfig) = store.update(transform)

    fun resetConfig() = store.reset()

    fun setProtocol(p: ControlProtocol) { _protocol.value = p }

    private fun toast(text: String) { _messages.tryEmit(text) }

    private val activityRunning get() = _test.value.running || _endurance.value.running

    private fun beginManual(title: String) { manualInFlight++; _busy.value = title }

    private fun endManual() { manualInFlight--; if (manualInFlight <= 0) { manualInFlight = 0; _busy.value = null } }

    // ------------------------------------------------------------------------------------------
    // Connection

    private fun joinNotes(vararg parts: String?): String =
        parts.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.joinToString("；")

    private fun cancelProbeIfRunning(c: LockBoardClient) {
        probeJob?.cancel()
        probeJob = null
        if (client === c && closing !== c && _link.value.status == LinkStatus.PROBING) {
            _link.update {
                it.copy(
                    status = LinkStatus.NO_BOARD,
                    message = joinNotes(openNote, "已中止自动探测，等待板卡回复"),
                )
            }
        }
    }

    private fun markBoardOnline(c: LockBoardClient, detail: String? = null) {
        if (client !== c || closing === c || !c.isOpen) return
        val cur = _link.value
        val desc = "${c.transportKind.label}：${c.transportDescription}"
        if (cur.status == LinkStatus.PROBING || cur.status == LinkStatus.NO_BOARD) {
            log.add(LogKind.INFO, "已连接 $desc${detail?.let { "；$it" } ?: ""}")
            _link.value = LinkState(
                status = LinkStatus.CONNECTED,
                description = desc,
                message = joinNotes(openNote, detail),
                simulated = !c.transportKind.physical,
            )
        } else if (cur.status == LinkStatus.CONNECTED && detail != null) {
            _link.update { it.copy(message = joinNotes(openNote, detail)) }
        }
    }

    private fun markNoBoard(c: LockBoardClient, detail: String) {
        if (client !== c || closing === c || !c.isOpen || !c.transportKind.physical) return
        val desc = "${c.transportKind.label}：${c.transportDescription}"
        _link.value = LinkState(
            status = LinkStatus.NO_BOARD,
            description = desc,
            message = joinNotes(openNote, detail),
            simulated = false,
        )
    }

    private fun startAutoProbe(c: LockBoardClient) {
        probeJob?.cancel()
        val preferred = _board.value.address.takeIf { it in Protocol.MIN_ADDRESS..Protocol.MAX_ADDRESS }
        val order = listOfNotNull(preferred) + (Protocol.MIN_ADDRESS..Protocol.MAX_ADDRESS).filter { it != preferred }
        val t = config.value.scanTimeoutMs
        val options = ExecOptions(timeoutMs = t, readRetries = 0, lateGuardMs = minOf(c.settings.lateReplyGuardMs, t, 50))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                for ((idx, a) in order.withIndex()) {
                    if (!currentCoroutineContext().isActive || client !== c || closing === c || !c.isOpen) return@launch
                    _link.update {
                        if (it.status == LinkStatus.PROBING) {
                            it.copy(message = joinNotes(openNote, "正在探测地址 $a（${idx + 1}/${order.size}）…"))
                        } else it
                    }
                    val p = c.execute(Command.GetVersion(a), options)
                    if (!currentCoroutineContext().isActive || client !== c || closing === c) return@launch
                    if (!c.isOpen) {
                        checkLink()
                        return@launch
                    }
                    val pv = p.confirmedReply()?.bytes
                    if (pv != null) {
                        val now = System.currentTimeMillis()
                        _board.update { cur ->
                            if (cur.address != a) BoardState(address = a, privateVersion = pv, updatedAtMillis = now)
                            else cur.copy(privateVersion = pv, updatedAtMillis = now, lastError = null)
                        }
                        markBoardOnline(c, "检测到地址 $a 在线（私有 ${pv.toHex()}）")
                        return@launch
                    }
                    if (a == preferred) {
                        val m = c.executeModbus(ModbusRequest.ReadHoldingRegisters(a, Modbus.REG_VERSION, 1), options)
                        if (!currentCoroutineContext().isActive || client !== c || closing === c) return@launch
                        if (!c.isOpen) {
                            checkLink()
                            return@launch
                        }
                        val mReply = m.confirmedReply()
                        val mv = (mReply as? ModbusReply.Registers)?.value(Modbus.REG_VERSION)
                        val mEx = mReply as? ModbusReply.Exception
                        if (mv != null || mEx != null) {
                            val now = System.currentTimeMillis()
                            _board.update { cur ->
                                if (cur.address != a) BoardState(address = a, modbusVersion = mv, updatedAtMillis = now, lastError = mEx?.let { "MODBUS ${it.summary()}" })
                                else cur.copy(modbusVersion = mv, updatedAtMillis = now, lastError = mEx?.let { "MODBUS ${it.summary()}" })
                            }
                            val detail = mv?.let { "MODBUS %04X".format(it) } ?: "MODBUS ${mEx!!.summary()}"
                            markBoardOnline(c, "检测到地址 $a 在线（$detail）")
                            return@launch
                        }
                    }
                    if (!c.isOpen) {
                        checkLink()
                        return@launch
                    }
                }
                if (currentCoroutineContext().isActive && client === c && closing !== c && c.isOpen && _link.value.status == LinkStatus.PROBING) {
                    markNoBoard(c, "地址 1–15 均无回复；请检查 485 A/B 接线、板卡供电、波特率及设备节点")
                    log.add(LogKind.WARN, "通道已打开，但探测地址 1–15 均无板卡回复（${c.transportDescription}）")
                }
            } finally {
                if (probeJob === currentCoroutineContext()[Job]) probeJob = null
            }
        }
        probeJob = job
        job.start()
    }

    fun connect() {
        val st = _link.value.status
        if (st == LinkStatus.CONNECTING || st.isOpen || st == LinkStatus.DISCONNECTING) return
        _link.value = LinkState(LinkStatus.CONNECTING, config.value.connection.type.label)
        val seq = ++connectSeq
        scope.launch {
            val cfg = config.value
            try {
                val opened = factory.open(cfg)
                if (seq != connectSeq) {
                    // Disconnected (or reconnected) while opening: the attempt is stale, close what it opened.
                    withContext(NonCancellable + Dispatchers.IO) { runCatching { opened.transport.close() } }
                    return@launch
                }
                val safety = SafetyPolicy({ config.value.profile(it) }, { config.value.maxLockTimedSeconds })
                val c = LockBoardClient(opened.transport, settingsFor(cfg, opened.transport.kind.physical), log, safety)
                c.onConfirmedReply = { scope.launch { markBoardOnline(c) } }
                val key = busKey(cfg, opened)
                val inherited = if (key == UNIDENTIFIED_USB) null else carries.remove(key)
                if (inherited != null && !inherited.isEmpty) c.importCarry(inherited)
                // Timing state of other USB adapters only adds waiting: always imported. Their outputs need the operator.
                if (isUsbKey(key)) carries.forEach { (k, v) -> if (isUsbKey(k)) c.importCarry(v.copy(outputs = emptyList())) }
                busKeys[c] = key
                client = c
                openNote = opened.note
                refreshPending()
                if (_pendingCarries.value.isNotEmpty()) toast("其他 USB 适配器有遗留的可能带电输出，请在连接页处理")
                simBus = opened.transport as? SimulatedBus
                _armed.value = emptySet()
                _scan.value = ScanState() // results of another bus must never become batch targets
                _board.value = BoardState(address = _board.value.address)
                val desc = "${c.transportKind.label}：${c.transportDescription}"
                if (!c.transportKind.physical) {
                    _link.value = LinkState(LinkStatus.CONNECTED, desc, opened.note ?: "", simulated = true)
                    log.add(LogKind.INFO, "已连接 $desc${opened.note?.let { "；$it" } ?: ""}")
                } else {
                    _link.value = LinkState(LinkStatus.PROBING, desc, joinNotes(openNote, "正在探测在线板卡…"), simulated = false)
                    log.add(LogKind.INFO, "已打开通道 $desc${opened.note?.let { "；$it" } ?: ""}，开始探测在线板卡")
                }
                sessionJobs += scope.launch { c.outputs.state.collect { _outputs.value = it } }
                if (simBus != null) sessionJobs += scope.launch {
                    while (isActive) { _simStates.value = simBus?.boardStates() ?: emptyList(); delay(300) }
                }
                if (inherited != null && inherited.outputs.isNotEmpty()) {
                    log.add(LogKind.WARN, "上次会话未经读回确认关断的输出：${inherited.outputs.joinToString()}，正在读回核对")
                    toast("上次会话仍有可能带电的输出，正在读回核对")
                    verifyCarried(c, inherited.outputs, "读回上次会话的输出") { anyConfirmed ->
                        if (c.transportKind.physical && !anyConfirmed && client === c && closing !== c && c.isOpen && _link.value.status == LinkStatus.PROBING) {
                            startAutoProbe(c)
                        }
                    }
                } else if (c.transportKind.physical) {
                    startAutoProbe(c)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (seq != connectSeq) return@launch
                _link.value = LinkState(LinkStatus.DISCONNECTED, message = "连接失败：${e.message ?: e.javaClass.simpleName}")
                log.add(LogKind.ERROR, "连接失败：${e.message}")
            }
        }
    }

    /** Physical links never run with pulse budgets shorter than the firmware timing. */
    private fun settingsFor(cfg: AppConfig, physical: Boolean) =
        cfg.effectiveClientSettings().let { if (physical) it.withPhysicalMinimums() else it }

    /**
     * Disconnects. Lock-down (no new energising or pulses) → stop activities → transaction barrier →
     * switch off every registered output and confirm by read-back → close.
     */
    fun disconnect() {
        if (_link.value.status == LinkStatus.CONNECTING) {
            connectSeq++ // invalidate the pending attempt; it closes its transport when open() returns
            _link.value = LinkState(LinkStatus.DISCONNECTED, message = "已取消连接")
            log.add(LogKind.INFO, "已取消连接")
            return
        }
        val c = client ?: return
        beginClose(c, "已断开", lost = false, cleanupFirst = true)
    }

    /** Single close path per client: lock-down at once, then (optionally) clean up, then close. */
    private fun beginClose(c: LockBoardClient, reason: String, lost: Boolean, cleanupFirst: Boolean) {
        if (closing === c) return
        closing = c
        c.safety.lockDown()
        probeJob?.cancel(); probeJob = null
        _armed.value = emptySet()
        _link.update { it.copy(status = LinkStatus.DISCONNECTING) }
        scope.launch {
            try {
                stopActivities()
                if (cleanupFirst && c.isOpen) {
                    _busy.value = "断开前关断输出"
                    val res = try { c.cleanup() } finally { _busy.value = null }
                    if (res.possiblyOn.isNotEmpty()) toast("无法经读回确认已关断，可能仍带电：${res.possiblyOn.joinToString()}")
                    res.pulseActiveUntilNanos?.let { toast("仍有脉冲任务可能在执行，约 ${(it - System.nanoTime()) / 1_000_000} ms 后结束") }
                } else {
                    // Lost link: close the transport so in-flight I/O exits and records its unanswered request,
                    // then wait for it to leave the transaction lock before taking the snapshot.
                    withContext(NonCancellable + Dispatchers.IO) { c.close() }
                    withContext(NonCancellable) { c.barrier() }
                }
            } finally {
                closeSession(c, reason, lost)
                if (closing === c) closing = null
            }
        }
    }

    private fun closeSession(target: LockBoardClient, reason: String, lost: Boolean = false) {
        val key = busKeys.remove(target)
        val carry = target.exportCarry().takeIf { !it.isEmpty }
        if (key != null && carry != null) carries.merge(key, carry, ::mergeCarry)
        if (client !== target) {
            // A newer session is active: only release this client's transport.
            scope.launch(Dispatchers.IO) { target.close() }
            return
        }
        pollJob?.cancel(); pollJob = null
        _polling.value = false
        probeJob?.cancel(); probeJob = null
        openNote = null
        sessionJobs.forEach { it.cancel() }; sessionJobs.clear()
        client = null
        _pendingCarries.value = emptyList()
        scope.launch(Dispatchers.IO) { target.close() }
        simBus = null
        _simStates.value = emptyList()
        _armed.value = emptySet()
        _scan.value = ScanState()
        log.add(if (lost) LogKind.ERROR else LogKind.INFO, reason + (carry?.outputs?.takeIf { it.isNotEmpty() }?.let { "；未确认关断的输出将带入下次连接：${it.joinToString()}" } ?: ""))
        _link.value = LinkState(if (lost) LinkStatus.LOST else LinkStatus.DISCONNECTED, message = reason)
    }

    /** Called when the USB adapter in use is unplugged. */
    fun onUsbDetached(deviceName: String) {
        val c = client ?: return
        if (deviceName.isNotBlank() && c.transportDescription.contains(deviceName)) {
            beginClose(c, "USB 适配器已拔出（$deviceName）", lost = true, cleanupFirst = false)
        }
    }

    private fun checkLink() {
        val c = client ?: return
        if (!c.isOpen && closing !== c) beginClose(c, "链路断开：${c.failure?.message ?: "未知原因"}", lost = true, cleanupFirst = false)
    }

    private suspend fun stopActivities() {
        testStopping = true
        testRunner?.requestStop()
        enduranceRunner?.requestStop()
        _prompt.value?.answer?.complete(Answer.SKIP)
        testJob?.join()
        enduranceJob?.join()
    }

    // ------------------------------------------------------------------------------------------
    // Scan & board selection

    fun scan() {
        val c = client ?: return toast("请先连接")
        if (activityRunning || _scan.value.running || manualInFlight > 0) return toast("有任务正在进行")
        cancelProbeIfRunning(c)
        // Claim the bus synchronously, before any other request can be admitted.
        _scan.value = ScanState(running = true)
        pollJob?.cancel(); _polling.value = false
        sessionJobs += scope.launch {
            val entries = ArrayList<ScanEntry>()
            val t = config.value.scanTimeoutMs
            val options = ExecOptions(timeoutMs = t, readRetries = 1, lateGuardMs = minOf(c.settings.lateReplyGuardMs, t))
            try {
                for (a in Protocol.MIN_ADDRESS..Protocol.MAX_ADDRESS) {
                    if (client !== c) return@launch // session replaced: results belong to another bus
                    _scan.update { it.copy(current = a) }
                    val p = c.execute(Command.GetVersion(a), options)
                    val m = c.executeModbus(ModbusRequest.ReadHoldingRegisters(a, Modbus.REG_VERSION, 1), options)
                    if (client !== c) return@launch
                    val pv = p.confirmedReply()?.bytes
                    val mReply = m.confirmedReply()
                    val mv = (mReply as? ModbusReply.Registers)?.value(Modbus.REG_VERSION)
                    val mEx = (mReply as? ModbusReply.Exception)?.code
                    val err = listOfNotNull(
                        (p as? CommResult.Failed)?.let { "私有：${it.error.label}" },
                        (m as? CommResult.Failed)?.let { "MODBUS：${it.error.label}" },
                        (mReply as? ModbusReply.Exception)?.let { "MODBUS：${it.summary()}" },
                    ).joinToString("；").ifEmpty { null }
                    val onlineEntry = pv != null || mv != null || mEx != null
                    entries += ScanEntry(
                        address = a,
                        privateVersion = pv,
                        modbusVersion = mv,
                        latencyMs = (p as? CommResult.Ok)?.latencyMs ?: (m as? CommResult.Ok)?.latencyMs,
                        error = if (onlineEntry) err else "无回复",
                        modbusException = mEx,
                    )
                    _scan.update { it.copy(entries = entries.toList()) }
                    if (!c.isOpen) break
                }
                val online = entries.filter { it.online }
                log.add(LogKind.INFO, "扫描完成：在线地址 ${online.joinToString(",") { it.address.toString() }.ifEmpty { "无" }}")
                if (online.isNotEmpty()) {
                    if (online.none { it.address == _board.value.address }) selectBoard(online.first().address)
                    markBoardOnline(c, "在线 ${online.size} 块（地址 ${online.joinToString(",") { it.address.toString() }}）")
                } else if (client === c && closing !== c && c.isOpen && c.transportKind.physical) {
                    markNoBoard(c, "扫描完成：地址 1–15 均无回复；请检查 485 A/B 接线、板卡供电、波特率及设备节点")
                }
            } finally {
                if (client === c) _scan.update { it.copy(running = false, finishedAtMillis = System.currentTimeMillis()) }
            }
            checkLink()
        }
    }

    fun selectBoard(address: Int) {
        if (address == _board.value.address) return
        val e = _scan.value.entries.firstOrNull { it.address == address }
        _board.value = BoardState(address = address, privateVersion = e?.privateVersion, modbusVersion = e?.modbusVersion)
    }

    // ------------------------------------------------------------------------------------------
    // Control

    private fun action(
        title: String,
        refreshAfter: Boolean,
        expectedAddress: Int? = null,
        block: suspend (LockBoardClient, Int) -> CommResult<*>,
    ) {
        val c = client ?: return toast("请先连接")
        if (activityRunning) return toast("自动测试/寿命测试进行中，手动操作已禁用")
        if (_scan.value.running) return toast("总线扫描进行中")
        val address = _board.value.address
        if (expectedAddress != null && expectedAddress != address) {
            return toast("当前板已切换为地址 $address，请核对后重试")
        }
        cancelProbeIfRunning(c)
        beginManual(title)
        scope.launch {
            try {
                val r = block(c, address)
                _lastResult.value = r.describe()
                apply(c, address, r)
                if (r is CommResult.Failed) toast(r.describe())
                if (refreshAfter && c.isOpen) {
                    // MODBUS pulses run in the background: read the feedback once the action window is over.
                    c.awaitPulseIdle(address)
                    delay(config.value.plan.settleMs.toLong())
                    apply(c, address, c.execute(Command.ReadAll(address)))
                }
            } finally {
                endManual()
                checkLink()
            }
        }
    }

    private fun apply(c: LockBoardClient, address: Int, r: CommResult<*>) {
        if (r is CommResult.Ok<*> && r.confirmed && r.reply !is ModbusReply.BroadcastSent) {
            markBoardOnline(c)
        }
        if (_board.value.address != address) return
        val now = System.currentTimeMillis()
        if (r is CommResult.Failed) {
            _board.update { it.copy(lastError = r.describe()) }
            return
        }
        val ok = r as CommResult.Ok<*>
        if (!ok.confirmed) {
            _board.update { it.copy(lastError = "回复归属不明，状态未更新") }
            return
        }
        when (val reply: Reply = ok.reply) {
            is MaskReply -> _board.update { it.copy(mask = reply.mask, singles = emptyMap(), updatedAtMillis = now, lastError = null) }
            is SingleLockReply -> if (ok.command is Command.ReadOne || ok.command is Command.UnlockOne) {
                _board.update { it.copy(singles = it.singles + (reply.channel to reply.state), updatedAtMillis = now, lastError = null) }
            }
            is VersionReply -> _board.update { it.copy(privateVersion = reply.bytes, updatedAtMillis = now, lastError = null) }
            is ModbusReply.Bits -> _board.update { st ->
                if (reply.start == 1 && reply.quantity == Protocol.CHANNELS) st.copy(mask = reply.lockMask, singles = emptyMap(), updatedAtMillis = now, lastError = null)
                else st.copy(
                    singles = st.singles + (0 until reply.quantity).associate { (reply.start + it) to (reply.bits and (1 shl it) != 0) },
                    updatedAtMillis = now, lastError = null,
                )
            }
            is ModbusReply.Registers -> _board.update { st ->
                var s = st.copy(updatedAtMillis = now, lastError = null)
                reply.value(Modbus.REG_VERSION)?.let { s = s.copy(modbusVersion = it) }
                reply.value(Modbus.REG_FEEDBACK_LO)?.let { s = s.copy(mask = it and Protocol.ALL_CHANNELS_MASK, singles = emptyMap()) }
                reply.value(Modbus.REG_OUTPUT_LO)?.let { s = s.copy(outputs = it) }
                s
            }
            is ModbusReply.Exception -> _board.update { it.copy(lastError = "MODBUS ${reply.summary()}") }
            else -> _board.update { it.copy(lastError = null) }
        }
    }

    private val usesModbus get() = _protocol.value == ControlProtocol.MODBUS

    fun readVersion(expectedAddress: Int? = null) = action("读版本", false, expectedAddress) { c, a ->
        if (usesModbus) c.executeModbus(ModbusRequest.ReadHoldingRegisters(a, Modbus.REG_VERSION, 1)) else c.execute(Command.GetVersion(a))
    }

    fun readAll(expectedAddress: Int? = null) = action("读全部反馈", false, expectedAddress) { c, a ->
        if (usesModbus) c.executeModbus(ModbusRequest.ReadDiscreteInputs(a, 1, 12)) else c.execute(Command.ReadAll(a))
    }

    fun readOne(ch: Int, expectedAddress: Int? = null) = action("读第 $ch 路", false, expectedAddress) { c, a ->
        if (usesModbus) c.executeModbus(ModbusRequest.ReadDiscreteInputs(a, ch, 1)) else c.execute(Command.ReadOne(a, ch))
    }

    fun unlock(ch: Int, expectedAddress: Int? = null) = action("开第 $ch 路", true, expectedAddress) { c, a ->
        if (usesModbus) c.executeModbus(ModbusRequest.WriteSingleCoil(a, ch)) else c.execute(Command.UnlockOne(a, ch))
    }

    fun unlockAll(expectedAddress: Int? = null) = action("开全部锁", true, expectedAddress) { c, a ->
        if (usesModbus) c.executeModbus(ModbusRequest.WriteMultipleCoils(a, 1, 12, Protocol.ALL_CHANNELS_MASK)) else c.execute(Command.UnlockAll(a))
    }

    fun unlockMany(channels: Collection<Int>, expectedAddress: Int? = null) = action("逐路开多个锁", true, expectedAddress) { c, a ->
        val m = LockMask.of(channels)
        if (usesModbus) c.executeModbus(ModbusRequest.WriteMultipleCoils(a, 1, 12, m)) else c.execute(Command.UnlockMany(a, m))
    }

    fun unlockSimultaneous(channels: Collection<Int>, expectedAddress: Int? = null) = action("同时开多个锁", true, expectedAddress) { c, a ->
        val m = LockMask.of(channels)
        if (usesModbus) c.executeModbus(ModbusRequest.WriteSingleRegister(a, Modbus.REG_PULSE_LO, m)) else c.execute(Command.UnlockSimultaneous(a, m))
    }

    fun setOutput(ch: Int, on: Boolean, expectedAddress: Int? = null) = action("第 $ch 路持续输出${if (on) "开" else "关"}", false, expectedAddress) { c, a ->
        val r = if (usesModbus) c.executeModbus(ModbusRequest.WriteMultipleRegisters(a, Modbus.REG_SINGLE_CHANNEL, listOf(ch, if (on) 1 else 0)))
        else c.execute(Command.SetOutput(a, ch, on))
        if (r is CommResult.Ok) apply(c, a, c.readOutputs(a))
        r
    }

    fun setOutputTimed(ch: Int, seconds: Int, expectedAddress: Int? = null) = action("第 $ch 路定时输出 $seconds s", false, expectedAddress) { c, a ->
        val r = if (usesModbus) c.executeModbus(ModbusRequest.WriteMultipleRegisters(a, Modbus.REG_SINGLE_CHANNEL, listOf(ch, 1, seconds)))
        else c.execute(Command.SetOutputTimed(a, ch, seconds))
        if (r is CommResult.Ok) apply(c, a, c.readOutputs(a))
        r
    }

    fun setOutputs(channels: Collection<Int>, expectedAddress: Int? = null) = action("整体持续输出", false, expectedAddress) { c, a ->
        val m = LockMask.of(channels)
        val r = if (usesModbus) c.executeModbus(ModbusRequest.WriteSingleRegister(a, Modbus.REG_OUTPUT_LO, m)) else c.execute(Command.SetOutputs(a, m))
        if (r is CommResult.Ok) apply(c, a, c.readOutputs(a))
        r
    }

    /** Trusted read-back of 0004 (MODBUS 03); clears registrations whose bit reads 0. */
    fun readOutputs(expectedAddress: Int? = null) = action("读回输出 0004", false, expectedAddress) { c, a -> c.readOutputs(a) }

    fun cleanupOutputs() {
        val c = client ?: return
        if (activityRunning) return toast("测试进行中：请先停止测试，测试会自行逐路关断并读回确认")
        if (_scan.value.running) return toast("总线扫描进行中")
        cancelProbeIfRunning(c)
        beginManual("关断并读回确认")
        scope.launch {
            val res = try { c.cleanup() } finally { endManual() }
            val pulse = res.pulseActiveUntilNanos?.let { "；脉冲任务可能还要约 ${(it - System.nanoTime()) / 1_000_000} ms 才结束（实际输出 = 持续 OR 脉冲）" } ?: ""
            _lastResult.value = if (res.possiblyOn.isEmpty()) "持续输出已关断并经读回 0004 确认$pulse" else "无法经读回确认已关断：${res.possiblyOn.joinToString()}$pulse"
            checkLink()
        }
    }

    /** Generation of the registration of [key] currently shown (null if not registered). */
    fun outputGeneration(key: OutputKey): Long? = client?.outputs?.generation(key)

    /** Operator confirmed on site that [key] is de-energised; only the registration they observed is cleared. */
    fun confirmOffManually(key: OutputKey, generation: Long) {
        val c = client ?: return
        if (c.outputs.operatorConfirmedOff(key, generation)) log.add(LogKind.WARN, "操作者现场确认 $key 已断电，清除登记")
        else toast("$key 在确认期间被再次上电，未清除登记，请重新核实")
    }

    fun togglePolling(enabled: Boolean) {
        pollJob?.cancel(); pollJob = null
        _polling.value = false
        val c = client ?: return
        if (!enabled) return
        if (activityRunning) return toast("测试进行中，不能轮询")
        if (_scan.value.running) return toast("总线扫描进行中，不能轮询")
        cancelProbeIfRunning(c)
        _polling.value = true
        pollJob = scope.launch {
            while (isActive && c.isOpen && !activityRunning && !_scan.value.running) {
                val a = _board.value.address
                apply(c, a, if (usesModbus) c.executeModbus(ModbusRequest.ReadDiscreteInputs(a, 1, 12)) else c.execute(Command.ReadAll(a)))
                checkLink()
                delay(config.value.pollIntervalMs.toLong())
            }
            _polling.value = false
        }
    }

    /** Operator confirmed the wiring profile of [address] for this connection: continuous output allowed. */
    fun arm(address: Int) {
        val c = client ?: return
        if (!c.safety.arm(address)) return toast("正在断开，不能确认接线")
        _armed.value = c.safety.armedAddresses()
        log.add(LogKind.WARN, "操作者确认地址 $address 的接线档案（灯/继电器：${config.value.profile(address).channelsOf(ChannelType.OUTPUT).joinToString(",")}），本次连接允许持续输出")
    }

    fun disarm(address: Int) {
        val c = client ?: return
        c.safety.disarm(address)
        _armed.value = c.safety.armedAddresses()
    }

    fun simCloseDoor(address: Int, channel: Int) { simBus?.closeDoor(address, channel) }

    // ------------------------------------------------------------------------------------------
    // Terminal

    /** Validation shared by the terminal preview and [sendFrame]; null when the frame can be built. */
    fun frameError(address: Int, command: Int, dataHex: String): String? {
        val data = try { parseHex(dataHex) } catch (e: IllegalArgumentException) { return "数据段格式错误：${e.message}" }
        if (address !in 0..255 || command !in 0..255) return "地址/指令超出 0..255"
        if (data.size > MAX_TERMINAL_DATA) return "数据段最多 $MAX_TERMINAL_DATA 字节"
        return null
    }

    /** Private frame (DC 02 …) with automatic checksum. */
    fun sendFrame(address: Int, command: Int, dataHex: String) {
        val c = client ?: return toast("请先连接")
        if (activityRunning) return toast("测试进行中")
        if (_scan.value.running) return toast("总线扫描进行中")
        frameError(address, command, dataHex)?.let { return toast(it) }
        cancelProbeIfRunning(c)
        val data = parseHex(dataHex)
        beginManual("终端发送")
        scope.launch {
            try {
                _lastResult.value = c.execute(Command.Custom(address, command, data)).describe()
            } catch (e: IllegalArgumentException) {
                _lastResult.value = "构造失败：${e.message}"
            } finally {
                endManual()
                checkLink()
            }
        }
    }

    /** MODBUS frame with automatic CRC; [broadcastConfirmed] is set after the operator's confirmation. */
    fun sendModbusFrame(address: Int, function: Int, payloadHex: String, broadcastConfirmed: Boolean) {
        val c = client ?: return toast("请先连接")
        if (activityRunning) return toast("测试进行中")
        if (_scan.value.running) return toast("总线扫描进行中")
        frameError(address, function, payloadHex)?.let { return toast(it) }
        val req = ModbusRequest.fromFrame(cn.tuodi.lockboard.core.modbus.Crc16.append(listOf(address, function) + parseHex(payloadHex)))
            ?: return toast("无法构造 MODBUS 帧")
        cancelProbeIfRunning(c)
        beginManual("终端 MODBUS 发送")
        scope.launch {
            try {
                val r = if (req is ModbusRequest.Custom) c.sendRaw(req.encode(), config.value.client.readTimeoutMs, broadcastConfirmed).let { ex ->
                    ex.rejected?.let { "已拒绝：$it" } ?: (ex.modbusReply?.summary() ?: "无回复")
                } else {
                    val res = c.executeModbus(req, ExecOptions(broadcastConfirmed = broadcastConfirmed))
                    apply(c, address, res)
                    res.describe()
                }
                _lastResult.value = r
            } finally {
                endManual()
                checkLink()
            }
        }
    }

    fun sendRawHex(hex: String, listenMs: Int, broadcastConfirmed: Boolean = false) {
        val c = client ?: return toast("请先连接")
        if (activityRunning) return toast("测试进行中")
        if (_scan.value.running) return toast("总线扫描进行中")
        val bytes = try { parseHex(hex) } catch (e: IllegalArgumentException) { return toast("HEX 格式错误：${e.message}") }
        cancelProbeIfRunning(c)
        beginManual("原始发送")
        scope.launch {
            val ex = try { c.sendRaw(bytes, listenMs.coerceIn(50, 10_000), broadcastConfirmed) } finally { endManual() }
            _lastResult.value = ex.rejected?.let { "已拒绝：$it" }
                ?: ("收到 ${ex.received.size} 个私有帧" + (ex.modbusReply?.let { "、MODBUS：${it.summary()}" } ?: "") +
                    "，${ex.badFrames.size} 个坏帧（监听 ${ex.listenedMs} ms）")
            ex.rejected?.let { toast(it) }
            checkLink()
        }
    }

    /** True when [hex] is a MODBUS broadcast frame (needs the operator's confirmation). */
    fun isBroadcastFrame(hex: String): Boolean = runCatching { parseHex(hex) }.getOrNull()?.let {
        it.isNotEmpty() && it[0] == Modbus.BROADCAST && ModbusRequest.fromFrame(it) != null
    } ?: false

    fun clearLog() {
        log.clear()
        _logEntries.value = emptyList()
    }

    fun exportLog(): File {
        val f = File(exportDir, "comm-log-${stamp()}.txt")
        f.writeText(log.snapshot().joinToString("\n") { it.format() } + "\n" + (client?.stats?.summary() ?: ""))
        return f
    }

    val statsSummary: String get() = client?.stats?.summary() ?: ""

    // ------------------------------------------------------------------------------------------
    // Automatic tests

    private val operator = object : Operator {
        override suspend fun ask(question: String): Answer = prompt(question, isRequest = false)
        override suspend fun request(instruction: String) { prompt(instruction, isRequest = true) }

        private suspend fun prompt(text: String, isRequest: Boolean): Answer {
            if (testStopping) return Answer.SKIP
            val p = OperatorPrompt(++promptSeq, text, isRequest, CompletableDeferred())
            _prompt.value = p
            try {
                return p.answer.await()
            } finally {
                if (_prompt.value?.id == p.id) _prompt.value = null
            }
        }
    }

    fun answerPrompt(answer: Answer) { _prompt.value?.answer?.complete(answer) }

    /** Batch targets are only the boards found online by a scan of this session (never a fallback). */
    fun testTargets(allScanned: Boolean): List<Int> =
        if (allScanned) _scan.value.online.map { it.address } else listOf(_board.value.address)

    fun previewTest(targets: List<Int>): List<String> {
        val c = client ?: return listOf("未连接")
        return TestRunner(c, { config.value.profile(it) }, config.value.plan, operator, appVersion).preview(targets)
    }

    fun startTest(targets: List<Int>, armOutputs: Boolean) {
        val c = client ?: return toast("请先连接")
        if (activityRunning || _scan.value.running || manualInFlight > 0) return toast("有任务正在进行")
        if (targets.isEmpty()) return toast("没有测试对象：请先扫描总线")
        cancelProbeIfRunning(c)
        if (armOutputs) targets.filter { config.value.profile(it).channelsOf(ChannelType.OUTPUT).isNotEmpty() }.forEach { arm(it) }
        togglePolling(false)
        val lines = ArrayList<String>()
        fun add(line: String) {
            lines += line
            _test.update { it.copy(lines = lines.takeLast(400)) }
        }
        val runner = TestRunner(c, { config.value.profile(it) }, config.value.plan, operator, appVersion, onProgress = { p ->
            scope.launch {
                when (p) {
                    is Progress.Message -> add("■ ${p.text}")
                    is Progress.ItemStarted -> add("▶ 地址${p.address} ${p.id} ${p.name}")
                    is Progress.Step -> add("    [${p.step.verdict.label}] ${p.step.text}")
                    is Progress.ItemFinished -> add("◀ ${p.result.id} ${p.result.name}：${p.result.verdict.label} —— ${p.result.summary}")
                }
            }
        })
        testRunner = runner
        testStopping = false
        _test.value = TestState(running = true)
        log.add(LogKind.INFO, "开始自动测试：地址 ${targets.joinToString(",")}")
        testJob = scope.launch {
            try {
                val report = runner.run(targets)
                val base = "test-report-${stamp()}"
                val (txt, json) = withContext(Dispatchers.IO) {
                    val t = File(exportDir, "$base.txt").apply { writeText(ReportFormatter.toText(report)) }
                    val j = File(exportDir, "$base.json").apply { writeText(ReportFormatter.toJson(report).toString(2)) }
                    t to j
                }
                _test.update { it.copy(running = false, report = report, reportText = txt, reportJson = json) }
                log.add(LogKind.INFO, "自动测试结束：${report.verdict.label}，报告 ${txt.name}")
            } catch (e: CancellationException) {
                _test.update { it.copy(running = false) }
                throw e
            } catch (e: Exception) {
                _test.update { it.copy(running = false) }
                log.add(LogKind.ERROR, "自动测试异常：${e.message}")
                toast("自动测试异常：${e.message}")
            } finally {
                testRunner = null
                checkLink()
            }
        }
    }

    fun stopTest() {
        testStopping = true
        testRunner?.requestStop()
        _prompt.value?.answer?.complete(Answer.SKIP)
    }

    fun startEndurance(channels: List<Int>, cycles: Int, intervalMs: Int, address: Int = _board.value.address) {
        val c = client ?: return toast("请先连接")
        if (activityRunning || _scan.value.running || manualInFlight > 0) return toast("有任务正在进行")
        if (address != _board.value.address) return toast("当前板已切换为地址 ${_board.value.address}，请核对通道后重试")
        if (channels.isEmpty()) return toast("请选择通道")
        cancelProbeIfRunning(c)
        togglePolling(false)
        val plan = EndurancePlan(address, channels, cycles, intervalMs, config.value.plan.settleMs)
        val file = File(exportDir, "endurance-${stamp()}.txt")
        val runner = EnduranceRunner(c, plan) { st ->
            _endurance.update { it.copy(status = st) }
            runCatching { file.writeText(st.summaryText()) }
        }
        enduranceRunner = runner
        _endurance.value = EnduranceState(running = true, file = file)
        log.add(LogKind.INFO, "开始寿命测试：地址 ${plan.address} 第 ${channels.joinToString(",")} 路 × $cycles 轮")
        enduranceJob = scope.launch {
            try {
                runner.run()
            } finally {
                enduranceRunner = null
                _endurance.update { it.copy(running = false) }
                checkLink()
            }
        }
    }

    fun stopEndurance() { enduranceRunner?.requestStop() }

    private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())

    fun profileOf(address: Int): BoardProfile = config.value.profile(address)

    fun channelStateText(ch: Int): String {
        val b = _board.value
        val s = if (b.singles.containsKey(ch)) b.singles[ch] else b.mask?.let { LockMask.isSet(it, ch) }
        return when (s) { true -> "打开"; false -> "关闭"; null -> "未知" }
    }

    fun versionText(b: BoardState) =
        "私有 ${b.privateVersion?.toHex() ?: "未读"}；MODBUS ${b.modbusVersion?.let { "%04X".format(it) } ?: "未读"}"

    companion object {
        const val UI_LOG_LIMIT = 1500
        const val MAX_TERMINAL_DATA = 64
    }
}
