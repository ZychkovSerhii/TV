package ua.tvremote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        else emptyArray()

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

        if (hasPermissions()) startRemote() else requestPermissions(permissions, REQ_PERMS)
    }

    override fun onDestroy() {
        unregisterReceiver(btStateReceiver)
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
        val names = devices.map { it.name ?: it.address }.toMutableList()
        names += "➕ Новий телевізор (зробити телефон видимим)"
        if (remote.isConnected) names += "✖ Від'єднатися"

        AlertDialog.Builder(this)
            .setTitle("Телевізор")
            .setItems(names.toTypedArray()) { _, which ->
                when {
                    which < devices.size -> remote.connect(devices[which])
                    which == devices.size -> makeDiscoverable()
                    else -> remote.disconnect()
                }
            }
            .show()
    }

    private fun makeDiscoverable() {
        startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
        AlertDialog.Builder(this)
            .setTitle("Сполучення з ТВ")
            .setMessage(
                "Телефон буде видимим 2 хвилини.\n\n" +
                "На телевізорі відкрийте:\nНалаштування → Пульти й аксесуари → Створити пару з аксесуаром.\n\n" +
                "Оберіть у списку свій телефон і підтвердьте код на обох пристроях. " +
                "Після цього застосунок підключиться сам."
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
