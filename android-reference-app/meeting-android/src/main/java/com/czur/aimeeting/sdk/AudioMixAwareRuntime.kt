package com.czur.aimeeting.sdk

import com.czur.aimeeting.client.AudioMixOptions
import com.czur.aimeeting.model.ModelAdapter
import com.czur.aimeeting.model.ModelSession
import com.czur.aimeeting.model.ModelSessionGrant
import com.czur.aimeeting.model.ModelSessionListener
import com.czur.aimeeting.model.ModelSessionReconnector
import com.czur.aimeeting.model.TranscriptListener

/**
 * AAR 内部的混流感知入口：只有 Android 运行时消费本场音频选项。
 *
 * <p>公共 `ModelRuntime` 契约保持不变；Mock、WAV 调试和伙伴自装运行时不需要实现本接口，
 * 适配层按类型判断后回落到普通 `open`，旧运行时行为不受影响。</p>
 */
internal interface AudioMixAwareRuntime {
    fun openWithAudioMix(grant: ModelSessionGrant, adapter: ModelAdapter,
        transcripts: TranscriptListener, listener: ModelSessionListener,
        retainAudio: Boolean, reconnector: ModelSessionReconnector?,
        audioMix: AudioMixOptions): ModelSession
}
