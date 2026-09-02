package com.wearable.inspection.mobile.tts

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * 语音播报管理器 — android.speech.tts.TextToSpeech 的薄包装。
 *
 * - 播报音轨显式绑定 STREAM_MUSIC（USAGE_MEDIA + CONTENT_TYPE_SPEECH）：
 *   声音经手机扬声器/蓝牙耳机播出，与提示音同走媒体音量，勿扰/通知静音模式下仍可闻，适配工业现场。
 * - TextToSpeech 初始化是异步的（OnInitListener 回调），本类在未就绪时缓存
 *   待播文案，初始化成功（或语音引擎变更后重新初始化）时自动补播，避免漏报。
 * - 默认 QUEUE_FLUSH（新播报打断旧播报，保证实效性）；[speak] 的 flush 参数
 *   可改为 QUEUE_ADD 排队衔接。
 */
class TtsManager(context: Context) {

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var pendingText: String? = null

    /** 引擎就绪状态（初始化成功且完成） */
    val isReady: Boolean get() = ready

    /** 初始化成功回调（可重复赋值，回调时读取最新） */
    @Volatile var onReady: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) {
                Log.w(TAG, "TTS 初始化失败：$status，本次采集将静默完成")
                return@TextToSpeech
            }
            // 绑定媒体音轨（STREAM_MUSIC / USAGE_MEDIA + CONTENT_TYPE_SPEECH）：
            // 经手机扬声器/蓝牙耳机播出，勿扰/通知静音下仍可闻
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            // 优先中文（设备无中文语音引擎时保留系统默认语言）
            val lang = tts?.setLanguage(Locale.CHINESE)
            if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "设备无中文语音引擎，使用系统默认语言")
            }
            pendingText?.let { speak(it) }
            pendingText = null
            onReady?.invoke()
        }
    }

    /**
     * 播报一段语音。初始化未完成时缓存（只保留最近一条），就绪后自动补播。
     *
     * @param flush true=QUEUE_FLUSH：打断上一条未播完的语音（默认，保证实效性）；
     *              false=QUEUE_ADD：排队衔接
     */
    fun speak(text: String, flush: Boolean = true) {
        if (!ready) {
            pendingText = text
            return
        }
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(text, mode, null, "tts_${System.currentTimeMillis()}")
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        pendingText = null
    }

    companion object {
        private const val TAG = "TtsManager"
    }
}
