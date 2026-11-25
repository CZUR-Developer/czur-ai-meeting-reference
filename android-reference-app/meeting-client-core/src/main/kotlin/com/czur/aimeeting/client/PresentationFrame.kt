package com.czur.aimeeting.client

/** 相对会议起点的 JPEG；构造及读取均复制数组，异步提交后调用方可复用原缓冲区。 */
class PresentationFrame(
    private val offsetMs: Long,
    jpeg: ByteArray,
    ocrText: String?,
) {
    constructor(offsetMs: Long, jpeg: ByteArray) : this(offsetMs, jpeg, null)

    private val jpeg = jpeg.clone()
    private val ocrText = ocrText?.takeIf { it.isNotBlank() }

    fun offsetMs(): Long = offsetMs
    fun jpeg(): ByteArray = jpeg.clone()
    fun ocrText(): String? = ocrText
}
