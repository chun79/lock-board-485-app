package cn.tuodi.lockboard.app

import android.app.Application
import cn.tuodi.lockboard.BuildConfig
import cn.tuodi.lockboard.transport.UsbAdapters
import kotlinx.coroutines.MainScope
import java.io.File

class LockBoardApp : Application() {
    lateinit var usb: UsbAdapters
        private set
    lateinit var controller: AppController
        private set
    private var stopUsbWatch: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        usb = UsbAdapters(this)
        controller = AppController(
            store = ConfigStore(this),
            factory = AndroidTransportFactory(usb),
            scope = MainScope(),
            exportDir = File(filesDir, "exports").apply { mkdirs() },
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        )
        stopUsbWatch = usb.watchDetach { controller.onUsbDetached(it) }
    }
}
