package dev.lanbridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.ConcurrentHashMap

object DiscoveryProtocol {
    fun request(nonce: ByteArray): ByteArray { require(nonce.size == 16); return "LBD1".toByteArray() + nonce }
    fun verify(reply: ByteArray, nonce: ByteArray, host: String, code: String): Pairing? {
        if(reply.size != 54 || nonce.size != 16 || !reply.copyOfRange(0, 4).contentEquals("LBR1".toByteArray()) || !reply.copyOfRange(4, 20).contentEquals(nonce)) return null
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(code.toByteArray(Charsets.US_ASCII), "HmacSHA256")) }
        if(!MessageDigest.isEqual(mac.doFinal(reply.copyOfRange(0, 22)), reply.copyOfRange(22, 54))) return null
        val port = ByteBuffer.wrap(reply, 20, 2).short.toInt() and 65535
        return runCatching { Pairing.parse("$host:$port", code) }.getOrNull()
    }
}
class PairingDiscovery(private val context: Context) {
    private val manager get() = context.getSystemService(ConnectivityManager::class.java)
    private val wifiNetworks = ConcurrentHashMap<Network, Boolean>()
    init {
        manager.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { wifiNetworks[network] = true }
            override fun onLost(network: Network) { wifiNetworks.remove(network) }
        })
    }
    // Bind sockets to physical Wi-Fi so a VPN does not capture local pairing traffic.
    fun wifi(): Network? = wifiNetworks.keys.firstOrNull() ?: manager.activeNetwork?.takeIf { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    private fun bindingRejected(e: Exception) { android.util.Log.w("LanBridge", "Wi-Fi binding unavailable; using system route", e) }
    fun tcpSocket(): Socket {
        val network = wifi()
        return routeSocket(::Socket, network?.let { net -> { socket: Socket -> net.bindSocket(socket) } }, ::bindingRejected).socket
    }
    fun find(pair: Pairing): Pairing? {
        val network = wifi()
        val routed = routeSocket(::DatagramSocket, network?.let { net -> { socket: DatagramSocket -> net.bindSocket(socket) } }, ::bindingRejected)
        routed.socket.use { socket ->
            val broadcasts = manager.getLinkProperties(if(routed.preferredNetwork) network else manager.activeNetwork)?.linkAddresses.orEmpty().mapNotNull { link ->
                val address = link.address as? Inet4Address ?: return@mapNotNull null
                if(link.prefixLength !in 1..30) return@mapNotNull null
                val host = ByteBuffer.wrap(address.address).int
                InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(host or (-1 ushr link.prefixLength)).array())
            }.ifEmpty { listOf(InetAddress.getByName("255.255.255.255")) }
            val nonce = ByteArray(16).also(SecureRandom()::nextBytes); val request = DiscoveryProtocol.request(nonce)
            socket.broadcast = true; socket.soTimeout = 250
            for(address in broadcasts) socket.send(DatagramPacket(request, request.size, address, 42832))
            val deadline = System.nanoTime() + 1_000_000_000L
            while(System.nanoTime() < deadline) {
                val packet = DatagramPacket(ByteArray(256), 256)
                try { socket.receive(packet) } catch(_: SocketTimeoutException) { continue }
                DiscoveryProtocol.verify(packet.data.copyOf(packet.length), nonce, packet.address.hostAddress ?: continue, pair.code)?.let { return it }
            }
        }
        return null
    }
}
