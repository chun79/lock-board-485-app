package cn.tuodi.lockboard

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.tuodi.lockboard.app.LinkStatus
import cn.tuodi.lockboard.app.LockBoardApp
import cn.tuodi.lockboard.core.sim.SimBoardConfig
import cn.tuodi.lockboard.core.sim.SimConfig
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** UI smoke test on Android 11 (API 30): connect to the simulator, scan, unlock from the grid. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val controller get() = (compose.activity.application as LockBoardApp).controller

    @Before fun configure() {
        controller.updateConfig {
            it.copy(sim = SimConfig(boards = listOf(SimBoardConfig(2)), autoCloseMs = 5000, responseDelayMs = 1, pulseOnMs = 10, pulseSettleMs = 5), scanTimeoutMs = 60)
        }
    }

    @Test fun connectScanAndUnlockFromUi() {
        compose.onNodeWithTag("connect").performScrollTo().performClick()
        compose.waitUntil(10_000) { controller.link.value.status == LinkStatus.CONNECTED }
        compose.onNodeWithTag("linkStatus").assertTextContains("模拟器", substring = true)

        compose.onNodeWithTag("scan").performScrollTo().performClick()
        compose.waitUntil(15_000) { controller.scan.value.finishedAtMillis != null }
        compose.waitUntil(5_000) { controller.board.value.address == 2 }
        compose.onNodeWithTag("scan_2").performScrollTo().assertTextContains("在线", substring = true)

        compose.onNodeWithTag("tab_CONTROL").performClick()
        compose.onNodeWithTag("unlock_5").performScrollTo().performClick()
        compose.waitUntil(10_000) { controller.channelStateText(5) == "打开" }
        compose.onNodeWithTag("state_5").performScrollTo().assertTextContains("打开")

        compose.onNodeWithTag("tab_TERMINAL").performClick()
        compose.waitUntil(5_000) { controller.logEntries.value.isNotEmpty() }
        compose.onNodeWithTag("tab_SETTINGS").performClick()
        compose.onNodeWithText("拨码地址换算").performScrollTo()
        compose.onNodeWithTag("tab_TEST").performClick()
        compose.onNodeWithTag("startTest").performScrollTo()
    }
}
