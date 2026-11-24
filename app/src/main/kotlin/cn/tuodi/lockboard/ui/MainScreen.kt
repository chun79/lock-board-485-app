package cn.tuodi.lockboard.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.tuodi.lockboard.app.AppController
import cn.tuodi.lockboard.app.LinkStatus
import cn.tuodi.lockboard.core.testing.Answer
import cn.tuodi.lockboard.transport.UsbAdapters

private enum class Tab(val label: String, val icon: ImageVector) {
    CONNECT("连接", Icons.Filled.Build),
    CONTROL("控制", Icons.Filled.Lock),
    TEST("测试", Icons.Filled.PlayArrow),
    TERMINAL("终端", Icons.AutoMirrored.Filled.List),
    SETTINGS("配置", Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(controller: AppController, usb: UsbAdapters?) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val link by controller.link.collectAsStateWithLifecycle()
    val busy by controller.busy.collectAsStateWithLifecycle()
    val board by controller.board.collectAsStateWithLifecycle()
    val prompt by controller.prompt.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { controller.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("485 锁控板测试", style = MaterialTheme.typography.titleMedium)
                    val status = when (link.status) {
                        LinkStatus.CONNECTED -> "${link.description} · 当前地址 ${board.address}"
                        LinkStatus.PROBING, LinkStatus.NO_BOARD -> "${link.status.label} · ${link.description}"
                        else -> link.status.label + if (link.message.isNotBlank()) "：${link.message}" else ""
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("linkStatus"),
                        color = when (link.status) {
                            LinkStatus.CONNECTED -> OpenColor
                            LinkStatus.NO_BOARD -> WarnColor
                            LinkStatus.LOST -> FailColor
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        })
                }
            })
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                        modifier = Modifier.testTag("tab_${t.name}"),
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (Tab.entries[tab]) {
                Tab.CONNECT -> ConnectScreen(controller, usb)
                Tab.CONTROL -> ControlScreen(controller)
                Tab.TEST -> TestScreen(controller)
                Tab.TERMINAL -> TerminalScreen(controller)
                Tab.SETTINGS -> SettingsScreen(controller)
            }
            if (busy != null) LinearProgressIndicator(Modifier.testTag("busy"))
        }
    }

    prompt?.let { p ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(if (p.isRequest) "请操作" else "请观察并确认") },
            text = { Text(p.text, modifier = Modifier.testTag("promptText")) },
            confirmButton = {
                if (p.isRequest) TextButton(onClick = { controller.answerPrompt(Answer.YES) }, modifier = Modifier.testTag("promptDone")) { Text("已完成") }
                else TextButton(onClick = { controller.answerPrompt(Answer.YES) }, modifier = Modifier.testTag("promptYes")) { Text("是") }
            },
            dismissButton = {
                androidx.compose.foundation.layout.Row {
                    // Stops the test immediately; outputs are then switched off explicitly.
                    TextButton(onClick = { controller.stopTest() }, modifier = Modifier.testTag("promptStop")) { Text("停止测试", color = FailColor) }
                    if (!p.isRequest) {
                        TextButton(onClick = { controller.answerPrompt(Answer.SKIP) }, modifier = Modifier.testTag("promptSkip")) { Text("无法观察") }
                        TextButton(onClick = { controller.answerPrompt(Answer.NO) }, modifier = Modifier.testTag("promptNo")) { Text("否") }
                    }
                }
            },
        )
    }
}
