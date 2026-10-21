package ua.tvremote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import ua.tvremote.HidDescriptor.ID_CONSUMER
import ua.tvremote.HidDescriptor.ID_KEYBOARD

/**
 * Перетворює телефон на Bluetooth HID-пристрій (клавіатура + пульт)
 * через системний API BluetoothHidDevice (Android 9+).
 * Дозволи BLUETOOTH_CONNECT перевіряє MainActivity до виклику start().
 */
@SuppressLint("MissingPermission")
class HidRemote(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onStatus(text: String, connected: Boolean)
    }

    private val adapter: BluetoothAdapter? =
        ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private val prefs = ctx.getSharedPreferences("remote", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    private var hid: BluetoothHidDevice? = null
    private var registered = false
    private var host: BluetoothDevice? = null
    private var pendingTarget: BluetoothDevice? = null

    val isConnected get() = host != null
    val bluetoothEnabled get() = adapter?.isEnabled == true

    fun bondedDevices(): List<BluetoothDevice> =
        adapter?.bondedDevices?.sortedBy { it.name ?: it.address } ?: emptyList()

    // ---------- Життєвий цикл ----------

    fun start() {
        val a = adapter ?: return status("Bluetooth недоступний на цьому телефоні", false)
        if (!a.isEnabled) return status("Увімкніть Bluetooth", false)
        if (hid != null) {
            if (!registered) register()
            return
        }
        status("Запуск…", false)
        val ok = a.getProfileProxy(ctx, serviceListener, BluetoothProfile.HID_DEVICE)
        if (!ok) status("Телефон не підтримує режим HID", false)
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        hid?.let {
            host?.let { d -> it.disconnect(d) }
            it.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it)
        }
        hid = null
        registered = false
        host = null
    }

    /** Підключитися до обраного (вже сполученого) телевізора. */
    fun connect(device: BluetoothDevice) {
        pendingTarget = device
        prefs.edit().putString(KEY_LAST, device.address).apply()
        val h = hid
        if (h == null || !registered) {
            start(); return
        }
        host?.takeIf { it.address != device.address }?.let { h.disconnect(it) }
        status("Підключення до ${device.name ?: device.address}…", false)
        h.connect(device)
    }

    fun disconnect() {
        host?.let { hid?.disconnect(it) }
    }

    private fun savedDevice(): BluetoothDevice? {
        val addr = prefs.getString(KEY_LAST, null) ?: return null
        return bondedDevices().firstOrNull { it.address == addr }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            hid = proxy as BluetoothHidDevice
            register()
        }

        override fun onServiceDisconnected(profile: Int) {
            hid = null; registered = false; host = null
            status("Bluetooth-сервіс зупинено", false)
        }
    }

    private fun register() {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "TV Remote", "Phone TV remote", "TvRemote",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            HidDescriptor.DESCRIPTOR
        )
        val ok = hid?.registerApp(sdp, null, null, ctx.mainExecutor, callback) == true
        if (!ok) status("Не вдалося зареєструвати пульт. Перезапустіть Bluetooth", false)
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            registered = isRegistered
            if (!isRegistered) {
                host = null
                return status("Пульт не активний", false)
            }
            val target = pendingTarget ?: pluggedDevice ?: savedDevice()
            if (target != null) {
                status("Підключення до ${target.name ?: target.address}…", false)
                hid?.connect(target)
            } else {
                status("Готово. Оберіть телевізор ↑", false)
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    pendingTarget = null
                    prefs.edit().putString(KEY_LAST, device.address).apply()
                    status("Підключено: ${device.name ?: device.address}", true)
                }
                BluetoothProfile.STATE_CONNECTING ->
                    status("Підключення…", false)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host == null || host?.address == device.address) {
                        host = null
                        status("Від'єднано від ${device.name ?: device.address}", false)
                    }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val size = if (id.toInt() == ID_CONSUMER) 2 else 8
            hid?.replyReport(device, type, id, ByteArray(size))
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }
    }

    // ---------- Надсилання кнопок ----------

    private fun send(id: Int, data: ByteArray): Boolean {
        val d = host ?: return false
        return hid?.sendReport(d, id, data) == true
    }

    fun keyDown(code: Int, modifiers: Int = 0) =
        send(ID_KEYBOARD, byteArrayOf(modifiers.toByte(), 0, code.toByte(), 0, 0, 0, 0, 0))

    fun keyUp() = send(ID_KEYBOARD, ByteArray(8))

    fun consumerDown(usage: Int) =
        send(ID_CONSUMER, byteArrayOf((usage and 0xFF).toByte(), ((usage shr 8) and 0xFF).toByte()))

    fun consumerUp() = send(ID_CONSUMER, ByteArray(2))

    /** Надрукувати текст (латиниця, цифри, знаки) — для пошуку на ТВ. */
    fun typeText(text: String, pressEnter: Boolean) {
        var delay = 0L
        val step = 35L
        for (ch in text) {
            val (code, mod) = KeyMap.forChar(ch) ?: continue
            main.postDelayed({ keyDown(code, mod) }, delay); delay += step
            main.postDelayed({ keyUp() }, delay); delay += step
        }
        if (pressEnter) {
            main.postDelayed({ keyDown(HidDescriptor.K_ENTER) }, delay); delay += step
            main.postDelayed({ keyUp() }, delay)
        }
    }

    private fun status(text: String, connected: Boolean) {
        main.post { listener.onStatus(text, connected) }
    }

    companion object {
        private const val KEY_LAST = "last_device"
    }
}
