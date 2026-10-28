package io.github.strongsand.lshell.beacon

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-local credentials, excluded from logical backup; never logged. */
internal class BeaconManagementCredentials(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("beacon_management_private", Context.MODE_PRIVATE)
    private fun alias(id: String): String {
        require(io.github.strongsand.lshell.beaconprotocol.BeaconProtocol.validId(id))
        return "lshell.beacon.management.$id"
    }
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    fun save(id: String, token: String) {
        require(token.matches(Regex("[a-f0-9]{64}")))
        val name = alias(id)
        val key = (store().getKey(name, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(name, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key); updateAAD(id.toByteArray()) }
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(id, Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).commit())
    }
    fun read(id: String): String? = runCatching {
        val data = Base64.decode(prefs.getString(id, null) ?: return null, Base64.NO_WRAP)
        require(data.size > 12)
        val key = store().getKey(alias(id), null) as SecretKey
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data.copyOfRange(0, 12))); updateAAD(id.toByteArray())
            String(doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
        }.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
    }.getOrNull()
    fun delete(id: String) {
        prefs.edit().remove(id).commit()
        runCatching { store().deleteEntry(alias(id)) }
    }
}
