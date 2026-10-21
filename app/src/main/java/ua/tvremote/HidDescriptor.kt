package ua.tvremote

/**
 * HID-дескриптор: телефон представляється як "клавіатура + мультимедійний пульт".
 *  Report ID 1 — стандартна клавіатура (8 байт: модифікатори, резерв, 6 клавіш)
 *  Report ID 2 — Consumer Control (16-бітний код кнопки: гучність, Home, Back…)
 */
object HidDescriptor {
    const val ID_KEYBOARD = 1
    const val ID_CONSUMER = 2

    // Consumer-коди, які Android/Google TV розуміє з коробки (Generic.kl)
    const val C_POWER = 0x0030
    const val C_MENU = 0x0040
    const val C_PLAY_PAUSE = 0x00CD
    const val C_NEXT = 0x00B5
    const val C_PREV = 0x00B6
    const val C_MUTE = 0x00E2
    const val C_VOL_UP = 0x00E9
    const val C_VOL_DOWN = 0x00EA
    const val C_SEARCH = 0x0221
    const val C_HOME = 0x0223
    const val C_BACK = 0x0224

    // Коди клавіатури (HID Usage Page 0x07)
    const val K_ENTER = 0x28
    const val K_BACKSPACE = 0x2A
    const val K_RIGHT = 0x4F
    const val K_LEFT = 0x50
    const val K_DOWN = 0x51
    const val K_UP = 0x52

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    val DESCRIPTOR: ByteArray = b(
        // ---- Клавіатура ----
        0x05, 0x01,       // Usage Page (Generic Desktop)
        0x09, 0x06,       // Usage (Keyboard)
        0xA1, 0x01,       // Collection (Application)
        0x85, ID_KEYBOARD,//   Report ID (1)
        0x05, 0x07,       //   Usage Page (Keyboard)
        0x19, 0xE0,       //   Usage Minimum (Left Ctrl)
        0x29, 0xE7,       //   Usage Maximum (Right GUI)
        0x15, 0x00,       //   Logical Minimum (0)
        0x25, 0x01,       //   Logical Maximum (1)
        0x75, 0x01,       //   Report Size (1)
        0x95, 0x08,       //   Report Count (8)
        0x81, 0x02,       //   Input (Data, Var, Abs) — модифікатори
        0x95, 0x01,       //   Report Count (1)
        0x75, 0x08,       //   Report Size (8)
        0x81, 0x01,       //   Input (Const) — резерв
        0x95, 0x06,       //   Report Count (6)
        0x75, 0x08,       //   Report Size (8)
        0x15, 0x00,       //   Logical Minimum (0)
        0x25, 0x65,       //   Logical Maximum (101)
        0x05, 0x07,       //   Usage Page (Keyboard)
        0x19, 0x00,       //   Usage Minimum (0)
        0x29, 0x65,       //   Usage Maximum (101)
        0x81, 0x00,       //   Input (Data, Array) — натиснуті клавіші
        0xC0,             // End Collection

        // ---- Consumer Control (пульт) ----
        0x05, 0x0C,       // Usage Page (Consumer)
        0x09, 0x01,       // Usage (Consumer Control)
        0xA1, 0x01,       // Collection (Application)
        0x85, ID_CONSUMER,//   Report ID (2)
        0x15, 0x00,       //   Logical Minimum (0)
        0x26, 0xFF, 0x03, //   Logical Maximum (0x3FF)
        0x19, 0x00,       //   Usage Minimum (0)
        0x2A, 0xFF, 0x03, //   Usage Maximum (0x3FF)
        0x75, 0x10,       //   Report Size (16)
        0x95, 0x01,       //   Report Count (1)
        0x81, 0x00,       //   Input (Data, Array)
        0xC0              // End Collection
    )
}
