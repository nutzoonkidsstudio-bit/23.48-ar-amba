package com.example.altyazi

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.PlaybackParams
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Ücretsiz, API anahtarsız, kotasız doğal Türkçe ses (Microsoft nöral sesleri).
 * Edge tarayıcısının "Sesli oku" servisini kullanır. Resmî bir API değildir;
 * servis değişirse hata verir ve uygulama telefon sesine döner.
 */
class EdgeVoice {

    companion object {
        // Ad -> ekranda görünen isim
        val VOICES = listOf(
            "tr-TR-EmelNeural" to "Emel (kadın)",
            "tr-TR-AhmetNeural" to "Ahmet (erkek)"
        )
        const val DEFAULT_VOICE = "tr-TR-EmelNeural"

        private const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        // Servis sürüm kontrolü yapar; ileride reddederse bu iki değeri güncelle
        private const val CHROME_FULL = "143.0.3650.75"
        private const val CHROME_MAJOR = "143"
        private const val WIN_EPOCH = 11644473600L
    }

    private class Rejected(val code: Int, val serverDateMs: Long?) : Exception("HTTP $code")

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val executor = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger(0)
    @Volatile private var player: MediaPlayer? = null
    @Volatile private var skewSec = 0L

    fun speak(
        voice: String,
        text: String,
        rate: Float,
        flush: Boolean,
        onError: (String) -> Unit
    ) {
        if (flush) stop()
        val gen = generation.get()
        executor.execute {
            if (gen != generation.get()) return@execute
            try {
                val mp3 = fetch(voice, text)
                if (gen != generation.get()) return@execute
                play(mp3, rate, gen)
            } catch (e: Exception) {
                if (gen == generation.get()) onError(e.message ?: "bilinmeyen hata")
            }
        }
    }

    fun stop() {
        generation.incrementAndGet()
        try {
            player?.stop()
        } catch (e: Exception) {
            // yok say
        }
    }

    fun release() {
        stop()
        executor.shutdownNow()
    }

    // ---------------------------------------------------------------- Ağ

    private fun fetch(voice: String, text: String): ByteArray {
        return try {
            fetchOnce(voice, text)
        } catch (e: Rejected) {
            // Telefon saati kaymışsa sunucu saatine göre düzeltip bir kez daha dene
            val server = e.serverDateMs
            if (e.code == 403 && server != null) {
                skewSec = (server - System.currentTimeMillis()) / 1000
                fetchOnce(voice, text)
            } else {
                throw Exception("Ses servisi isteği reddetti (HTTP ${e.code})")
            }
        }
    }

    private fun gec(): String {
        val sec = System.currentTimeMillis() / 1000 + skewSec + WIN_EPOCH
        val ticks = (sec - sec % 300) * 10_000_000L
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$ticks$TOKEN".toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun stamp(): String {
        val f = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date()) + " GMT+0000 (Coordinated Universal Time)"
    }

    private fun escape(s: String): String =
        s.replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), " ")
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun parseDate(h: String?): Long? = try {
        if (h == null) null else SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(h)?.time
    } catch (e: Exception) {
        null
    }

    private fun fetchOnce(voice: String, text: String): ByteArray {
        val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=$TOKEN" +
            "&ConnectionId=${UUID.randomUUID().toString().replace("-", "")}" +
            "&Sec-MS-GEC=${gec()}" +
            "&Sec-MS-GEC-Version=1-$CHROME_FULL"

        val req = Request.Builder()
            .url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/$CHROME_MAJOR.0.0.0 Safari/537.36 Edg/$CHROME_MAJOR.0.0.0"
            )
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=" + UUID.randomUUID().toString().replace("-", "").uppercase())
            .build()

        val longName = "Microsoft Server Speech Text to Speech Voice (tr-TR, " +
            voice.substringAfterLast('-') + ")"
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='tr-TR'>" +
            "<voice name='$longName'><prosody pitch='+0Hz' rate='+0%' volume='+0%'>" +
            escape(text) + "</prosody></voice></speak>"

        val audio = ByteArrayOutputStream()
        val latch = CountDownLatch(1)
        val failure = AtomicReference<Exception?>(null)

        val ws: WebSocket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    "X-Timestamp:${stamp()}\r\nContent-Type:application/json; charset=utf-8\r\n" +
                        "Path:speech.config\r\n\r\n" +
                        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
                        "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
                        "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
                )
                webSocket.send(
                    "X-RequestId:${UUID.randomUUID().toString().replace("-", "")}\r\n" +
                        "Content-Type:application/ssml+xml\r\n" +
                        "X-Timestamp:${stamp()}Z\r\nPath:ssml\r\n\r\n" + ssml
            )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) latch.countDown()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes.size < 2) return
                val headerLen = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                if (bytes.size < 2 + headerLen) return
                val header = bytes.substring(2, 2 + headerLen).utf8()
                if (header.contains("Path:audio")) {
                    audio.write(bytes.substring(2 + headerLen).toByteArray())
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure.set(
                    if (response != null && response.code != 101) {
                        Rejected(response.code, parseDate(response.header("Date")))
                    } else {
                        Exception("Ses servisine bağlanılamadı, internetini kontrol et")
                    }
                )
                latch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                latch.countDown()
            }
        })

        val finished = latch.await(20, TimeUnit.SECONDS)
        ws.close(1000, null)

        failure.get()?.let { throw it }
        if (!finished) throw Exception("Ses servisi zamanında yanıt vermedi")
        val bytes = audio.toByteArray()
        if (bytes.isEmpty()) throw Exception("Ses servisi boş yanıt verdi")
        return bytes
    }

    // ------------------------------------------------------------- Oynatma

    private class BytesSource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(size, (data.size - position).toInt())
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize(): Long = data.size.toLong()
        override fun close() {}
    }

    private fun play(mp3: ByteArray, rate: Float, gen: Int) {
        val done = CountDownLatch(1)
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(BytesSource(mp3))
            mp.setOnCompletionListener { done.countDown() }
            mp.setOnErrorListener { _, _, _ -> done.countDown(); true }
            mp.prepare()
            player = mp
            mp.start()
            try {
                // Hız ayarı, bazı cihazlarda oynatmayı başlattığı için start()'tan sonra yapılır
                mp.playbackParams = PlaybackParams().setSpeed(rate)
            } catch (e: Exception) {
                // hız ayarlanamazsa normal hızda çal
            }
            while (!done.await(40, TimeUnit.MILLISECONDS)) {
                if (gen != generation.get()) break
            }
        } finally {
            try {
                mp.stop()
            } catch (e: Exception) {
                // yok say
            }
            mp.release()
            player = null
        }
    }
}
