package com.czur.aimeeting.sdk

import com.czur.aimeeting.model.ModelHttpRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 只把公开文本请求描述转换成现有 OkHttp 请求，不选择模型、补鉴权或拥有响应资源。 */
internal object ModelHttpRequests {
    fun build(endpoint: String, description: ModelHttpRequest): Request {
        val request = Request.Builder().url(endpoint)
        description.headers().forEach { (name, value) -> request.header(name, value) }
        val body = description.body()?.toRequestBody(description.contentType()?.toMediaType())
        return request.method(description.method(), body).build()
    }
}
