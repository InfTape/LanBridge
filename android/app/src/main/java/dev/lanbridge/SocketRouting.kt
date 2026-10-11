package dev.lanbridge

import java.io.Closeable
import java.io.IOException

internal data class RoutedSocket<T>(val socket: T, val preferredNetwork: Boolean)

// A rejected bind may leave a socket partially configured. Always close it and
// create a fresh default-route socket, rather than reusing the failed candidate.
internal fun <T : Closeable> routeSocket(create: () -> T, bind: ((T) -> Unit)?, rejected: (Exception) -> Unit): RoutedSocket<T> {
    val socket = create()
    if(bind == null) return RoutedSocket(socket, false)
    try {
        bind(socket)
        return RoutedSocket(socket, true)
    } catch(e: Exception) {
        runCatching { socket.close() }
        if(e !is IOException && e !is SecurityException) throw e
        rejected(e)
        return RoutedSocket(create(), false)
    }
}
