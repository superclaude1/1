package com.storybrain.app.data.local

import android.util.Log
import okhttp3.*
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object EdgeTtsClient {
    private const val TAG = "EdgeTtsClient"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun generateSecMsGec(): String {
        try {
            val windowsFileTimeEpoch = 11644473600L
            val unixEpoch = System.currentTimeMillis() / 1000L
            val ticks = (unixEpoch + windowsFileTimeEpoch) * 10000000L
            val roundedTicks = ticks - (ticks % 3000000000L)
            val strToHash = "${roundedTicks}6A5AA1D4EAFF4E9FB37E23D68491D6F4"
            
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(strToHash.toByteArray(Charsets.US_ASCII))
            return hashBytes.joinToString("") { "%02X".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating Sec-MS-GEC", e)
            return ""
        }
    }

    private fun generateMuid(): String {
        val chars = "0123456789ABCDEF"
        return (1..32).map { chars.random() }.joinToString("")
    }

    private fun getJavascriptDateString(): String {
        val sdf = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    /**
     * Synthesize text to MP3 bytes using Microsoft Edge TTS WebSocket.
     * This is a blocking call (should be run on a background thread).
     */
    fun synthesize(text: String, voice: String = "zh-CN-XiaoxiaoNeural"): ByteArray? {
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val connectionId = UUID.randomUUID().toString().replace("-", "")
        val gec = generateSecMsGec()
        
        val muid = generateMuid()
        val chromiumFullVersion = "143.0.3650.75"
        val chromiumMajorVersion = "143"
        val gecVersion = "1-$chromiumFullVersion"
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromiumMajorVersion.0.0.0 Safari/537.36 Edg/$chromiumMajorVersion.0.0.0"

        val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
                "?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4" +
                "&ConnectionId=$connectionId" +
                "&Sec-MS-GEC=$gec" +
                "&Sec-MS-GEC-Version=$gecVersion"
        
        Log.d(TAG, "Generated WebSocket URL: $url")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Cookie", "muid=$muid;")
            .build()

        val latch = CountDownLatch(1)
        val audioBuffer = ByteArrayOutputStream()
        var error: Throwable? = null

        val webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket Opened")
                val timestamp = getJavascriptDateString()

                // 1. Send speech.config
                val configPayload = "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"true\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
                val configMsg = "X-Timestamp:$timestamp\r\n" +
                        "Content-Type:application/json; charset=utf-8\r\n" +
                        "Path:speech.config\r\n\r\n" +
                        configPayload
                webSocket.send(configMsg)

                // 2. Send ssml
                val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>" +
                        "<voice name='$voice'>" +
                        "<prosody rate='+0%' pitch='+0%'>$text</prosody>" +
                        "</voice></speak>"
                
                val ssmlMsg = "X-RequestId:$requestId\r\n" +
                        "Content-Type:application/ssml+xml\r\n" +
                        "X-Timestamp:${timestamp}Z\r\n" +
                        "Path:ssml\r\n\r\n" +
                        ssml
                webSocket.send(ssmlMsg)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received text metadata: $text")
                if (text.contains("Path:turn.end")) {
                    Log.d(TAG, "Synthesis finished successfully")
                    webSocket.close(1000, "Normal Closure")
                    latch.countDown()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    val data = bytes.toByteArray()
                    if (data.size >= 2) {
                        val headerLength = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                        if (data.size >= 2 + headerLength) {
                            val headerStr = String(data, 2, headerLength, Charsets.UTF_8)
                            if (headerStr.contains("Path:audio")) {
                                val audioPayload = data.copyOfRange(2 + headerLength, data.size)
                                audioBuffer.write(audioPayload)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing binary frame", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket Failure", t)
                error = t
                latch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket Closed")
                latch.countDown()
            }
        })

        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                Log.w(TAG, "Timeout waiting for Edge TTS synthesis")
                webSocket.cancel()
            }
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted", e)
            webSocket.cancel()
        }

        if (error != null) {
            return null
        }

        val audioBytes = audioBuffer.toByteArray()
        return if (audioBytes.isNotEmpty()) audioBytes else null
    }
}
