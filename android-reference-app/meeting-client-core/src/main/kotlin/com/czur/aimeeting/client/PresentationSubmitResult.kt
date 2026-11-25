package com.czur.aimeeting.client

/** 演示帧提交结果：图片是否成功、OCR 文本是否实际写入请求。 */
data class PresentationSubmitResult(
    val imageAccepted: Boolean,
    val ocrTextIncluded: Boolean,
    val ocrTextSuppressedReason: OcrTextSuppression? = null,
)

/** OCR 文本未写入请求的原因；不是 OCR 失败。 */
enum class OcrTextSuppression {
    BUDGET,
    EMPTY,
}

/**
 * 演示帧提交结果回调。与旧 Callback<Void?> 分开，避免 JVM 泛型擦除后的重载冲突。
 */
interface PresentationResultCallback {
    fun onSuccess(result: PresentationSubmitResult)
    fun onError(error: MeetingError)
    fun onError(message: String)
}
