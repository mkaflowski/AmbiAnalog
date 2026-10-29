package pl.mateuszkaflowski.ambiled.adb

/** Matches adbd's precedence: a nonempty service property overrides the persistent one. */
internal object AdbPorts {
    fun tcp(servicePort: String, persistentPort: String): Int? =
        parse(servicePort.trim().ifEmpty { persistentPort.trim() })

    fun parse(value: String): Int? = value.trim().toIntOrNull()?.takeIf { it in 1..65535 }
}
