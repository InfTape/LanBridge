package dev.lanbridge

internal object DeviceNames {
    fun normalize(value: String): String {
        require(value.none { it.isISOControl() }) { "设备名称不能包含换行或控制字符" }
        val name = value.trim()
        require(name.codePointCount(0, name.length) <= 32) { "设备名称最多 32 个字符" }
        return name
    }

    // The pairing identity survives address changes. A different pairing code
    // represents a new device/binding; its local display name must start afresh.
    fun retain(previous: Pairing?, next: Pairing, name: String): String =
        if(previous?.code == next.code) normalize(name) else ""
}
