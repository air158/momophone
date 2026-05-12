package com.afwsamples.testdpc.policy.locktask

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal MomoAI account client: login -> read user_momoai_key, falling back
 * to create-key if the account does not yet have one. Used by AiSettings to
 * auto-fill the API key after the user enters their MomoAI credentials.
 *
 * Base URL is fixed to https://www.momoai.pro per current product decision.
 * Returns Result.failure with a human-readable Chinese message on any error.
 */
object MomoAccountClient {

    private const val BASE = "https://www.momoai.pro"

    data class LoginResult(
        val authToken: String,
        val username: String,
        val email: String,
        val momoApiKey: String
    )

    fun login(usernameOrEmail: String, password: String): Result<LoginResult> {
        val loginPayload = JSONObject().apply {
            put("email", usernameOrEmail)
            put("password", password)
        }
        val (loginCode, loginBody) = postJson("$BASE/api/auth/login", loginPayload.toString(), null)
            ?: return Result.failure(IllegalStateException("无法连接 MomoAI 服务器"))
        val loginJson = try { JSONObject(loginBody) } catch (e: Exception) {
            return Result.failure(IllegalStateException("登录响应解析失败"))
        }
        if (loginCode !in 200..299 || !loginJson.optBoolean("success", false)) {
            val code = loginJson.optString("code")
            val msg = loginJson.optString("message").ifEmpty { "登录失败 (HTTP $loginCode)" }
            return if (code == "EMAIL_NOT_VERIFIED") {
                Result.failure(IllegalStateException("$msg。请在 momoai.pro 完成邮箱验证。"))
            } else {
                Result.failure(IllegalStateException(msg))
            }
        }

        val authToken = loginJson.optString("authToken")
        if (authToken.isBlank()) return Result.failure(IllegalStateException("登录响应缺少 authToken"))
        val user = loginJson.optJSONObject("user")
            ?: return Result.failure(IllegalStateException("登录响应缺少 user 字段"))
        val username = user.optString("username").ifEmpty { user.optString("user_id") }
        val email = user.optString("email")

        var key = user.optString("user_momoai_key")
        if (key.isNullOrBlank()) {
            key = createKey(authToken).getOrElse { return Result.failure(it) }
        }
        return Result.success(LoginResult(authToken, username, email, key))
    }

    private fun createKey(authToken: String): Result<String> {
        val payload = JSONObject().put("action", "create").toString()
        val (code, body) = postJson("$BASE/api/user/momoai-key", payload, authToken)
            ?: return Result.failure(IllegalStateException("创建 API Key 失败：无法连接服务器"))
        val json = try { JSONObject(body) } catch (_: Exception) {
            return Result.failure(IllegalStateException("创建 API Key 响应解析失败"))
        }
        if (code !in 200..299 || !json.optBoolean("success", false)) {
            val msg = json.optString("error").ifEmpty { json.optString("message") }
                .ifEmpty { "创建 API Key 失败 (HTTP $code)" }
            return Result.failure(IllegalStateException(msg))
        }
        val data = json.optJSONObject("data")
        val key = data?.optString("user_momoai_key").orEmpty()
        return if (key.isBlank()) Result.failure(IllegalStateException("响应缺少 user_momoai_key"))
        else Result.success(key)
    }

    private fun postJson(url: String, body: String, bearer: String?): Pair<Int, String>? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            conn.disconnect()
            code to text
        } catch (_: Exception) {
            null
        }
    }
}
