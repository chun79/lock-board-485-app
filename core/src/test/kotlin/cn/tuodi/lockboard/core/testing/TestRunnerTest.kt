package cn.tuodi.lockboard.core.testing

import cn.tuodi.lockboard.core.client.ClientSettings
import cn.tuodi.lockboard.core.client.CommLog
import cn.tuodi.lockboard.core.client.LockBoardClient
import cn.tuodi.lockboard.core.client.SafetyPolicy
import cn.tuodi.lockboard.core.config.AppConfig
import cn.tuodi.lockboard.core.config.BoardProfile
import cn.tuodi.lockboard.core.config.ChannelProfile
import cn.tuodi.lockboard.core.config.ChannelType
import cn.tuodi.lockboard.core.config.ConfigJson
import cn.tuodi.lockboard.core.config.TransportType
import cn.tuodi.lockboard.core.sim.SimBoardConfig
import cn.tuodi.lockboard.core.sim.SimConfig
import cn.tuodi.lockboard.core.sim.SimulatedBus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TestRunnerTest {
    private val fast = ClientSettings(
        readTimeoutMs = 80, perLockPulseMaxMs = 60, simultaneousPulseMs = 15, actionMarginMs = 60,
        interFrameGapMs = 1, lateReplyGuardMs = 60, interByteTimeoutMs = 15, lateReplyMaxAgeMs = 300,
    )
    private val plan = TestPlan(
        communicationCount = 3, stressCount = 6, settleMs = 20, unlockIntervalMs = 5, waitClosedMs = 1000,
        timedSeconds = 1, timerToleranceMs = 400,
    )

    private class ScriptedOperator(val answer: (String) -> Answer = { Answer.YES }) : Operator {
        val questions = ArrayList<String>()
        override suspend fun ask(question: String): Answer { questions += question; return answer(question) }
        override suspend fun request(instruction: String) { questions += instruction }
    }

    private fun sim(vararg boards: SimBoardConfig, autoCloseMs: Int = 300) = SimConfig(
        boards = boards.toList().ifEmpty { listOf(SimBoardConfig(1)) },
        pulseOnMs = 10, pulseSettleMs = 5, responseDelayMs = 1, autoCloseMs = autoCloseMs,
    )

    private val withOutputs = BoardProfile(1, "测试板")
        .withChannel(11, ChannelProfile(ChannelType.OUTPUT, "灯"))
        .withChannel(12, ChannelProfile(ChannelType.OUTPUT, "继电器"))

    private fun setup(simConfig: SimConfig, profile: BoardProfile = BoardProfile(1), armed: Boolean = true): Triple<SimulatedBus, LockBoardClient, (Int) -> BoardProfile> {
        val bus = SimulatedBus(simConfig)
        val profiles: (Int) -> BoardProfile = { if (it == profile.address) profile else BoardProfile(it) }
        val client = LockBoardClient(bus, fast, CommLog(), SafetyPolicy(profiles))
        if (armed) client.safety.arm(profile.address)
        return Triple(bus, client, profiles)
    }

    private fun TestReport.item(id: String) = boards.single().items.firstOrNull { it.id == id }

    private val unlockOnly = plan.copy(
        communication = false, consistency = false, robustness = false, modbusExceptions = false, stress = false,
        outputSingle = false, outputMulti = false, outputTimed = false, modbusOutputs = false, crossProtocolTimers = false,
    )

    @Test fun `healthy board passes every item of both protocols`() = runBlocking {
        val (bus, client, profiles) = setup(sim(), withOutputs)
        val report = TestRunner(client, profiles, plan.copy(manualConfirm = true, allowPulseOnOutputs = true), ScriptedOperator(), "test").run(listOf(1))
        val text = ReportFormatter.toText(report)
        val ids = listOf("T0", "T1", "T2", "T9", "M7", "T10", "T3", "M3", "T4", "M4", "T5", "M5", "T6", "T7", "T8", "T11", "M6", "X1", "X2")
        for (id in ids) assertEquals("$id\n$text", Verdict.PASS, report.item(id)?.verdict)
        assertEquals(Verdict.PASS, report.verdict)
        assertTrue(report.possiblyEnergized.isEmpty())
        assertTrue(bus.boardStates().single().outputOn.none { it })
        assertEquals(listOf(2, 0), report.boards.single().privateVersion)
        assertEquals(2, report.boards.single().modbusVersion)
        assertTrue(text.contains("模拟器"))
        JSONObject(ReportFormatter.toJson(report).toString())
        File("build").mkdirs()
        File("build/sample-report.txt").writeText(text)
    }

    @Test fun `without feedback and manual confirmation the physical part is unverified`() = runBlocking {
        val noFb = (1..12).fold(BoardProfile(1)) { acc, ch -> acc.withChannel(ch, ChannelProfile(feedback = false)) }
        val (_, client, profiles) = setup(sim(), noFb)
        val report = TestRunner(client, profiles, unlockOnly.copy(unlockAll = false), NoOperator, "t").run(listOf(1))
        for (id in listOf("T3", "M3", "T4", "M4", "T5", "M5")) assertEquals(id, Verdict.UNVERIFIED, report.item(id)?.verdict)
    }

    @Test fun `dead driver is detected by both protocols`() = runBlocking {
        val (_, client, profiles) = setup(sim(SimBoardConfig(1, deadChannels = setOf(3))))
        val report = TestRunner(client, profiles, unlockOnly.copy(unlockMany = false, modbusUnlockMany = false, unlockSimultaneous = false, modbusSimultaneous = false, unlockAll = false), NoOperator, "t").run(listOf(1))
        for (id in listOf("T3", "M3")) {
            val item = report.item(id)!!
            assertEquals(id, Verdict.FAIL, item.verdict)
            assertTrue(item.steps.any { it.verdict == Verdict.FAIL && it.text.contains("第 3 路") })
            assertFalse(item.steps.any { it.verdict == Verdict.FAIL && it.text.contains("第 4 路") })
        }
    }

    @Test fun `miswired channel opening a neighbour is reported`() = runBlocking {
        val (_, client, profiles) = setup(sim(SimBoardConfig(1, miswired = mapOf(4 to 5))))
        val report = TestRunner(client, profiles, unlockOnly.copy(modbusUnlockEach = false, unlockMany = false, modbusUnlockMany = false, unlockSimultaneous = false, modbusSimultaneous = false, unlockAll = false), NoOperator, "t").run(listOf(1))
        assertTrue(report.item("T3")!!.steps.any { it.verdict == Verdict.FAIL && it.text.contains("非目标通道 5") })
    }

    @Test fun `board-reported open without independent read-back is unverified`() = runBlocking {
        val (_, client, profiles) = setup(sim(autoCloseMs = 1))
        val report = TestRunner(client, profiles, unlockOnly.copy(modbusUnlockEach = false, unlockMany = false, modbusUnlockMany = false, unlockSimultaneous = false, modbusSimultaneous = false, unlockAll = false), NoOperator, "t").run(listOf(1))
        assertEquals(Verdict.UNVERIFIED, report.item("T3")!!.verdict)
    }

    @Test fun `T9 passes on a healthy board with a long late-reply age`() = runBlocking {
        val bus = SimulatedBus(sim())
        val profiles: (Int) -> BoardProfile = { BoardProfile(it) }
        val client = LockBoardClient(bus, fast.copy(lateReplyMaxAgeMs = 2000), CommLog(), SafetyPolicy(profiles))
        val report = TestRunner(client, profiles, unlockOnly.copy(robustness = true, unlockEach = false, modbusUnlockEach = false, unlockMany = false,
            modbusUnlockMany = false, unlockSimultaneous = false, modbusSimultaneous = false, unlockAll = false), NoOperator, "t").run(listOf(1))
        assertEquals(ReportFormatter.toText(report), Verdict.PASS, report.item("T9")!!.verdict)
    }

    @Test fun `a stop request during a timer wait ends the item at once and switches off`() = runBlocking {
        val (bus, client, profiles) = setup(sim(), withOutputs)
        lateinit var runner: TestRunner
        val p = unlockOnly.copy(unlockEach = false, modbusUnlockEach = false, unlockMany = false, modbusUnlockMany = false,
            unlockSimultaneous = false, modbusSimultaneous = false, unlockAll = false, crossProtocolTimers = true, timedSeconds = 30)
        runner = TestRunner(client, profiles, p, NoOperator, "t")
        val job = launch { runner.run(listOf(1)) }
        // Wait until X1 has energised its outputs, then stop.
        while (bus.boardStates().single().outputOn.none { it }) kotlinx.coroutines.delay(10)
        val t0 = System.nanoTime()
        runner.requestStop()
        job.join()
        assertTrue("停止应立即生效：${(System.nanoTime() - t0) / 1_000_000} ms", (System.nanoTime() - t0) / 1_000_000 < 3000)
        assertTrue(bus.boardStates().single().outputOn.none { it })
    }

    @Test fun `offline board fails the online check`() = runBlocking {
        val (_, client, profiles) = setup(sim())
        val report = TestRunner(client, profiles, plan, NoOperator, "t").run(listOf(9))
        assertEquals("T0", report.boards.single().items.single().id)
        assertEquals(Verdict.FAIL, report.verdict)
    }

    @Test fun `outputs are skipped when the wiring profile is not armed`() = runBlocking {
        val (bus, client, profiles) = setup(sim(), withOutputs, armed = false)
        val report = TestRunner(client, profiles, plan, NoOperator, "t").run(listOf(1))
        for (id in listOf("T6", "T7", "T8", "T11", "M6", "X1", "X2")) assertEquals(id, Verdict.SKIP, report.item(id)?.verdict)
        assertTrue(bus.receivedHostFrames.none { it.command == 0x16 || it.command == 0x1A || it.command == 0x14 })
    }

    @Test fun `stop request yields a partial report with outputs switched off`() = runBlocking {
        val (bus, client, profiles) = setup(sim(), withOutputs)
        lateinit var runner: TestRunner
        val op = ScriptedOperator { q -> if (q.contains("已通电")) { runner.requestStop(); Answer.YES } else Answer.YES }
        runner = TestRunner(client, profiles, unlockOnly.copy(manualConfirm = true, unlockEach = false, modbusUnlockEach = false, unlockMany = false,
            modbusUnlockMany = false, unlockSimultaneous = false, modbusSimultaneous = false, unlockAll = false, outputSingle = true), op, "t")
        val report = runner.run(listOf(1))
        assertTrue(report.cancelled)
        assertTrue(report.possiblyEnergized.isEmpty())
        assertTrue(bus.boardStates().single().outputOn.none { it })
        assertTrue(ReportFormatter.toText(report).contains("中止"))
    }

    @Test fun `coroutine cancellation during an output test still switches outputs off`() = runBlocking {
        val (bus, client, profiles) = setup(sim(), withOutputs)
        val asked = CompletableDeferred<Unit>()
        val op = object : Operator {
            override suspend fun ask(question: String): Answer { asked.complete(Unit); CompletableDeferred<Unit>().await(); return Answer.SKIP }
            override suspend fun request(instruction: String) = Unit
        }
        val job = launch {
            TestRunner(client, profiles, unlockOnly.copy(manualConfirm = true, unlockEach = false, modbusUnlockEach = false, unlockMany = false,
                modbusUnlockMany = false, unlockSimultaneous = false, modbusSimultaneous = false, unlockAll = false, outputSingle = true), op, "t").run(listOf(1))
        }
        asked.await()
        assertTrue(bus.boardStates().single().outputOn[10])
        job.cancelAndJoin()
        assertTrue(bus.boardStates().single().outputOn.none { it })
        assertTrue(client.outputs.energized().isEmpty())
    }

    @Test fun `preview lists every physical action`() {
        val (_, client, profiles) = setup(sim(), withOutputs)
        val lines = TestRunner(client, profiles, plan, NoOperator, "t").preview(listOf(1))
        assertTrue(lines.any { it.contains("0x10") })
        assertTrue(lines.any { it.contains("MODBUS 05") })
        assertTrue(lines.any { it.contains("0x14") && it.contains("跳过") })
        assertTrue(lines.any { it.contains("11,12") && it.contains("定时") })
        val m7 = TestRunner(client, profiles, plan.copy(modbusExceptions = true), NoOperator, "t").preview(listOf(1))
        assertTrue(m7.joinToString("\n"), m7.any { it.contains("M7") && it.contains("第 11 路") })
    }

    @Test fun `endurance counts verified actions and stops after consecutive failures`() = runBlocking {
        val (_, client, _) = setup(sim(autoCloseMs = 40))
        val ok = EnduranceRunner(client, EndurancePlan(1, listOf(1, 2), cycles = 3, cycleIntervalMs = 80, settleMs = 5)).run()
        assertEquals(listOf(3, 3), ok.channels.map { it.verifiedActions })
        val (_, bad, _) = setup(sim(SimBoardConfig(1, deadChannels = setOf(1)), autoCloseMs = 40))
        val st = EnduranceRunner(bad, EndurancePlan(1, listOf(1), cycles = 50, cycleIntervalMs = 0, settleMs = 1, stopAfterConsecutiveFailures = 3)).run()
        assertEquals(3, st.channels.single().failures)
        assertTrue(st.stopReason.contains("连续失败"))
    }

    @Test fun `config json round trip`() {
        val cfg = AppConfig().copy(
            connection = AppConfig().connection.copy(type = TransportType.TCP, tcpHost = "10.0.0.5", tcpPort = 4196),
            boards = mapOf(1 to withOutputs, 3 to BoardProfile(3, "三号")),
            plan = plan.copy(manualConfirm = true, simultaneousChannels = listOf(2, 4), modbusOutputs = false),
            sim = sim(SimBoardConfig(1, deadChannels = setOf(3), miswired = mapOf(4 to 5))),
            client = fast.copy(discardTxEcho = true),
            maxLockTimedSeconds = 2,
        )
        val back = ConfigJson.decode(ConfigJson.encode(cfg))
        assertEquals(cfg.connection, back.connection)
        assertEquals(cfg.boards, back.boards)
        assertEquals(cfg.plan.validated(), back.plan)
        assertEquals(cfg.sim, back.sim.copy(seed = cfg.sim.seed, baud = cfg.sim.baud))
        assertEquals(cfg.client.validated(), back.client)
        assertEquals(2, back.maxLockTimedSeconds)
        assertEquals(AppConfig(), ConfigJson.decode("{}"))
    }
}
