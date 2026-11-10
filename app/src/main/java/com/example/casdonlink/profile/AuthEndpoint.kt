package com.example.casdonlink.profile

import com.example.casdonlink.BuildConfig

/**
 * 授权服务地址。
 *
 * 生产地址按需求写死在代码里，但不能以明文出现：这里用 XOR 字节数组在运行时还原。
 * 这只能避免静态字符串扫描，不能抵御逆向；真正的边界仍是服务端鉴权与网络可信。
 * debug 构建可通过 Gradle 参数 `-PauthBaseUrl=http://10.0.0.10:8080` 指向本地平台，
 * release 构建永远使用生产地址。
 */
object AuthEndpoint {
    private const val XOR_KEY = 0x5A
    private val PRODUCTION_BYTES = intArrayOf(
        50, 46, 46, 42, 96, 117, 117, 107, 107, 105, 116, 107, 106, 111,
        116, 104, 111, 105, 116, 104, 105, 110, 96, 107, 107, 110, 107, 111
    )

    fun baseUrl(): String {
        val override = BuildConfig.AUTH_BASE_URL_OVERRIDE.trim()
        if (BuildConfig.DEBUG && override.isNotEmpty()) return override.trimEnd('/')
        val decoded = ByteArray(PRODUCTION_BYTES.size) { index ->
            (PRODUCTION_BYTES[index] xor XOR_KEY).toByte()
        }
        return String(decoded, Charsets.US_ASCII).trimEnd('/')
    }

    fun isUsingDebugOverride(): Boolean =
        BuildConfig.DEBUG && BuildConfig.AUTH_BASE_URL_OVERRIDE.trim().isNotEmpty()
}
