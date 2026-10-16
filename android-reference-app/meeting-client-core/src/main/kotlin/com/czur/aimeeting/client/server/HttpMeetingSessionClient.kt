package com.czur.aimeeting.client.server

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** 参考部署的终端账号登录／匿名发现，初始账号由部署方预置；不记录请求和响应凭据。 */
class HttpMeetingSessionClient(baseUrl: String) {
    private val baseUrl = baseUrl.removeSuffix("/")
    @Throws(IOException::class)
    fun login(username: String, password: String): ReferenceLoginSession {
        val body = try { JSONObject().put("username", username).put("password", password)
            .toString().toByteArray(StandardCharsets.UTF_8) }
        catch (invalidInput: JSONException) { throw IOException("登录请求格式错误", invalidInput) }
        val loginUrl = URL("$baseUrl/v1/reference/session:login")
        validateLoginUrl(loginUrl)
        val connection = loginUrl.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status != 200) throw IOException(if (status == 401) "账号或密码错误" else "登录失败，HTTP $status")
            try {
                connection.inputStream.use { input ->
                    val response = JSONObject(readUtf8(input))
                    val token = response.getString("accessToken")
                    val expiresAt = response.getString("expiresAt")
                    if (token.isEmpty() || expiresAt.isEmpty()) throw IOException("登录响应缺少会话信息")
                    return ReferenceLoginSession(token, expiresAt)
                }
            } catch (invalidResponse: JSONException) { throw IOException("登录响应格式错误", invalidResponse) }
        } finally { connection.disconnect() }
    }
    @Throws(IOException::class) fun capabilities(): Set<String> = deploymentOptions().capabilities()
    /** 语言、链接期限和邮箱从同一响应取得，不推断未声明能力。 */
    @Throws(IOException::class)
    fun deploymentOptions(): DeploymentOptions {
        val url = URL("$baseUrl/v1/capabilities")
        validateLoginUrl(url)
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status != 200) throw IOException("部署能力发现失败，HTTP $status")
            try {
                connection.inputStream.use { input ->
                    val response = JSONObject(readUtf8(input))
                    val values = response.getJSONArray("capabilities")
                    val capabilities = HashSet<String>()
                    for (index in 0 until values.length()) capabilities.add(values.getString(index))
                    val languages = ArrayList<String>()
                    val declared = response.optJSONArray("languages")
                    if (declared != null) for (index in 0 until declared.length()) languages.add(declared.getString(index))
                    return DeploymentOptions(java.util.Set.copyOf(capabilities), response.optLong("resultLinkTtlMs", 0),
                        response.optBoolean("resultEmailEnabled", false), languages)
                }
            } catch (invalidResponse: JSONException) { throw IOException("部署能力响应格式错误", invalidResponse) }
        } finally { connection.disconnect() }
    }
    companion object {
        /** 账号仅经 HTTPS，唯一开发明文例外为 adb reverse 暴露的 loopback。 */
        @JvmStatic @Throws(IOException::class)
        fun validateLoginUrl(url: URL) {
            if (url.protocol.equals("https", true)) return
            val host = url.host
            if (url.protocol.equals("http", true) && (host.equals("localhost", true) || host == "127.0.0.1")) return
            throw IOException("登录地址须使用 HTTPS；本机调试可使用 adb reverse 的 localhost")
        }
        @Throws(IOException::class)
        private fun readUtf8(input: InputStream): String {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                output.write(buffer, 0, count)
            }
            return output.toString(StandardCharsets.UTF_8)
        }
    }
}
