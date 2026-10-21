package ua.tvremote

/** Відповідність символів кодам HID-клавіатури (розкладка US). */
object KeyMap {
    private const val SHIFT = 0x02

    private val plain = mapOf(
        ' ' to 0x2C, '\n' to 0x28,
        '-' to 0x2D, '=' to 0x2E, '[' to 0x2F, ']' to 0x30, '\\' to 0x31,
        ';' to 0x33, '\'' to 0x34, '`' to 0x35, ',' to 0x36, '.' to 0x37, '/' to 0x38
    )
    private val shifted = mapOf(
        '!' to 0x1E, '@' to 0x1F, '#' to 0x20, '$' to 0x21, '%' to 0x22,
        '^' to 0x23, '&' to 0x24, '*' to 0x25, '(' to 0x26, ')' to 0x27,
        '_' to 0x2D, '+' to 0x2E, '{' to 0x2F, '}' to 0x30, '|' to 0x31,
        ':' to 0x33, '"' to 0x34, '~' to 0x35, '<' to 0x36, '>' to 0x37, '?' to 0x38
    )

    /** Повертає (код, модифікатор) або null, якщо символ не підтримується. */
    fun forChar(c: Char): Pair<Int, Int>? = when (c) {
        in 'a'..'z' -> (0x04 + (c - 'a')) to 0
        in 'A'..'Z' -> (0x04 + (c - 'A')) to SHIFT
        in '1'..'9' -> (0x1E + (c - '1')) to 0
        '0' -> 0x27 to 0
        else -> plain[c]?.let { it to 0 } ?: shifted[c]?.let { it to SHIFT }
    }
}
