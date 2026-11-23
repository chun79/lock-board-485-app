package cn.tuodi.lockboard.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import cn.tuodi.lockboard.core.client.ByteTransport
import cn.tuodi.lockboard.core.client.TransportKind
import cn.tuodi.lockboard.core.config.Parity
import cn.tuodi.lockboard.core.config.SerialParams
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume

data class UsbAdapterInfo(
    val deviceName: String,
    val label: String,
    val hasPermission: Boolean,
)

/** USB OTG ↔ RS485 adapters (CH34x, CP210x, FTDI, PL2303, CDC-ACM) via usb-serial-for-android. */
class UsbAdapters(private val context: Context) {
    private val usbManager: UsbManager? = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    val hostSupported: Boolean
        get() = usbManager != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    private fun drivers(): List<UsbSerialDriver> =
        usbManager?.let { UsbSerialProber.getDefaultProber().findAllDrivers(it) } ?: emptyList()

    fun list(): List<UsbAdapterInfo> = drivers().map { d ->
        val dev = d.device
        val chip = d.javaClass.simpleName.removeSuffix("SerialDriver")
        UsbAdapterInfo(
            deviceName = dev.deviceName,
            label = "$chip  VID:PID %04X:%04X  ${dev.productName ?: ""}".format(dev.vendorId, dev.productId).trim(),
            hasPermission = usbManager!!.hasPermission(dev),
        )
    }

    /** Requests permission for [deviceName]; returns true when granted. */
    suspend fun requestPermission(deviceName: String): Boolean {
        val manager = usbManager ?: return false
        val device = manager.deviceList[deviceName] ?: return false
        if (manager.hasPermission(device)) return true
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    runCatching { context.unregisterReceiver(this) }
                    if (cont.isActive) cont.resume(manager.hasPermission(device))
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            // Explicit (package-scoped) intent; the system must fill in extras, hence mutable on API 31+.
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            manager.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
        }
    }

    @Throws(IOException::class)
    fun open(deviceName: String, params: SerialParams): UsbTransport {
        val manager = usbManager ?: throw IOException("此设备不支持 USB Host")
        val driver = drivers().firstOrNull { it.device.deviceName == deviceName }
            ?: throw IOException("未找到 USB 转串口设备（已拔出或芯片不受支持）")
        if (!manager.hasPermission(driver.device)) throw IOException("未获得 USB 设备访问授权")
        val connection = manager.openDevice(driver.device) ?: throw IOException("打开 USB 设备失败")
        val port = driver.ports.firstOrNull() ?: run { connection.close(); throw IOException("USB 设备没有串口") }
        try {
            port.open(connection)
            val parity = when (params.parity) {
                Parity.NONE -> UsbSerialPort.PARITY_NONE
                Parity.ODD -> UsbSerialPort.PARITY_ODD
                Parity.EVEN -> UsbSerialPort.PARITY_EVEN
            }
            val stop = if (params.stopBits == 2) UsbSerialPort.STOPBITS_2 else UsbSerialPort.STOPBITS_1
            port.setParameters(params.baud, params.dataBits, stop, parity)
        } catch (e: Exception) {
            runCatching { port.close() }
            throw IOException("配置 USB 串口失败：${e.message}", e)
        }
        return UsbTransport(port, driver.device, "${driver.javaClass.simpleName.removeSuffix("SerialDriver")} ${params.describe()}")
    }

    /** Calls [onDetached] with the device name whenever a USB device is removed. */
    fun watchDetach(onDetached: (String) -> Unit): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val dev = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                onDetached(dev.deviceName)
            }
        }
        // System broadcast: must be receivable from the system, so it is registered as exported.
        ContextCompat.registerReceiver(context, receiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), ContextCompat.RECEIVER_EXPORTED)
        return { runCatching { context.unregisterReceiver(receiver) } }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "cn.tuodi.lockboard485.USB_PERMISSION"
    }
}

class UsbTransport internal constructor(
    private val port: UsbSerialPort,
    val device: UsbDevice,
    private val label: String,
) : ByteTransport {
    override val kind = TransportKind.USB
    override val description get() = "$label（${device.deviceName}${serial?.let { "，序列号 $it" } ?: "，无序列号"}）"

    /** Adapter serial number (readable once permission is granted); many CH340 adapters have none. */
    val serial: String? = runCatching { device.serialNumber }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    @Volatile
    private var closed = false

    private fun checkOpen() { if (closed || !port.isOpen) throw IOException("USB 串口已关闭或已拔出") }

    override fun write(bytes: ByteArray) {
        checkOpen()
        port.write(bytes, WRITE_TIMEOUT_MS)
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        checkOpen()
        // timeout 0 would block forever in usb-serial-for-android.
        return port.read(buffer, timeoutMs.coerceIn(1, 50))
    }

    override fun drainInput(): ByteArray {
        checkOpen()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(maxOf(64, port.readEndpoint?.maxPacketSize ?: 64))
        while (true) {
            val n = port.read(buf, 1)
            if (n <= 0) return out.toByteArray()
            out.write(buf, 0, n)
            // A backlog beyond 64 KiB fails instead of leaking old frames into the next request.
            if (out.size() >= 65_536) throw IOException("USB 串口积压数据超过 65536 字节，总线未清空")
        }
    }

    override fun close() {
        closed = true
        runCatching { port.close() }
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 1000
    }
}
