package io.github.strongsand.lshell.beacon

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

interface BeaconDiscovery {
    fun discover(): Flow<Result<BeaconDevice>>
}

class AndroidNsdBeaconDiscovery(context: Context) : BeaconDiscovery {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NsdManager::class.java)

    override fun discover(): Flow<Result<BeaconDevice>> = callbackFlow {
        var resolving = false
        val multicastLock = runCatching {
            appContext.getSystemService(WifiManager::class.java)
                ?.createMulticastLock("lshell-beacon-discovery")
                ?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                trySend(Result.failure(BeaconDiscoveryException(errorCode)))
                close()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType?.startsWith(SERVICE_TYPE.removeSuffix(".")) != true || resolving) return
                resolving = true
                @Suppress("DEPRECATION")
                manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { resolving = false }
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        // Always release the single-flight gate. Some Android NSD implementations
                        // deliver a resolved service without an address; keeping it locked here made
                        // every later service announcement invisible until the screen was reopened.
                        resolving = false
                        val attributes = info.attributes.mapValues { (_, value) ->
                            value.toString(StandardCharsets.UTF_8)
                        }
                        val host = info.host?.hostAddress ?: return
                        val id = attributes["id"]?.takeIf { it.isNotBlank() }
                            ?: "${info.serviceName}@$host:${info.port}"
                        trySend(Result.success(BeaconDevice(
                            id = id,
                            name = info.serviceName.ifBlank { DEFAULT_HOSTNAME },
                            host = host,
                            port = info.port,
                            firmwareVersion = attributes["fw"] ?: attributes["firmware"] ?: attributes["version"],
                            status = attributes["status"],
                            protocolVersion = attributes["proto"]?.toIntOrNull()
                        )))
                    }
                })
            }
        }
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: RuntimeException) {
            trySend(Result.failure(error))
            close(error)
        }
        awaitClose {
            runCatching { manager.stopServiceDiscovery(listener) }
            runCatching { if (multicastLock?.isHeld == true) multicastLock.release() }
        }
    }

    private class BeaconDiscoveryException(code: Int) :
        IllegalStateException("NSD error $code")

    companion object {
        const val SERVICE_TYPE = "_lshell-beacon._tcp."
        const val DEFAULT_HOSTNAME = "lshell-beacon.local"
    }
}
