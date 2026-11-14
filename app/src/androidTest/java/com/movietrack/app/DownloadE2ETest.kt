package com.movietrack.app

import android.Manifest
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.movietrack.app.data.DownloadEntity
import com.movietrack.app.data.Status
import com.movietrack.app.download.Analyzer
import com.movietrack.app.download.OutputFile
import com.movietrack.app.download.Requests
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gerçek indirme testleri: emülatörde uygulamanın kendi indirme motoru, telefon içinde çalışan
 * yerel bir sunucudan (gerçek sitelerin yaptığı numaralarla) video indirir; çıkan dosya
 * Android'in kendi okuyucusuyla açılıp görüntü + ses izi ve süresi kontrol edilir.
 */
@RunWith(AndroidJUnit4::class)
class DownloadE2ETest {
    // Takılan test tüm işi kilitlemesin: süresi dolan test adıyla başarısız olur
    @get:org.junit.Rule val timeoutRule: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(240)

    @get:Rule
    val perms: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else GrantPermissionRule.grant()

    private val app get() = App.instance
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val server = MockWebServer()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val run = System.currentTimeMillis().toString(36)

    private val busyHits = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private val slowHits = java.util.concurrent.atomic.AtomicInteger(0)
    private fun asset(p: String): ByteArray? = try { assets.open("fx/$p").use { it.readBytes() } } catch (_: Exception) { null }

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                var path = request.requestUrl!!.encodedPath.removePrefix("/")
                // Dizipal benzeri: Referer olmadan 403
                if (path.startsWith("ref/")) {
                    if (request.getHeader("Referer")?.startsWith("http://site.test/") != true) return MockResponse().setResponseCode(403)
                    path = path.removePrefix("ref/")
                }
                // hdfilmcehennemi benzeri: segmentler .png adıyla ve başlarında sahte PNG başlığıyla
                if (path.startsWith("junk/")) {
                    val n = path.removePrefix("junk/")
                    if (n == "index.m3u8") return ok(String(asset("ts/index.m3u8")!!).replace(".ts", ".png").toByteArray(), "application/vnd.apple.mpegurl", request)
                    val ts = asset("ts/" + n.replace(".png", ".ts")) ?: return MockResponse().setResponseCode(404)
                    val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(1200) { (it * 7).toByte() }
                    return ok(png + ts, "image/png", request)
                }
                // Ses parçaları da sahte PNG başlığıyla: ham AAC (ID3 etiketli) .png adıyla
                if (path.startsWith("junkaud/")) {
                    val n = path.removePrefix("junkaud/")
                    if (n == "master.m3u8") return ok(String(asset("aac/master.m3u8")!!).replace("../split/", "/split/").toByteArray(), "application/vnd.apple.mpegurl", request)
                    if (n == "audio.m3u8") return ok(String(asset("aac/audio.m3u8")!!).replace(".aac", ".png").toByteArray(), "application/vnd.apple.mpegurl", request)
                    val a = asset("aac/" + n.replace(".png", ".aac")) ?: return MockResponse().setResponseCode(404)
                    val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(1200) { (it * 7).toByte() }
                    return ok(png + a, "image/png", request)
                }
                // Yoğun sunucu: her parçanın ilk 7 isteğine 429 (hızlı yeniden denemeler tükenir)
                if (path.startsWith("busy/")) {
                    val n = path.removePrefix("busy/")
                    if (n.endsWith(".ts")) {
                        val c = busyHits.merge(n, 1, Int::plus)!!
                        if (c <= 7) return MockResponse().setResponseCode(429)
                    }
                    return ok(asset("ts/$n") ?: return MockResponse().setResponseCode(404), if (n.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "video/mp2t", request)
                }
                // Ortadaki parçası ilk istekte geç gelen yayın (duraklat/sürdür sırasında birleştirme testi)
                if (path.startsWith("pr/")) {
                    val n = path.removePrefix("pr/")
                    val r = ok(asset("ts/$n") ?: return MockResponse().setResponseCode(404), if (n.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "video/mp2t", request)
                    if (n == "s1.ts" && slowHits.incrementAndGet() == 1) r.setHeadersDelay(6, java.util.concurrent.TimeUnit.SECONDS)
                    return r
                }
                // İlk parçası çok yavaş gelen büyük dosya (dinamik bölme testi)
                if (path == "slowfirst/big.bin") {
                    val r = ok(asset("big/b0.ts")!!, "application/octet-stream", request)
                    val rg = request.getHeader("Range")?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                    if (rg != null && rg.groupValues[1] == "0" && rg.groupValues[2] != "0") r.throttleBody(64 * 1024, 500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    return r
                }
                // gizlenmiş liste adı: master.txt
                if (path == "hidden/master.txt") {
                    val t = String(asset("ts/master.m3u8")!!).replace("\nindex.m3u8", "\n/ts/index.m3u8").replace("../split/", "/split/")
                    return ok(t.toByteArray(), "text/plain", request)
                }
                val body = asset(path) ?: return MockResponse().setResponseCode(404)
                val type = when (path.substringAfterLast('.')) {
                    "m3u8" -> "application/vnd.apple.mpegurl"; "mpd" -> "application/dash+xml"; "ts" -> "video/mp2t"
                    "mp4", "m4s" -> "video/mp4"; "vtt" -> "text/vtt"; else -> "application/octet-stream"
                }
                return ok(body, type, request)
            }
        }
        server.start()
        app.settings.wifiOnly = false
        app.settings.folderUri = null
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
        server.shutdown()
    }

    /** Range isteklerini de destekleyen yanıt (çok parçalı indirme için). */
    private fun ok(body: ByteArray, type: String, r: RecordedRequest): MockResponse {
        val range = r.getHeader("Range")?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
        if (range != null) {
            val start = range.groupValues[1].toLong()
            val end = range.groupValues[2].toLongOrNull()?.coerceAtMost(body.size - 1L) ?: (body.size - 1L)
            if (start >= body.size) return MockResponse().setResponseCode(416)
            return MockResponse().setResponseCode(206).setHeader("Content-Type", type).setHeader("Accept-Ranges", "bytes")
                .setHeader("Content-Range", "bytes $start-$end/${body.size}")
                .setBody(Buffer().write(body, start.toInt(), (end - start + 1).toInt()))
        }
        return MockResponse().setHeader("Content-Type", type).setHeader("Accept-Ranges", "bytes").setBody(Buffer().write(body))
    }

    private fun url(p: String) = server.url("/$p").toString()

    data class Probe(val video: Int, val audio: Int, val durationSec: Double, val audioLangs: List<String>, val audioSec: Double = 0.0, val audioMimes: List<String> = emptyList())

    private fun probe(uri: Uri): Probe {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(app, uri, null)
            var v = 0; var a = 0; var d = 0L; var ad = 0L
            val am = ArrayList<String>()
            val langs = ArrayList<String>()
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) v++
                if (mime.startsWith("audio/")) { am += mime; a++; f.getString(MediaFormat.KEY_LANGUAGE)?.let { langs += it }; if (f.containsKey(MediaFormat.KEY_DURATION)) ad = maxOf(ad, f.getLong(MediaFormat.KEY_DURATION)) }
                if (f.containsKey(MediaFormat.KEY_DURATION)) d = maxOf(d, f.getLong(MediaFormat.KEY_DURATION))
            }
            return Probe(v, a, d / 1e6, langs, ad / 1e6, am)
        } finally { ex.release() }
    }

    private fun analyze(u: String, kind: String? = null, referer: String? = null) = runBlocking { Analyzer.analyze(u, kind, referer, null) }

    private fun download(i: Analyzer.Info, u: String, name: String, quality: String? = i.qualities.firstOrNull()?.id,
                         audios: List<String> = i.audios.map { it.id }, subs: List<String> = emptyList(), mode: String = "mp4", referer: String? = null,
                         audioOnly: Boolean = false, alsoAudio: Boolean = false): DownloadEntity = runBlocking {
        val req = Requests.build(i, u, "test-$name-$run", "Test", quality, audios, subs, mode, referer = referer, audioOnly = audioOnly, alsoAudio = alsoAudio)
        val id = app.downloads.add(req)
        val dao = app.db.downloads()
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            val e = dao.get(id)!!
            if (e.status == Status.DONE) return@runBlocking e
            if (e.status == Status.ERROR) fail("$name indirilemedi: ${e.error}")
            delay(300)
        }
        fail("$name zaman aşımı: ${dao.get(id)?.status}"); throw IllegalStateException()
    }

    private fun output(e: DownloadEntity): Uri = OutputFile.decode(e.outputUri)!!.uri

    private fun assertPlayable(e: DownloadEntity, what: String, wantAudio: Boolean = true) {
        val p = probe(output(e))
        assertEquals("$what: görüntü izi (uyarı: ${e.error})", 1, p.video)
        // İstatistik: indirme süresi kaydedilmiş olmalı (detayda süre ve ortalama hız için)
        val st = com.movietrack.app.util.KV.decode(e.meta)
        assertTrue("$what: istatistik yok: ${st.keys}", (st["st.dl"]?.toLongOrNull() ?: -1) >= 0 && st.containsKey("st.merge"))
        if (wantAudio) {
            assertTrue("$what: ses izi yok (${p}, uyarı: ${e.error})", p.audio >= 1)
            // Ses tüm video boyunca sürmeli (yalnızca ilk parçası değil)
            assertTrue("$what: ses ${p.audioSec}s / görüntü ${p.durationSec}s", p.audioSec >= p.durationSec * 0.8)
            // Tanı raporu sesin gerçekten duyulduğunu ölçmeli (test dosyalarında 440 Hz ton var)
            com.movietrack.app.util.KV.decode(e.meta)["diag"]?.let { d ->
                assertTrue("$what: tanı sesi duymadı: ${d.replace("\n", " | ")}", d.contains("ses VAR"))
                // Test seslerinin hepsi 440 Hz temiz ton: bozuk/karışık ses başka frekans verir
                val hz = Regex("baskın frekans ~(\\d+) Hz").find(d)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                assertTrue("$what: ses bozuk, frekans ~$hz Hz (440 bekleniyor): ${d.replace("\n", " | ")}", hz in 400..480)
            }
        }
        assertTrue("$what: süre ${p.durationSec}s", p.durationSec in 5.0..7.5)
        // Parçalar indirme sırasında birleştirilmiş olmalı (indirme bitince bekleme olmasın)
        st["diag"]?.let { d ->
            Regex("\\((\\d+) / (\\d+) parça indirme sırasında eklendi\\)").find(d)?.let { m ->
                assertTrue("$what: indirirken birleştirme çalışmadı: ${m.value}", m.groupValues[1].toInt() > 0)
            }
        }
    }

    @Test
    fun joinsWhileDownloadingAcrossPauseAndResume() = runBlocking {
        val u = url("pr/index.m3u8")
        val i = analyze(u)
        val req = Requests.build(i, u, "test-pauseresume-$run", "Test", i.qualities.first().id, emptyList(), emptyList(), "mp4")
        val id = app.downloads.add(req)
        val dao = app.db.downloads()
        // s0 ve s2 iner, s1 sunucuda bekler: bu sırada duraklat
        delay(2500)
        assertTrue("erken bitti: ${dao.get(id)?.status}", dao.get(id)!!.status != Status.DONE)
        app.downloads.pause(id).join()
        val dir = java.io.File(app.filesDir, "streams/$id")
        val pos = java.io.File(dir, "stream_0.pos")
        assertTrue("birleştirme durumu kaydedilmedi: ${dir.list()?.toList()}", pos.exists())
        android.util.Log.i("JoinTest", "duraklatıldı, durum: ${pos.readText().replace("\n", " | ")}")
        // Akışa eklenen parça diskte tutulmaz (yer: parçalar + akış iki kopya olmasın); sırası gelmeyen bekler
        assertFalse("eklenen parça silinmedi: ${dir.list()?.toList()}", java.io.File(dir, "0_0.seg").exists())
        assertTrue("sırası gelmeyen parça silindi: ${dir.list()?.toList()}", java.io.File(dir, "0_2.seg").exists())
        app.downloads.resume(id).join()
        val deadline = System.currentTimeMillis() + 90_000
        var e = dao.get(id)!!
        while (System.currentTimeMillis() < deadline && e.status != Status.DONE) {
            if (e.status == Status.ERROR) fail("sürdürülemedi: ${e.error}")
            delay(300); e = dao.get(id)!!
        }
        assertEquals(Status.DONE, e.status)
        assertPlayable(e, "Duraklat/sürdür sırasında birleştirme")
    }

    @Test
    fun hlsTsWithMuxedAudio() {
        val u = url("ts/index.m3u8")
        val i = analyze(u)
        assertEquals("hls", i.kind)
        assertEquals(true, i.qualities.single().audio)
        assertEquals(6.0, i.duration, 0.1)
        assertPlayable(download(i, u, "ts"), "TS")
    }

    @Test
    fun masterMarksSilentVariant() {
        val u = url("ts/master.m3u8")
        val i = analyze(u)
        assertEquals(2, i.qualities.size)
        val silent = i.qualities.first { it.id.endsWith("split/video.m3u8") }
        val loud = i.qualities.first { it.id.endsWith("ts/index.m3u8") }
        assertEquals(false, silent.audio)
        assertEquals(true, loud.audio)
        // tek başına görüntü listesi (diziyou'daki sessiz 720p.m3u8 gibi)
        assertEquals(false, analyze(url("split/video.m3u8")).qualities.single().audio)
        assertPlayable(download(i, u, "master-loud", quality = loud.id), "Ana liste sesli kalite")
    }

    @Test
    fun separateAudioTrackIsMergedWithLanguage() {
        val u = url("split/master.m3u8")
        val i = analyze(u)
        assertEquals(1, i.audios.size)
        assertEquals("tr", i.audios[0].lang)
        val e = download(i, u, "split")
        assertPlayable(e, "Ayrı ses izi")
        assertTrue("dil etiketi: ${probe(output(e)).audioLangs}", probe(output(e)).audioLangs.any { it == "tur" })
    }

    /** 5.1 AC-3 (Dolby) ses: telefon çözemiyorsa birleştirmede AAC stereoya çevrilmeli (indirici'deki FFmpeg çevirici). */
    @Test
    fun ac3AudioIsConvertedWhenPhoneCannotDecode() {
        val prev = com.movietrack.app.download.Muxer.converterFactory
        com.movietrack.app.download.Muxer.converterFactory = { androidx.media3.decoder.ffmpeg.FfmpegToAac.create(it) }
        try {
            val ac3 = android.media.MediaFormat.createAudioFormat("audio/ac3", 48000, 6)
            val phoneHasAc3 = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).findDecoderForFormat(ac3) != null
            val u = url("ac3/index.m3u8")
            val e = download(analyze(u), u, "ac3")
            val p = probe(output(e))
            android.util.Log.i("AudioConvert", "telefonda AC-3 çözücü=$phoneHasAc3 çıktı sesi=${p.audioMimes} ses=${p.audioSec}s görüntü=${p.durationSec}s")
            assertPlayable(e, "AC-3 ses")
            if (!phoneHasAc3) assertEquals("çevrilmiş ses", listOf("audio/mp4a-latm"), p.audioMimes)
        } finally { com.movietrack.app.download.Muxer.converterFactory = prev }
    }

    /** Ayrı ses izi başka bir saatle geliyor (görüntü 1,4 sn, ses 10 saat): ses videonun başına hizalanmalı. */
    @Test
    fun separateAudioOnDifferentClock() {
        val u = url("clock/master.m3u8")
        val i = analyze(u)
        assertEquals(1, i.audios.size)
        val e = download(i, u, "clock")
        assertPlayable(e, "Başka saatli ses izi")
    }

    /** Sunucu "çok fazla istek" (429) diyor: indirme durmamalı, yavaşlayıp tamamlanmalı. */
    @Test
    fun survivesTooManyRequests() {
        val u = url("busy/index.m3u8")
        val e = download(analyze(u), u, "busy")
        assertPlayable(e, "429 sonrası")
    }

    /** Yalnızca ses: ayrı ses izi varsa görüntü inmeden .m4a */
    @Test
    fun audioOnlyFromSeparateTrack() {
        val u = url("split/master.m3u8")
        val e = download(analyze(u), u, "yalnizses", audioOnly = true)
        val p = probe(output(e))
        assertTrue("dosya adı: ${e.fileName}", e.fileName.endsWith(".m4a"))
        assertEquals("görüntü olmamalı ($p)", 0, p.video)
        assertTrue("ses ($p)", p.audio == 1 && p.audioSec in 5.0..7.5)
    }

    /** Yalnızca ses: ses görüntü akışının içindeyse ayıklanır */
    @Test
    fun audioOnlyFromMuxedStream() {
        val u = url("ts/index.m3u8")
        val e = download(analyze(u), u, "yalnizses2", audioOnly = true)
        val p = probe(output(e))
        assertEquals("görüntü olmamalı ($p)", 0, p.video)
        assertTrue("ses ($p)", p.audio == 1 && p.audioSec in 5.0..7.5)
    }

    /** Sesi ayrıca da kaydet: MP4'ün yanında .m4a */
    @Test
    fun alsoSavesAudioFile() {
        val u = url("split/master.m3u8")
        val e = download(analyze(u), u, "ayricases", alsoAudio = true)
        assertPlayable(e, "Sesli MP4")
        val extra = e.extraOutputs.lines().firstOrNull { it.endsWith("aud:tr") } ?: fail("ek ses dosyası yok: ${e.extraOutputs}").let { "" }
        val p = probe(OutputFile.decode(extra.substringBeforeLast('|'))!!.uri)
        assertTrue("ek ses ($p)", p.video == 0 && p.audio == 1 && p.audioSec in 5.0..7.5)
    }

    /** Ham .aac ses parçaları, her birinin başında ID3 zaman etiketi (Apple tarzı "packed audio"). */
    @Test
    fun packedAacAudioWithId3() {
        val u = url("aac/master.m3u8")
        val i = analyze(u)
        assertEquals(1, i.audios.size)
        val e = download(i, u, "aac")
        assertPlayable(e, "Ham AAC ses")
    }

    /** Ayrı ses izi ham AAC ve parçaları sahte resim gibi gizlenmiş (hdfilmcehennemi tarzı). */
    @Test
    fun disguisedPackedAudio() {
        val u = url("junkaud/master.m3u8")
        val i = analyze(u)
        assertEquals(1, i.audios.size)
        val e = download(i, u, "junkaud")
        assertPlayable(e, "Gizlenmiş ham AAC ses")
    }

    /** Seçilen ayrı ses izi işe yaramıyorsa (içinde ses yok) videonun kendi sesi kullanılmalı. */
    @Test
    fun brokenSeparateAudioFallsBackToMuxed() {
        val u = url("brokenaud/master.m3u8")
        val i = analyze(u)
        val e = download(i, u, "brokenaud")
        assertPlayable(e, "Boş ayrı ses izi")
    }

    @Test
    fun separateModeWritesAudioFile() {
        val u = url("split/master.m3u8")
        val i = analyze(u)
        val e = download(i, u, "sep", mode = "separate")
        assertPlayable(e, "Ayrı dosya video", wantAudio = false)
        val extra = e.extraOutputs.lines().filter { it.isNotBlank() }
        assertTrue("ses dosyası: $extra", extra.any { it.endsWith("aud:tr") })
    }

    @Test
    fun fakeImageHeadersAreStripped() {
        val u = url("junk/index.m3u8")
        val i = analyze(u)
        assertEquals(true, i.qualities.single().audio)
        assertPlayable(download(i, u, "junk"), "Sahte PNG başlıklı")
    }

    @Test
    fun hiddenTxtPlaylist() {
        val u = url("hidden/master.txt")
        val i = analyze(u, kind = "hls")
        assertEquals(2, i.qualities.size)
        assertPlayable(download(i, u, "hidden", quality = i.qualities.first { it.audio == true }.id), "master.txt")
    }

    @Test
    fun fmp4Hls() {
        val u = url("fmp4/index.m3u8")
        val i = analyze(u)
        assertEquals(true, i.qualities.single().audio)
        assertPlayable(download(i, u, "fmp4"), "fMP4")
    }

    @Test
    fun aes128Hls() {
        val u = url("aes/index.m3u8")
        val i = analyze(u)
        assertTrue(i.encrypted)
        assertPlayable(download(i, u, "aes"), "AES-128")
    }

    @Test
    fun dash() {
        val u = url("dash/manifest.mpd")
        val i = analyze(u)
        assertEquals("dash", i.kind)
        assertTrue(i.qualities.isNotEmpty())
        assertTrue(i.audios.isNotEmpty())
        assertPlayable(download(i, u, "dash"), "DASH")
    }

    @Test
    fun directMp4NeedsReferer() {
        val u = url("ref/direct.mp4")
        val i = analyze(u, referer = "http://site.test/film")
        assertEquals("direct", i.kind)
        assertEquals(asset("direct.mp4")!!.size.toLong(), i.size)
        val e = download(i, u, "direct", referer = "http://site.test/film")
        val bytes = app.contentResolver.openInputStream(output(e))!!.use { it.readBytes() }
        assertTrue("dosya aynı değil", bytes.contentEquals(asset("direct.mp4")!!))
        assertPlayable(e, "Doğrudan MP4")
    }

    /** Bir bağlantı yavaş kalınca boşalan bağlantılar onun kalanını bölüp devralmalı; dosya bayt bayt aynı olmalı. */
    @Test
    fun idleConnectionsStealFromSlowPart() {
        val prev = app.settings.parts
        app.settings.parts = 4
        try {
            val u = url("slowfirst/big.bin")
            val t0 = System.currentTimeMillis()
            val e = download(analyze(u), u, "slowfirst")
            val took = System.currentTimeMillis() - t0
            val bytes = app.contentResolver.openInputStream(output(e))!!.use { it.readBytes() }
            assertTrue("dosya aynı değil", bytes.contentEquals(asset("big/b0.ts")!!))
            val n = e.parts.split(';').count { it.isNotBlank() }
            android.util.Log.i("DynSplit", "parça sayısı=$n süre=${took} ms")
            assertTrue("bölme olmadı: $n parça", n > 4)
            // Bölme olmasa yavaş parça (~1,7 MB, 128 KB/sn) tek başına ~13 sn sürerdi
            assertTrue("yavaş: $took ms", took < 11_000)
        } finally { app.settings.parts = prev }
    }

    @Test
    fun subtitleSavedNextToVideo() {
        val u = url("ts/index.m3u8")
        var i = analyze(u)
        val sub = url("sub.vtt")
        i = i.copy(subtitles = i.subtitles + Analyzer.Track(sub, "tr", "sub.vtt", "subtitle:tr"))
        val e = download(i, u, "sub", subs = listOf(sub))
        val srt = e.extraOutputs.lines().firstOrNull { it.endsWith("subtitle:tr") }
        assertFalse("altyazı kaydedilmedi: ${e.extraOutputs}", srt == null)
        val text = app.contentResolver.openInputStream(OutputFile.decode(srt!!.substringBeforeLast('|'))!!.uri)!!.use { String(it.readBytes()) }
        assertTrue(text, text.contains("Merhaba") && text.contains("00:00:00,500 --> 00:00:02,000"))
    }
}
