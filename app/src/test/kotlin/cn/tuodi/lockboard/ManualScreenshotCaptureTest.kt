package cn.tuodi.lockboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.tuodi.lockboard.app.LinkStatus
import cn.tuodi.lockboard.app.LockBoardApp
import cn.tuodi.lockboard.core.config.BoardProfile
import cn.tuodi.lockboard.core.config.ChannelProfile
import cn.tuodi.lockboard.core.config.ChannelType
import cn.tuodi.lockboard.core.sim.SimBoardConfig
import cn.tuodi.lockboard.core.sim.SimConfig
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Generates real Chinese UI screenshots for the user manual using Robolectric NATIVE graphics.
 * Skipped during regular unit test runs unless `MANUAL_SCREENSHOTS_DIR` (or `-Dmanual.screenshots.dir`) is set.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w412dp-h840dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ManualScreenshotCaptureTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val controller get() = (compose.activity.application as LockBoardApp).controller

    private fun waitForMain(timeoutMs: Long, desc: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                error("超时等待($desc, ${timeoutMs}ms): running=${controller.test.value.running}, lines=${controller.test.value.lines.takeLast(5)}")
            }
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            Thread.sleep(20)
        }
        ShadowLooper.idleMainLooper()
        compose.waitForIdle()
    }

    private fun saveScreenshot(dir: File, name: String) {
        ShadowLooper.idleMainLooper()
        compose.waitForIdle()
        val out = File(dir, name)
        val view = compose.activity.window.decorView
        fun drawDecor(): Bitmap {
            val w = view.width.coerceAtLeast(1)
            val h = view.height.coerceAtLeast(1)
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                view.draw(Canvas(b))
            }
        }
        val bitmap = if (Looper.myLooper() == Looper.getMainLooper()) {
            drawDecor()
        } else {
            var bmp: Bitmap? = null
            val latch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                bmp = drawDecor()
                latch.countDown()
            }
            latch.await(5, TimeUnit.SECONDS)
            bmp ?: error("无法截取界面图像：$name")
        }
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun captureManualScreenshots() {
        val dirProp = (System.getProperty("manual.screenshots.dir")
            ?: System.getenv("MANUAL_SCREENSHOTS_DIR")).orEmpty().trim()
        Assume.assumeTrue("未设置 manual.screenshots.dir / MANUAL_SCREENSHOTS_DIR，跳过用户手册截图采集", dirProp.isNotBlank())
        val outDir = File(dirProp).apply { mkdirs() }

        val board1 = BoardProfile(
            address = 1,
            name = "A 柜上层",
            channels = listOf(
                ChannelProfile(ChannelType.LOCK, "1号门", feedback = true),
                ChannelProfile(ChannelType.LOCK, "2号门", feedback = true),
                ChannelProfile(ChannelType.LOCK, "3号门", feedback = true),
                ChannelProfile(ChannelType.OUTPUT, "照明灯", feedback = false),
                ChannelProfile(ChannelType.UNUSED, "预留", feedback = false),
                ChannelProfile(ChannelType.LOCK, "6号门", feedback = true),
                ChannelProfile(ChannelType.LOCK, "7号门", feedback = true),
                ChannelProfile(ChannelType.LOCK, "8号门", feedback = true),
                ChannelProfile(ChannelType.LOCK, "9号门", feedback = true),
                ChannelProfile(ChannelType.LOCK, "10号门", feedback = true),
                ChannelProfile(ChannelType.LOCK, "11号门", feedback = true),
                ChannelProfile(ChannelType.LOCK, "12号门", feedback = true),
            ),
        )
        val board2 = BoardProfile(address = 2, name = "A 柜下层")

        controller.updateConfig {
            it.copy(
                sim = SimConfig(
                    boards = listOf(SimBoardConfig(1), SimBoardConfig(2)),
                    pulseOnMs = 10,
                    pulseSettleMs = 5,
                    autoCloseMs = 60_000,
                    responseDelayMs = 1,
                ),
                client = it.client.copy(
                    readTimeoutMs = 80,
                    perLockPulseMaxMs = 60,
                    simultaneousPulseMs = 20,
                    actionMarginMs = 60,
                    interFrameGapMs = 1,
                    lateReplyGuardMs = 60,
                    lateReplyMaxAgeMs = 300,
                ),
                plan = it.plan.copy(
                    communicationCount = 3,
                    stressCount = 6,
                    settleMs = 15,
                    unlockIntervalMs = 5,
                    waitClosedMs = 500,
                    timedSeconds = 1,
                    timerToleranceMs = 400,
                    manualConfirm = false,
                ),
                scanTimeoutMs = 60,
            ).withProfile(board1).withProfile(board2)
        }
        compose.waitForIdle()

        // 1. 连接页：连接并扫描总线（展示在线 2 块板与双协议版本）
        compose.onNodeWithTag("connect").performScrollTo().performClick()
        waitForMain(10_000, "连接") { controller.link.value.status == LinkStatus.CONNECTED }
        compose.onNodeWithTag("scan").performScrollTo().performClick()
        waitForMain(15_000, "扫描") { controller.scan.value.finishedAtMillis != null && controller.board.value.address == 1 }
        compose.onNodeWithTag("scan_2").performScrollTo()
        saveScreenshot(outDir, "01_connect_scan.png")

        // 2. 配置页：板卡接线档案
        compose.onNodeWithTag("tab_SETTINGS").performClick()
        compose.onNodeWithTag("profile_6").performScrollTo()
        saveScreenshot(outDir, "02_settings_profile.png")

        // 3. 配置页：上电输出安全 + 拨码地址换算 + 模拟器入口
        compose.onNodeWithText("每次通电 ms").performScrollTo()
        saveScreenshot(outDir, "03_settings_dip.png")

        // 4. 控制页（上部）：带入扫描到的版本号、读反馈、读回 0004 与未确认接线安全门
        compose.onNodeWithTag("tab_CONTROL").performClick()
        controller.selectBoard(2)
        controller.selectBoard(1)
        controller.readAll()
        waitForMain(5_000, "读反馈") { controller.board.value.mask != null }
        controller.readOutputs()
        waitForMain(5_000, "读回 0004") { controller.board.value.outputs != null }
        compose.onNodeWithTag("arm").performScrollTo()
        saveScreenshot(outDir, "04_control_top.png")

        // 5. 控制页（中部）：确认接线、开第 1 路锁、开第 4 路灯/继电器，展示 12 路通道卡片
        controller.arm(1)
        waitForMain(5_000, "确认接线") { 1 in controller.armed.value }
        compose.onNodeWithTag("unlock_1").performScrollTo().performClick()
        waitForMain(10_000, "第 1 路开锁") { controller.channelStateText(1) == "打开" }
        compose.onNodeWithTag("outOn_4").performScrollTo().performClick()
        waitForMain(10_000, "第 4 路持续输出开") { controller.board.value.outputs == (1 shl 3) }
        compose.onNodeWithTag("channel_6").performScrollTo()
        saveScreenshot(outDir, "05_control_channels.png")

        // 6. 控制页（下部）：批量操作区（选全部锁并高亮可用按钮）
        compose.onNodeWithTag("unlockAll").performScrollTo()
        compose.onNodeWithText("选全部锁").performScrollTo().performClick()
        compose.onNodeWithTag("unlockAll").performScrollTo()
        saveScreenshot(outDir, "06_control_batch.png")

        // 7. 测试页（上部）：测试对象、测试项与参数
        compose.onNodeWithTag("tab_TEST").performClick()
        compose.onNodeWithText("T5 私有 0x18 同时开").performScrollTo()
        saveScreenshot(outDir, "07_test_setup.png")

        // 8. 测试页（报告区）：切为快速自动关门后重连，运行一次自动测试并截取报告
        controller.disconnect()
        waitForMain(10_000, "断开") { controller.link.value.status == LinkStatus.DISCONNECTED }
        controller.updateConfig {
            it.copy(
                sim = it.sim.copy(autoCloseMs = 150),
                plan = it.plan.copy(
                    unlockSimultaneous = false,
                    modbusSimultaneous = false,
                    unlockAll = false,
                ),
            )
        }
        controller.connect()
        waitForMain(10_000, "重连") { controller.link.value.status == LinkStatus.CONNECTED }
        controller.startTest(listOf(1), armOutputs = true)
        waitForMain(90_000, "自动测试报告") { controller.test.value.report != null && !controller.test.value.running }
        compose.onNodeWithText("分享 TXT").performScrollTo()
        saveScreenshot(outDir, "08_test_report.png")

        // 9. 终端页：构造发送与彩色通讯日志
        compose.onNodeWithTag("tab_TERMINAL").performClick()
        controller.sendFrame(1, 0x12, "")
        waitForMain(5_000, "私有帧 0x12") { controller.lastResult.value.contains("0x12") }
        controller.sendModbusFrame(1, 0x03, "00 01 00 01", broadcastConfirmed = false)
        waitForMain(5_000, "MODBUS 03") { controller.lastResult.value.contains("0001=0002") }
        controller.sendModbusFrame(1, 0x06, "00 05 00 00", broadcastConfirmed = false)
        waitForMain(5_000, "安全拒绝 0005") { controller.lastResult.value.contains("0005") }
        ShadowLooper.idleMainLooper(250, TimeUnit.MILLISECONDS)
        saveScreenshot(outDir, "09_terminal.png")
    }
}
