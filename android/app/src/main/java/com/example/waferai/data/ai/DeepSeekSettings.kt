package com.example.waferai.data.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DeepSeekSettings(context: Context, private val name: String = "deepseek_credentials") {
    companion object {
        const val DEFAULT_MODEL = "deepseek-flash"
        val models: Map<String, String> = linkedMapOf(DEFAULT_MODEL to "Flash", "deepseek-v4-pro" to "Pro")
    }

    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val alias = "$name.aes"
    val model: String
        get() = preferences.getString("model", DEFAULT_MODEL)?.takeIf { it in models } ?: DEFAULT_MODEL
    val hasKey: Boolean get() = preferences.contains("encrypted_key")
    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun key(): String {
        check(hasKey) { "请先在“AI 设置”中保存你的 DeepSeek API 密钥。" }
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = Base64.decode(preferences.getString("iv", ""), Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(Base64.decode(preferences.getString("encrypted_key", ""), Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrElse { throw IllegalStateException("本机密钥无法读取，请在 AI 设置中重新保存密钥。") }
    }
    @Synchronized fun save(newKey: String, model: String) {
        require(model in models) { "请选择支持的 DeepSeek 模型。" }
        val editor = preferences.edit().putString("model", model)
        if (newKey.isNotBlank()) {
            val key = newKey.trim()
            require(key.length in 16..512 && key.all { it.code in 33..126 }) { "密钥格式不正确，请粘贴完整的 API 密钥。" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
            editor.putString("encrypted_key", Base64.encodeToString(cipher.doFinal(key.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        } else require(hasKey) { "请填写你的 DeepSeek API 密钥。" }
        check(editor.commit()) { "无法保存 AI 设置。" }
    }
    @Synchronized fun selectModel(model: String) {
        require(model in models) { "请选择支持的 DeepSeek 模型。" }
        preferences.edit().putString("model", model).apply()
    }

    @Synchronized fun clear() {
        check(preferences.edit().remove("encrypted_key").remove("iv").commit()) { "无法清除密钥。" }
    }
}
