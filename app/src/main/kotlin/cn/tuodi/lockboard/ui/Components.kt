package cn.tuodi.lockboard.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import cn.tuodi.lockboard.core.config.DipSwitch
import cn.tuodi.lockboard.core.testing.Verdict
import java.io.File

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** Integer field that commits only valid values within [range]. */
@Composable
fun IntField(label: String, value: Int, range: IntRange, modifier: Modifier = Modifier, onValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    val error = parsed == null || parsed !in range
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it.filter { c -> c.isDigit() }.take(7)
            text.toIntOrNull()?.takeIf { v -> v in range }?.let(onValue)
        },
        label = { Text(label) },
        isError = error,
        supportingText = if (error) { { Text("范围 ${range.first}–${range.last}") } } else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
fun <T> Dropdown(
    label: String,
    options: List<T>,
    selected: T,
    text: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
            Text("$label：${text(selected)}  ▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(text(o)) }, onClick = { expanded = false; onSelect(o) })
            }
        }
    }
}

@Composable
fun LabeledSwitch(label: String, checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun LabeledCheckbox(label: String, checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(label)
    }
}

/** Multi-select of channels 1..12. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChannelSelector(selected: Set<Int>, enabled: (Int) -> Boolean = { true }, onChange: (Set<Int>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (ch in 1..12) {
            FilterChip(
                selected = ch in selected,
                enabled = enabled(ch),
                onClick = { onChange(if (ch in selected) selected - ch else selected + ch) },
                label = { Text("$ch") },
            )
        }
    }
}

/** Picture of the 4-bit DIP switch (switch 1 = 2⁰). Tapping a switch toggles it when [onChange] is set. */
@Composable
fun DipSwitchView(address: Int, onChange: ((Int) -> Unit)? = null) {
    val sw = DipSwitch.switchesFor(address.coerceIn(0, 15))
    Row(
        Modifier.background(Color(0xFFD32F2F), RoundedCornerShape(4.dp)).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("ON", color = Color.White, fontSize = 10.sp)
        for (i in 0 until 4) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(width = 16.dp, height = 30.dp).background(Color(0xFFB71C1C)).border(1.dp, Color.White)
                        .let { m -> if (onChange != null) m.clickable { onChange(address xor (1 shl i)) } else m },
                ) {
                    Box(
                        Modifier.size(14.dp).padding(1.dp).background(Color.White)
                            .align(if (sw[i]) Alignment.TopCenter else Alignment.BottomCenter),
                    )
                }
                Text("${i + 1}", color = Color.White, fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String = "确定",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }, modifier = Modifier.testTag("confirm")) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

fun verdictColor(v: Verdict): Color = when (v) {
    Verdict.PASS -> PassColor
    Verdict.FAIL -> FailColor
    Verdict.WARN -> WarnColor
    Verdict.UNVERIFIED -> Color(0xFF6A1B9A)
    Verdict.SKIP -> UnknownColor
}

fun shareFile(context: Context, file: File, mime: String = "text/plain") {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "分享 ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
fun Hint(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
fun RowSpacer() = Spacer(Modifier.width(8.dp))

@Composable
fun <T> RememberEffect(key: T, block: suspend () -> Unit) = LaunchedEffect(key) { block() }
