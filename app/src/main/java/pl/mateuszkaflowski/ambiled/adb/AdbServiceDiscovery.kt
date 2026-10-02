package pl.mateuszkaflowski.ambiled.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** mDNS service types advertised by adbd's wireless debugging. */
enum class AdbService(val type: String) {
    PAIRING("_adb-tls-pairing._tcp"),
}

/**
 * Finds the port of this device's own wireless debugging service. adbd picks a random port
 * each time wireless debugging starts and only announces it over mDNS; other devices on the
 * same Wi-Fi may announce theirs too, so results are filtered by our own addresses.
 */
object AdbServiceDiscovery {

    private const val TAG = "AdbServiceDiscovery"

    suspend fun findOwnPort(context: Context, service: AdbService, timeoutMs: Long): Int =
        withTimeout(timeoutMs) { discover(context, service) }

    private suspend fun discover(context: Context, service: AdbService): Int =
        suspendCancellableCoroutine { cont ->
            val nsd = context.getSystemService(NsdManager::class.java)
            val own = localAddresses()
            val lock = Any()
            val pending = ArrayDeque<NsdServiceInfo>()
            var resolving = false

            lateinit var discoveryListener: NsdManager.DiscoveryListener

            fun finish(result: Result<Int>) {
                runCatching { nsd.stopServiceDiscovery(discoveryListener) }
                if (cont.isActive) result.fold(cont::resume, cont::resumeWithException)
            }

            // NsdManager resolves one service at a time, so found services are queued.
            fun resolveNext() {
                val next = synchronized(lock) {
                    if (resolving || !cont.isActive) return
                    pending.removeFirstOrNull()?.also { resolving = true } ?: return
                }
                @Suppress("DEPRECATION") // ServiceInfoCallback replacement is API 34+.
                nsd.resolveService(next, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        synchronized(lock) { resolving = false }
                        Log.d(TAG, "Resolved ${info.serviceName}: ${info.host}:${info.port}, own=${info.host in own}")
                        if (info.host in own) finish(Result.success(info.port)) else resolveNext()
                    }

                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        synchronized(lock) { resolving = false }
                        Log.d(TAG, "Resolve of ${info.serviceName} failed: $errorCode")
                        resolveNext()
                    }
                })
            }

            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) {
                    Log.d(TAG, "Found ${info.serviceName} (${info.serviceType})")
                    synchronized(lock) { pending.addLast(info) }
                    resolveNext()
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("mDNS discovery failed: $errorCode"))
                }

                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onServiceLost(info: NsdServiceInfo) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }

            nsd.discoverServices(service.type, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            cont.invokeOnCancellation { runCatching { nsd.stopServiceDiscovery(discoveryListener) } }
        }

    private fun localAddresses(): Set<InetAddress> =
        NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .toSet()
}
