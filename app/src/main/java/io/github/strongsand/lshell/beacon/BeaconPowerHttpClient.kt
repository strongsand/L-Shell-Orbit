package io.github.strongsand.lshell.beacon

import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener

/** Development address only. Cannot contain credentials, another path or commands. */
internal data class BeaconPowerEndpoint private constructor(val host: String, val port: Int) {
    val address: String get() = "http://${if (':' in host) "[$host]" else host}:$port"

    companion object {
        fun parse(input: String): BeaconPowerEndpoint {
            val raw = input.trim()
            require(raw.isNotEmpty() && raw.length <= 300 && raw.none { it.isWhitespace() || it.isISOControl() })
            val uri = URI(if ("://" in raw) raw else "http://$raw")
            require(uri.scheme.equals("http", ignoreCase = true) && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null && uri.rawPath in listOf(null, "", "/"))
            val host = requireNotNull(uri.host).removePrefix("[").removeSuffix("]")
            require(host.isNotBlank() && '%' !in host && uri.rawAuthority?.endsWith(':') != true)
            val port = if (uri.port == -1) 80 else uri.port
            require(port in 1..65535)
            return BeaconPowerEndpoint(host, port)
        }
    }
}

internal class BeaconPowerContractException : IllegalArgumentException("Invalid electrical HTTP contract")

/** Isolated read-only adapter: same info/status contract, no pairing/history/ACK client or credentials.
 * Used only for an explicitly selected development server, never as a configured Beacon fallback.
 */
internal class BeaconPowerHttpClient(private val endpoint: BeaconPowerEndpoint) {
    private var identity: String? = null

    suspend fun read(): Result<BeaconRuntimeStatus> = try {
        val info = request("/v1/info")
        val id = info.opt("beacon_id") as? String ?: throw BeaconPowerContractException()
        val protocol = (info.opt("protocol_version") as? Number)?.toDouble()
        if (id.isBlank() || id.length > 128 || id.any { it.isISOControl() } || protocol != 1.0 || identity?.let { it != id } == true)
            throw BeaconPowerContractException()
        val capabilities = info.opt("capabilities").let { value ->
            if (value == null || value == JSONObject.NULL) emptySet() else {
                val array = value as? org.json.JSONArray ?: throw BeaconPowerContractException()
                (0 until array.length()).map { array.get(it) as? String ?: throw BeaconPowerContractException() }.toSet()
            }
        }
        val device = BeaconDevice(id, id, endpoint.host, endpoint.port,
            protocolVersion = 1, capabilities = capabilities)
        identity = id // pin across reconnects; a different server identity requires an explicit new session
        readBeaconPowerDiagnostic(device, info = { Result.success(device) }, status = {
            val json = request("/v1/status")
            Result.success(BeaconRuntimeStatus(device, electrical = BeaconPowerParser.parse(json, capabilities)))
        })
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { Result.failure(error) }

    private suspend fun request(path: String): JSONObject = coroutineScope {
        // Only fixed paths above can reach here. Redirects cannot escape the selected target.
        val connection = URI(endpoint.address + path).toURL().openConnection() as HttpURLConnection
        val response = async(Dispatchers.IO) {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 3000
            connection.readTimeout = 5000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            check(connection.responseCode == 200) { "Electrical HTTP ${connection.responseCode}" }
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(2048)
                val body = StringBuilder()
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (body.length + count > 64 * 1024) throw BeaconPowerContractException()
                    body.append(buffer, 0, count)
                }
                body.toString()
            }
            val tokens = JSONTokener(text)
            val json = JSONObject(tokens)
            if (tokens.nextClean() != '\u0000') throw BeaconPowerContractException()
            json
        }
        // Parent await is cancellable. Disconnect before waiting for the IO child to finish,
        // so leaving/switching the panel also closes a blocked response body.
        try { response.await() } finally {
            // Some platform HTTP implementations wait for an in-flight read while closing.
            // Cleanup must stay off the UI thread even on cancellation (timeouts remain bounded).
            withContext(NonCancellable + Dispatchers.IO) { connection.disconnect() }
        }
    }
}
