package com.mobox.notes

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only wraps the ordinary vault credential. Never wraps private-category passwords. */
class DeviceAccess(context: Context) {
    private val prefs = context.getSharedPreferences("device-access", Context.MODE_PRIVATE)
    private val alias = "suixinnotes-local-v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return generator.generateKey()
    }
    fun load(): CharArray? {
        val value = prefs.getString("wrapped", null) ?: return null
        val data = Base64.getDecoder().decode(value)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        val plain = cipher.doFinal(data, 12, data.size - 12)
        return try { plain.toString(Charsets.UTF_8).toCharArray() } finally { plain.fill(0) }
    }
    fun save(password: CharArray) {
        val plain = String(password).toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
            val wrapped = Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain))
            check(prefs.edit().putString("wrapped", wrapped).commit()) { "无法保存本机访问凭据" }
        } finally { plain.fill(0) }
    }
    fun generate(): CharArray = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }).toCharArray()
}
