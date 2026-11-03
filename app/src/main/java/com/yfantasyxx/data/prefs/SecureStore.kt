package com.yfantasyxx.data.prefs

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 AndroidKeyStore(AES/GCM) 加密敏感字符串（WebDAV 密码）。
 * API 26+ 可用，正好是 minSdk。
 */
object SecureStore {

    private const val KEY_ALIAS = "yfantasyxx_secure_key"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_SIZE = 128

    /** 保护 KeyStore 的读-判-建三步，见 [getOrCreateKey] */
    private val KEY_LOCK = Any()

    /**
     * 解密结果缓存。
     *
     * 设置流被 5 处收集，每次设置变化都要各解一遍密（跨进程 Keystore，毫秒级）。
     * 明文本来就随 `AppSettings` 常驻内存，缓存不增加暴露面，只是省掉重复计算。
     * 容量只给 4：正常只有一个密码，换密码后旧条目在下次清理时淘汰。
     */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun getOrCreateKey(): SecretKey {
        // 必须同步：settings 是冷 Flow，每个收集者都会走一遍解密，
        // 首次启动时几个收集者几乎同时进来，各自 generateKey() 会互相覆盖 —— 
        // 用旧 key 加密的密文从此解不开（表现为「密码莫名变空、备份一直认证失败」）
        synchronized(KEY_LOCK) {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
                return it.secretKey
            }

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            generator.init(spec)
            return generator.generateKey()
        }
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String?): String {
        if (encoded.isNullOrEmpty()) return ""
        cache[encoded]?.let { return it }
        val plain = try {
            val data = Base64.decode(encoded, Base64.NO_WRAP)
            if (data.size <= IV_SIZE) return ""
            val iv = data.copyOfRange(0, IV_SIZE)
            val body = data.copyOfRange(IV_SIZE, data.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(TAG_SIZE, iv)
            )
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            // 密钥被系统清掉（换机、恢复出厂、KeyStore 损坏）时只能返回空，
            // 让用户重新填一次密码，而不是每次读设置都崩
            return ""
        }
        if (cache.size > 4) cache.clear()
        cache[encoded] = plain
        return plain
    }
}
