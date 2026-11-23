package cn.tuodi.lockboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import cn.tuodi.lockboard.app.LockBoardApp
import cn.tuodi.lockboard.ui.LockBoardTheme
import cn.tuodi.lockboard.ui.MainScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LockBoardApp
        setContent {
            LockBoardTheme { MainScreen(app.controller, app.usb) }
        }
    }
}
