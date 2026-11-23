package cn.tuodi.lockboard.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val OpenColor = Color(0xFF2E7D32)
val ClosedColor = Color(0xFF546E7A)
val UnknownColor = Color(0xFF9E9E9E)
val WarnColor = Color(0xFFE65100)
val FailColor = Color(0xFFC62828)
val PassColor = Color(0xFF2E7D32)

private val Light = lightColorScheme(primary = Color(0xFF1565C0), secondary = Color(0xFF00796B), tertiary = Color(0xFFE65100))
private val Dark = darkColorScheme(primary = Color(0xFF90CAF9), secondary = Color(0xFF80CBC4), tertiary = Color(0xFFFFB74D))

@Composable
fun LockBoardTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
