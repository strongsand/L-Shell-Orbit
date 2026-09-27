package com.hurricane.lshell.beacon

import java.security.KeyStore

interface BeaconCredentialStore {
    fun contains(alias: String): Boolean
    fun delete(alias: String)
}

class AndroidKeystoreCredentialStore : BeaconCredentialStore {
    private fun keyStore() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun contains(alias: String): Boolean = runCatching { keyStore().containsAlias(alias) }.getOrDefault(false)

    override fun delete(alias: String) {
        runCatching { keyStore().deleteEntry(alias) }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        fun aliasFor(beaconId: String): String = "lshell.beacon." +
            beaconId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80)
    }
}
