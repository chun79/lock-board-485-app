package cn.tuodi.lockboard.app

import android.content.Context
import cn.tuodi.lockboard.core.client.ByteTransport
import cn.tuodi.lockboard.core.config.AppConfig
import cn.tuodi.lockboard.core.config.TransportType
import cn.tuodi.lockboard.core.sim.SimulatedBus
import cn.tuodi.lockboard.core.tcp.TcpTransport
import cn.tuodi.lockboard.transport.UartTransport
import cn.tuodi.lockboard.transport.UsbAdapters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** [busId]: stable identity of the adapter when the link has one (USB: VID:PID:serial), else null. */
data class OpenedTransport(val transport: ByteTransport, val note: String? = null, val busId: String? = null)

fun interface TransportFactory {
    @Throws(IOException::class)
    suspend fun open(config: AppConfig): OpenedTransport
}

class AndroidTransportFactory(private val usb: UsbAdapters) : TransportFactory {
    override suspend fun open(config: AppConfig): OpenedTransport {
        val c = config.connection
        return when (c.type) {
            TransportType.SIMULATOR -> OpenedTransport(SimulatedBus(config.sim))
            TransportType.TCP -> withContext(Dispatchers.IO) { OpenedTransport(TcpTransport.connect(c.tcpHost.trim(), c.tcpPort)) }
            TransportType.UART -> withContext(Dispatchers.IO) {
                val t = UartTransport.open(c.uartPath.trim(), config.serial, c.uartRs485Ioctl)
                OpenedTransport(t, t.rs485Status)
            }
            TransportType.USB -> {
                if (!usb.hostSupported) throw IOException("此设备不支持 USB Host（OTG）")
                val name = c.usbDeviceName.ifBlank { usb.list().firstOrNull()?.deviceName }
                    ?: throw IOException("未发现 USB 转 485 适配器")
                if (!usb.requestPermission(name)) throw IOException("USB 访问授权被拒绝")
                withContext(Dispatchers.IO) {
                    val t = usb.open(name, config.serial)
                    OpenedTransport(t, busId = t.serial?.let { "USB:%04X:%04X:%s".format(t.device.vendorId, t.device.productId, it) })
                }
            }
        }
    }
}

@Suppress("unused")
fun Context.exportDir() = java.io.File(filesDir, "exports").apply { mkdirs() }
