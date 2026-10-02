package com.mobox.notes

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Versioned authenticated envelope; secrets never stored in its header. */
object Crypto {
    private val magic = byteArrayOf(77, 79, 66, 88, 1)
    const val ITERATIONS = 600_000
    const val MAX_BYTES = 48 * 1024 * 1024
    private val random = SecureRandom()
    private fun derive(password: CharArray, salt: ByteArray, count: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, count, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    fun encrypt(data: ByteArray, password: CharArray, iterations: Int = ITERATIONS): ByteArray {
        require(data.size <= MAX_BYTES - 64) { "保险库超过首版容量限制（48MB），请减少图片" }
        require(iterations in 100_000..1_000_000)
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val header = ByteBuffer.allocate(37).put(magic).putInt(iterations).put(salt).put(nonce).array()
        val key = derive(password, salt, iterations)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            header + cipher.doFinal(data)
        } finally { key.fill(0) }
    }
    fun decrypt(envelope: ByteArray, password: CharArray): ByteArray {
        require(envelope.size in 53..MAX_BYTES) { "文件大小无效" }
        val b = ByteBuffer.wrap(envelope)
        val prefix = ByteArray(5).also(b::get)
        require(prefix.contentEquals(magic)) { "不支持的备份格式或版本" }
        val count = b.int
        require(count in 100_000..1_000_000) { "文件加密参数不受支持" }
        val salt = ByteArray(16).also(b::get)
        val nonce = ByteArray(12).also(b::get)
        val key = derive(password, salt, count)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(envelope.copyOfRange(0, 37))
            cipher.doFinal(envelope, 37, envelope.size - 37)
        } finally { key.fill(0) }
    }
}
