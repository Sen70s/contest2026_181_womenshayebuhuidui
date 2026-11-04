package com.pointerwander.luminawatch.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 问卷答案仅加密保存在设备上，不混入普通 AppStore 偏好设置。 */
internal class AssessmentVault(context: Context) {
    private val prefs = context.getSharedPreferences("mental_assessments", Context.MODE_PRIVATE)
    private val alias = "luminawatch_mental_assessments_v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    fun read(): List<AssessmentRecord> {
        val payload = prefs.getString("records", null) ?: return emptyList()
        val parts = payload.split(':', limit = 2)
        require(parts.size == 2) { "问卷记录格式无效" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        val json = String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        val array = JSONArray(json)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            AssessmentRecord(
                id = item.getLong("id"),
                timestamp = item.getLong("timestamp"),
                gadAnswers = item.getJSONArray("gad").toIntList(),
                phqAnswers = item.getJSONArray("phq").toIntList(),
            )
        }
    }

    fun write(records: List<AssessmentRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(JSONObject().apply {
                put("id", record.id)
                put("timestamp", record.timestamp)
                put("gad", JSONArray(record.gadAnswers))
                put("phq", JSONArray(record.phqAnswers))
            })
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(data, Base64.NO_WRAP)
        prefs.edit().putString("records", packed).apply()
    }
}

private fun JSONArray.toIntList(): List<Int> = (0 until length()).map { getInt(it) }
