package ua.tvremote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.widget.ArrayAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

@SuppressLint("MissingPermission", "ClickableViewAccessibility")
class MainActivity : AppCompatActivity(), HidRemote.Listener {

    private lateinit var remote: HidRemote
    private lateinit var statusText: TextView
    private lateinit var statusDot: View

    private val permissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN
            )
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private val btAdapter: BluetoothAdapter? by lazy {
        getSystemService(BluetoothManager::class.java)?.adapter
    }

    // ---- Пошук телевізора з боку телефона ----
    private val found = mutableListOf<BluetoothDevice>()
    private var foundAdapter: ArrayAdapter<String>? = null
    private var scanDialog: AlertDialog? = null
    private var bondingTarget: BluetoothDevice? = null

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d = deviceFrom(i) ?: return
                    if (found.none { it.address == d.address }) {
                        found += d
                        foundAdapter?.add(label(d))
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED ->
                    scanDialog?.setTitle(if (found.isEmpty()) "Нічого не знайдено" else "Оберіть телевізор")
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val d = deviceFrom(i) ?: return
                    if (d.address != bondingTarget?.address) return
                    when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                        BluetoothDevice.BOND_BONDED -> {
                            bondingTarget = null
                            onStatus("Сполучено. Підключення…", false)
                            remote.connect(d)
                        }
                        BluetoothDevice.BOND_NONE -> {
                            bondingTarget = null
                            onStatus("Сполучення не вдалося. Спробуйте ще раз", false)
                        }
                    }
                }
            }
        }
    }

    private fun deviceFrom(i: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33)
            i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private fun label(d: BluetoothDevice): String {
        val name = d.name
        val kind = when (d.bluetoothClass?.majorDeviceClass) {
            BluetoothClass.Device.Major.AUDIO_VIDEO -> "📺 "
            BluetoothClass.Device.Major.PHONE -> "📱 "
            BluetoothClass.Device.Major.COMPUTER -> "💻 "
            else -> ""
        }
        return if (name.isNullOrBlank()) "$kind${d.address}" else "$kind$name"
    }

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> if (hasPermissions()) remote.start()
                BluetoothAdapter.STATE_OFF -> { remote.stop(); onStatus("Увімкніть Bluetooth", false) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        remote = HidRemote(this, this)
        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)

        bindKeyboard(R.id.btnUp, HidDescriptor.K_UP)
        bindKeyboard(R.id.btnDown, HidDescriptor.K_DOWN)
        bindKeyboard(R.id.btnLeft, HidDescriptor.K_LEFT)
        bindKeyboard(R.id.btnRight, HidDescriptor.K_RIGHT)
        bindKeyboard(R.id.btnOk, HidDescriptor.K_ENTER)
        bindKeyboard(R.id.btnBackspace, HidDescriptor.K_BACKSPACE)

        bindConsumer(R.id.btnPower, HidDescriptor.C_POWER)
        bindConsumer(R.id.btnHome, HidDescriptor.C_HOME)
        bindConsumer(R.id.btnBack, HidDescriptor.C_BACK)
        bindConsumer(R.id.btnMenu, HidDescriptor.C_MENU)
        bindConsumer(R.id.btnSearch, HidDescriptor.C_SEARCH)
        bindConsumer(R.id.btnVolUp, HidDescriptor.C_VOL_UP)
        bindConsumer(R.id.btnVolDown, HidDescriptor.C_VOL_DOWN)
        bindConsumer(R.id.btnMute, HidDescriptor.C_MUTE)
        bindConsumer(R.id.btnPrev, HidDescriptor.C_PREV)
        bindConsumer(R.id.btnPlay, HidDescriptor.C_PLAY_PAUSE)
        bindConsumer(R.id.btnNext, HidDescriptor.C_NEXT)

        findViewById<Button>(R.id.btnDevice).setOnClickListener { chooseDevice() }

        val input = findViewById<EditText>(R.id.textInput)
        val sendText = {
            val t = input.text.toString()
            if (t.isNotEmpty()) {
                if (!remote.isConnected) toast("Спочатку підключіться до ТВ")
                else {
                    if (t.any { KeyMap.forChar(it) == null })
                        toast("Кирилиця й емодзі не передаються — лише латиниця")
                    remote.typeText(t, pressEnter = true)
                    input.text.clear()
                }
            }
        }
        findViewById<Button>(R.id.btnSendText).setOnClickListener { sendText() }
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { sendText(); true } else false
        }

        registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        registerReceiver(scanReceiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        })

        if (hasPermissions()) startRemote() else requestPermissions(permissions, REQ_PERMS)
    }

    override fun onDestroy() {
        unregisterReceiver(btStateReceiver)
        unregisterReceiver(scanReceiver)
        btAdapter?.cancelDiscovery()
        remote.stop()
        super.onDestroy()
    }

    private fun hasPermissions() = permissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_PERMS) {
            if (hasPermissions()) startRemote()
            else onStatus("Потрібен дозвіл «Пристрої поблизу»", false)
        }
    }

    private fun startRemote() {
        if (!remote.bluetoothEnabled) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            onStatus("Увімкніть Bluetooth", false)
        } else remote.start()
    }

    // ---------- Кнопки ----------

    /** Натискання/відпускання передаються окремо — утримання кнопки працює як на справжньому пульті. */
    private fun bindKeyboard(id: Int, code: Int) = bindPress(id,
        down = { remote.keyDown(code) }, up = { remote.keyUp() })

    private fun bindConsumer(id: Int, usage: Int) = bindPress(id,
        down = { remote.consumerDown(usage) }, up = { remote.consumerUp() })

    private fun bindPress(id: Int, down: () -> Boolean, up: () -> Boolean) {
        findViewById<View>(id).setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    if (!down()) toast(if (hasPermissions()) "Немає з'єднання з ТВ" else "Немає дозволу Bluetooth")
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    up()
                }
            }
            true
        }
    }

    // ---------- Вибір телевізора ----------

    private fun chooseDevice() {
        if (!hasPermissions()) { requestPermissions(permissions, REQ_PERMS); return }
        if (!remote.bluetoothEnabled) { startRemote(); return }

        val devices = remote.bondedDevices()
        val names = devices.map { "🔗 " + label(it) }.toMutableList()
        names += "🔍 Знайти телевізор (пошук з телефона)"
        names += "👁 Зробити телефон видимим для ТВ"
        if (remote.isConnected) names += "✖ Від'єднатися"

        AlertDialog.Builder(this)
            .setTitle("Телевізор")
            .setItems(names.toTypedArray()) { _, which ->
                when {
                    which < devices.size -> remote.connect(devices[which])
                    which == devices.size -> scanForTv()
                    which == devices.size + 1 -> makeDiscoverable()
                    else -> remote.disconnect()
                }
            }
            .show()
    }

    /** Телефон сам шукає телевізор і ініціює сполучення — працює, коли ТВ не показує телефон у своєму списку. */
    private fun scanForTv() {
        val a = btAdapter ?: return
        found.clear()
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
        foundAdapter = adapter
        // Спочатку показуємо вже відомі пристрої, які телефон бачив раніше
        scanDialog = AlertDialog.Builder(this)
            .setTitle("Пошук… (1–2 хв)")
            .setMessage(null)
            .setAdapter(adapter) { _, which ->
                val d = found.getOrNull(which) ?: return@setAdapter
                a.cancelDiscovery()
                pairWith(d)
            }
            .setNegativeButton("Скасувати") { _, _ -> a.cancelDiscovery() }
            .setOnDismissListener { foundAdapter = null; scanDialog = null }
            .show()

        AlertDialog.Builder(this)
            .setTitle("Підготуйте телевізор")
            .setMessage(
                "Щоб телевізор було видно, на ТВ відкрийте:\n" +
                "Налаштування → Пульти й аксесуари → Створити пару з аксесуаром\n\n" +
                "і залиште цей екран відкритим. Телевізор з'явиться у списку з назвою моделі (напр. «55V6B» або «Smart TV»)."
            )
            .setPositiveButton("ОК", null)
            .show()

        if (a.isDiscovering) a.cancelDiscovery()
        if (!a.startDiscovery()) toast("Не вдалося почати пошук")
    }

    private fun pairWith(d: BluetoothDevice) {
        if (d.bondState == BluetoothDevice.BOND_BONDED) {
            remote.connect(d); return
        }
        bondingTarget = d
        onStatus("Сполучення з ${d.name ?: d.address}… Підтвердьте код на ТВ і телефоні", false)
        if (!d.createBond()) {
            bondingTarget = null
            onStatus("Не вдалося почати сполучення", false)
        }
    }

    private fun makeDiscoverable() {
        startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
        AlertDialog.Builder(this)
            .setTitle("Сполучення з ТВ")
            .setMessage(
                "Телефон буде видимим 5 хвилин.\n\n" +
                "На телевізорі відкрийте:\nНалаштування → Пульти й аксесуари → Створити пару з аксесуаром.\n\n" +
                "Якщо телефона в списку ТВ немає — скористайтеся пунктом «🔍 Знайти телевізор»."
            )
            .setPositiveButton("Зрозуміло", null)
            .show()
    }

    // ---------- Статус ----------

    override fun onStatus(text: String, connected: Boolean) {
        statusText.text = text
        statusDot.setBackgroundResource(if (connected) R.drawable.dot_on else R.drawable.dot_off)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_PERMS = 1
    }
}
