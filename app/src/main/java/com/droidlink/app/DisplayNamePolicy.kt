package com.droidlink.app

object DisplayNamePolicy {
    fun sanitize(value: String): String = value
        .replace(Regex("[\\r\\n\\t\\p{Cc}]"), "")
        .filter { it.isLetterOrDigit() || it in " _-.'" }
        .replace(Regex("\\s{2,}"), " ")
        .trimStart()
        .take(16)

    fun effective(savedName: String, host: Boolean): String = savedName.trim().ifEmpty { if (host) "Player 1" else "Player 2" }
}
