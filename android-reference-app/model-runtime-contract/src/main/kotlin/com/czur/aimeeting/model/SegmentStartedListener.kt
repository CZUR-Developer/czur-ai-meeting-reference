package com.czur.aimeeting.model

/** 句开始监听器；与字幕／译文同序交付，只处理快照，不另开音源或解释模型 JSON。 */
fun interface SegmentStartedListener {
    fun onSegmentStarted(event: SegmentStartedEvent)
}
