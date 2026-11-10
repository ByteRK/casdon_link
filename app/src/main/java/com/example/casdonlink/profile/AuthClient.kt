package com.example.casdonlink.profile

import com.example.casdonlink.connection.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** 授权服务返回的分配结果。 */
data class Authorization(
    val pid: String,
    val mac: String,
    val did: String,
    val license: String,
    val mode: String,
    val assignedAt: String,
    val sourceBatch: String
)

class AuthException(message: String, val httpStatus: Int = 0) : Exception(message)

/**
 * MacToAuthCode 的 `/api/device/authorize` 客户端。
 *
 * 服务端对同一 PID + MAC 是幂等的：重复请求返回原记录，`mode=reused`，不会重复扣库存。
 * 因此授权流程失败后重新申请是安全的；界面仍会优先提供「只重写」以减少无谓请求。
 */
class AuthClient {
    suspend fun authorize(pid: String, mac: String): Authorization = withContext(Dispatchers.IO) {
        Diagnostics.log("向授权服务申请：PID=$pid MAC=$mac")
        val connection = openConnection("${AuthEndpoint.baseUrl()}/api/device/authorize", "POST")
        try {
            val body = JSONObject().put("pid", pid).put("mac", mac)
                .toString().toByteArray(StandardCharsets.UTF_8)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.doOutput = true
            connection.outputStream.use { it.write(body) }

            val status = connection.responseCode
            val text = readBody(if (status in 200..299) connection.inputStream else connection.errorStream)
            val root = runCatching { JSONObject(text) }.getOrNull()
            if (status != 200 || root == null || !root.optBoolean("success", false)) {
                val message = root?.optString("message").orEmpty().ifBlank { "授权服务请求失败（HTTP $status）" }
                throw AuthException(message, status)
            }
            val data = root.optJSONObject("data")
                ?: throw AuthException("授权服务返回数据缺少 data", status)
            val license = data.optJSONObject("payload")?.optString("license").orEmpty()
            val did = data.optString("display_code")
            if (license.isBlank()) throw AuthException("授权服务返回数据缺少 payload.license", status)
            if (did.isBlank()) throw AuthException("授权服务返回数据缺少 display_code", status)
            Authorization(
                pid = data.optString("pid", pid),
                mac = data.optString("mac", mac),
                did = did,
                license = license,
                mode = data.optString("mode"),
                assignedAt = data.optString("assigned_at"),
                sourceBatch = data.optString("source_batch")
            ).also {
                Diagnostics.log("授权服务返回：DID=${it.did} mode=${it.mode.ifBlank { "assigned" }}")
            }
        } finally {
            connection.disconnect()
        }
    }

    /** 连通性自检：服务端对 OPTIONS 返回 204。 */
    suspend fun checkReachable(): String = withContext(Dispatchers.IO) {
        val connection = openConnection("${AuthEndpoint.baseUrl()}/api/device/authorize", "OPTIONS")
        try {
            val status = connection.responseCode
            if (status != 204) throw AuthException("授权服务返回 HTTP $status", status)
            "授权服务可访问"
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            useCaches = false
        }

    private fun readBody(stream: InputStream?): String {
        if (stream == null) return ""
        return stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                if (output.size() > MAX_RESPONSE_BYTES) break
            }
            String(output.toByteArray(), StandardCharsets.UTF_8)
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 256 * 1024
    }
}
