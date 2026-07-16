package com.storybrain.app.data.local

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import com.storybrain.app.data.remote.OpenAiCompatClient
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object TtsVoiceAllocator {
    fun allocateDemoId(charId: String?, name: String): String {
        if (charId.isNullOrBlank()) {
            return "demo-5" // Narration: Fixed steady male narrator
        }
        val lowercaseName = name.lowercase()
        val isFemale = lowercaseName.contains("女") ||
                lowercaseName.contains("师姐") ||
                lowercaseName.contains("师妹") ||
                lowercaseName.contains("妹") ||
                lowercaseName.contains("姐") ||
                lowercaseName.contains("姑") ||
                lowercaseName.contains("姨") ||
                lowercaseName.contains("娘") ||
                lowercaseName.contains("碧瑶") ||
                lowercaseName.contains("雪琪") ||
                lowercaseName.contains("小姐") ||
                lowercaseName.contains("姑娘") ||
                lowercaseName.contains("公主") ||
                lowercaseName.contains("夫人") ||
                lowercaseName.contains("妈") ||
                lowercaseName.contains("姬") ||
                lowercaseName.contains("妃") ||
                lowercaseName.contains("后") ||
                lowercaseName.contains("嫂") ||
                lowercaseName.contains("妇") ||
                lowercaseName.contains("婢") ||
                lowercaseName.contains("仆")

        val gender = if (isFemale) "female" else "male"
        return "${gender}_${charId}_${name}"
    }
}

class TtsManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var mediaPlayer: MediaPlayer? = null
    private var systemTts: TextToSpeech? = null
    private var systemTtsReady = false
    private val settingsManager = (context.applicationContext as com.storybrain.app.StoryBrainApp).settingsManager

    /**
     * Callback triggered when a speech utterance or playback finishes.
     * Guaranteed to be called on the main (UI) thread.
     */
    var onSpeakCompleteListener: (() -> Unit)? = null

    init {
        Log.d("TtsManager", "Initializing TtsManager and native system TTS...")
        systemTts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = systemTts?.setLanguage(java.util.Locale.SIMPLIFIED_CHINESE)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TtsManager", "Simplified Chinese is not supported by system TTS")
            } else {
                systemTtsReady = true
                Log.d("TtsManager", "System TTS initialized successfully for Simplified Chinese")
                
                systemTts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onSpeakCompleteListener?.invoke()
                        }
                    }
                    override fun onError(utteranceId: String?) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onSpeakCompleteListener?.invoke()
                        }
                    }
                })
            }
        } else {
            Log.e("TtsManager", "System TTS initialization failed with status: $status")
        }
    }

    fun speak(text: String, demoId: String) {
        val mode = settingsManager.loadTtsMode()
        when (mode) {
            "system" -> {
                if (systemTtsReady) {
                    var pitch = 1.0f
                    var rate = 1.0f
                    if (demoId.startsWith("female_")) {
                        val hash = Math.abs(demoId.hashCode())
                        pitch = 1.1f + (hash % 30) / 100f // 1.1f to 1.4f
                        rate = 0.95f + (hash % 20) / 100f // 0.95f to 1.15f
                    } else if (demoId.startsWith("male_")) {
                        val hash = Math.abs(demoId.hashCode())
                        pitch = 0.8f + (hash % 20) / 100f // 0.8f to 1.0f
                        rate = 1.0f + (hash % 15) / 100f // 1.0f to 1.15f
                    } else {
                        when (demoId) {
                            "demo-3" -> { pitch = 1.3f; rate = 1.08f }
                            "demo-6" -> { pitch = 1.15f; rate = 0.95f }
                            "demo-4" -> { pitch = 0.8f; rate = 1.05f }
                            "demo-7" -> { pitch = 0.92f; rate = 1.1f }
                        }
                    }
                    systemTts?.setPitch(pitch)
                    systemTts?.setSpeechRate(rate)

                    // Stop any active MediaPlayer audio first
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            mediaPlayer?.stop()
                        } catch (e: Exception) {}
                    }
                    
                    systemTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "char_tts")
                } else {
                    Log.w("TtsManager", "System TTS not ready")
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onSpeakCompleteListener?.invoke()
                    }
                }
            }
            "edge" -> {
                Thread {
                    val voice = when {
                        demoId == "demo-5" || demoId.isBlank() -> "zh-CN-YunyangNeural" // Narration
                        demoId.startsWith("female_") -> {
                            val hash = Math.abs(demoId.hashCode())
                            val femaleVoices = listOf(
                                "zh-CN-XiaoxiaoNeural",
                                "zh-CN-XiaoyiNeural",
                                "zh-CN-XiaoxuanNeural",
                                "zh-CN-XiaomengNeural",
                                "zh-CN-XiaoruiNeural",
                                "zh-CN-YunxiaNeural",
                                "zh-CN-Liaoning-XiaobeiNeural"
                            )
                            femaleVoices[hash % femaleVoices.size]
                        }
                        demoId.startsWith("male_") -> {
                            val hash = Math.abs(demoId.hashCode())
                            val maleVoices = listOf(
                                "zh-CN-YunjianNeural",
                                "zh-CN-YunxiNeural",
                                "zh-CN-YunyeNeural",
                                "zh-CN-YunzeNeural"
                            )
                            maleVoices[hash % maleVoices.size]
                        }
                        demoId == "demo-3" || demoId == "demo-6" -> "zh-CN-XiaoxiaoNeural"
                        demoId == "demo-4" -> "zh-CN-YunjianNeural"
                        demoId == "demo-7" -> "zh-CN-YunxiNeural"
                        else -> "zh-CN-YunyangNeural"
                    }
                    try {
                        // Stop any active system TTS audio first
                        systemTts?.stop()

                        val audioBytes = EdgeTtsClient.synthesize(text, voice)
                        if (audioBytes != null) {
                            playAudioBytes(audioBytes, ".mp3")
                        } else {
                            Log.e("TtsManager", "Edge-TTS returned empty bytes")
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                onSpeakCompleteListener?.invoke()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("TtsManager", "Edge-TTS request failed", e)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onSpeakCompleteListener?.invoke()
                        }
                    }
                }.start()
            }
            else -> { // server
                Thread {
                    // CosyVoice 3 服务端签名: text + demo_id (+ speed)
                    val formBody = FormBody.Builder()
                        .add("text", text)
                        .add("demo_id", demoId)
                        .add("speed", "1.0")
                        .build()

                    val serverUrl = settingsManager.loadTtsServerUrl()
                    val request = Request.Builder()
                        .url("${serverUrl.trimEnd('/')}/api/generate")
                        .post(formBody)
                        .build()

                    try {
                        // Stop any active system TTS audio first
                        systemTts?.stop()

                        val response = OpenAiCompatClient.sharedHttp.newCall(request).execute()
                        if (response.isSuccessful) {
                            val bodyString = response.body?.string() ?: ""
                            val json = JSONObject(bodyString)
                            val audioBase64 = json.getString("audio_base64")
                            val audioBytes = Base64.decode(audioBase64, Base64.DEFAULT)
                            playAudioBytes(audioBytes, ".wav")
                        } else {
                            Log.e("TtsManager", "CosyVoice request failed: ${response.code} ${response.message}")
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                onSpeakCompleteListener?.invoke()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("TtsManager", "CosyVoice network error", e)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onSpeakCompleteListener?.invoke()
                        }
                    }
                }.start()
            }
        }
    }

    fun speak(text: String, pitch: Float, speechRate: Float) {
        val demoId = when {
            pitch > 1.2f -> "demo-6"
            pitch < 1.0f -> "demo-4"
            else -> "demo-5"
        }
        speak(text, demoId)
    }

    fun speakCharacter(charId: String?, name: String, text: String) {
        val demoId = TtsVoiceAllocator.allocateDemoId(charId, name)
        speak(text, demoId)
    }

    private fun playAudioBytes(bytes: ByteArray, suffix: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val tempFile = File.createTempFile("storybrain_tts_", suffix, context.cacheDir)
                tempFile.deleteOnExit()
                FileOutputStream(tempFile).use { fos ->
                    fos.write(bytes)
                }

                mediaPlayer?.stop()
                mediaPlayer?.release()

                mediaPlayer = MediaPlayer().apply {
                    setDataSource(tempFile.absolutePath)
                    setOnCompletionListener {
                        onSpeakCompleteListener?.invoke()
                    }
                    setOnErrorListener { _, _, _ ->
                        onSpeakCompleteListener?.invoke()
                        true
                    }
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                Log.e("TtsManager", "Failed to play audio bytes", e)
                onSpeakCompleteListener?.invoke()
            }
        }
    }

    fun stop() {
        try {
            systemTts?.stop()
        } catch (e: Exception) {}
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                mediaPlayer?.stop()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun shutdown() {
        try {
            systemTts?.stop()
            systemTts?.shutdown()
            systemTts = null
        } catch (e: Exception) {}
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
}
