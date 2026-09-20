package com.jmread.data

import android.content.Context
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 已保存账号凭据（邮箱 + 密码）：
 *  - 密码用 AndroidKeyStore 内的 AES-256-GCM 密钥加密后落盘，密钥不可导出
 *  - 邮箱明文存储（仅用于展示/预填）
 *  - 登出不清除，仅用户明确选择"删除保存的账号"时清除
 *  - 「是否保存」由用户显式控制（[saveEnabled]，默认沿用既有行为 = 保存），
 *    关闭时 save() 直接跳过并清除已有凭据
 */
object SecureAccountStore {

    private const val PREFS_NAME = "jm_secure_account"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_PREFIX = "jm_cred_"
    private const val GCM_TAG_BITS = 128
    private const val KEY_SAVE_ENABLED = "save_enabled"

    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 是否允许保存账号密码。默认 true，保持既有「登出后一键重登」体验不被静默改变；
     * 用户可在登录页取消勾选关闭（关闭时清除已有凭据）。
     */
    var saveEnabled: Boolean
        get() = prefs?.getBoolean(KEY_SAVE_ENABLED, true) ?: true
        set(value) {
            prefs?.edit()?.putBoolean(KEY_SAVE_ENABLED, value)?.apply()
        }

    private val keyAlias = KEY_PREFIX + "JM"
    private val emailKey = "email_JM"
    private val passKey = "pass_JM"

    /** 登录成功后保存凭据（覆盖旧值）；用户关闭「保存账号密码」时不落盘 */
    fun save(email: String, password: String) {
        val p = prefs ?: return
        if (!saveEnabled) return
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val ct = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            p.edit()
                .putString(emailKey, email)
                .putString(
                    passKey,
                    Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                        Base64.encodeToString(ct, Base64.NO_WRAP),
                )
                .apply()
        } catch (e: Exception) {
            Log.e("SecureAccountStore", "save failed: ${e.message}")
        }
    }

    /** 读取已保存凭据（解密），无保存或解密失败返回 null */
    fun load(): Pair<String, String>? {
        val p = prefs ?: return null
        val email = p.getString(emailKey, null) ?: return null
        val packed = p.getString(passKey, null) ?: return null
        val parts = packed.split(":")
        if (parts.size != 2) return null
        return try {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            email to String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            // Keystore 密钥丢失（如系统备份还原）→ 清掉无法解密的旧数据
            Log.e("SecureAccountStore", "load failed: ${e.message}")
            clear()
            null
        }
    }

    /** 仅邮箱（登录页展示用，不触发解密失败清理） */
    fun savedEmail(): String? =
        prefs?.getString(emailKey, null)

    fun clear() {
        prefs?.edit()
            ?.remove(emailKey)
            ?.remove(passKey)
            ?.apply()
    }

    /** 取 KeyStore 中的密钥，不存在则用 KeyGenParameterSpec 生成（AES-256-GCM，不可导出） */
    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER)
        ks.load(null)
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance("AES", KEYSTORE_PROVIDER)
        gen.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                keyAlias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }
}
