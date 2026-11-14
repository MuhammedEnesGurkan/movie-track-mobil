package com.movietrack.app.download

import com.movietrack.app.App
import com.movietrack.app.util.Category
import com.movietrack.app.util.Format
import com.movietrack.app.util.KV
import com.movietrack.app.util.Lang
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

/**
 * HLS (m3u8) ve DASH (mpd) indirme:
 *  - seçilen kalite + seçilen ses dilleri (aud:xx) + altyazılar (subtitle:xx)
 *  - segmentler paralel indirilir, AES-128 çözülür, reklam araları atlanabilir
 *  - indirilen segmentler diskte kalır: duraklat/devam ve uygulama yeniden açılınca kaldığı yerden
 *  - sonunda yeniden kodlamadan MP4'e birleştirilir (TS → MP4) ya da ayrı dosyalar olarak kaydedilir
 */
class StreamDownloader(private val app: App, private val ctx: JobContext) {
    /** raw: bayt aralıklarıyla bölünmüş tek dosya (YouTube); parçalarda "sahte başlık" temizliği yapılmaz */
    private class Plan(val role: String, val lang: String, val name: String, val segments: List<Hls.Segment>, val fmp4: Boolean, val raw: Boolean = false)

    private val e get() = ctx.e
    private val meta by lazy { KV.decode(e.meta) }
    private val dir by lazy { File(app.filesDir, "streams/${e.id}").apply { mkdirs() } }
    private val keyCache = HashMap<String, ByteArray>()

    suspend fun run() {
        ctx.phase = "Liste alınıyor"
        ctx.emit()
        val skipAds = meta["skipAds"] != "0"
        val audioOnly = meta["audioOnly"] == "1"
        val allPlans = when (e.type) { "dash" -> planDash(); "youtube" -> planYoutube(); else -> planHls() }
        // Yalnızca ses: ayrı ses izi varsa görüntü hiç indirilmez; yoksa ses ana akıştan ayıklanır
        val plans = if (audioOnly && allPlans.any { it.role == "audio" }) allPlans.filter { it.role == "audio" } else allPlans
        val tasks = plans.flatMapIndexed { si, p -> p.segments.mapIndexedNotNull { i, s -> if (skipAds && s.isAd) null else Triple(si, i, s) } }
        // Init segmentleri (fMP4): parçalar indikçe birleştirilebilsin diye önce alınır
        for ((si, p) in plans.withIndex()) for (m in p.segments.mapNotNull { it.map }.distinct()) {
            val f = initFile(si, m)
            if (!f.exists()) { val seg = p.segments.first { it.map == m }; f.writeBytes(Http.bytes(e, m, seg.mapRange)) }
        }
        // İndirirken birleştirme: sıradaki parçalar indikçe akış dosyasına eklenir ve parça dosyası silinir
        // (yer tasarrufu); indirme bitince bu adım beklenmez. Kaldığı yer önce okunur: eklenmiş parçalar
        // diskte yoktur ama indirilmiş sayılır.
        val joiners = plans.mapIndexed { si, p -> Joiner(si, p, skipAds) }
        joiners.forEach { it.prepare() }
        fun have(si: Int, i: Int) = joiners[si].isJoined(i) || segFile(si, i).exists()
        ctx.segTotal = tasks.size
        ctx.segDone = tasks.count { have(it.first, it.second) }
        e.downloadedBytes = joiners.sumOf { it.file.length() } +
            tasks.sumOf { (si, i) -> if (joiners[si].isJoined(i)) 0L else segFile(si, i).let { f -> if (f.exists()) f.length() else 0L } }
        ctx.phase = "İndiriliyor"

        // 1) Segmentler (paralel). Sunucu "çok fazla istek" (429/503) derse durmaz: bağlantı sayısı yarıya
        // iner, beklenir, aynı parça yeniden denenir; bir süre sorunsuz gidince bağlantılar geri artar.
        val next = AtomicInteger(0)
        val workers = app.settings.parts.coerceIn(2, 16)
        val allowed = AtomicInteger(workers)
        val lastThrottle = java.util.concurrent.atomic.AtomicLong(0)
        val throttles = AtomicInteger(0)
        val minAllowed = AtomicInteger(workers)
        suspend fun fetchSegment(seg: Hls.Segment): ByteArray {
            var attempt = 0
            while (true) {
                try { return Http.bytes(e, seg.url, seg.range) { ctx.onBytes(it) } }
                catch (x: HttpException) {
                    if ((x.code != 429 && x.code != 503) || attempt >= 10) throw x
                    attempt++
                    // Aynı anda gelen retler tek olay sayılır (yoksa 8 bağlantı bir anda 1'e iniyordu);
                    // en az 2 bağlantı kalır
                    val now = System.currentTimeMillis()
                    throttles.incrementAndGet()
                    synchronized(allowed) {
                        if (now - lastThrottle.get() > 5_000) {
                            val n = maxOf(2, allowed.get() * 2 / 3)
                            allowed.set(n); lastThrottle.set(now)
                            if (n < minAllowed.get()) minAllowed.set(n)
                        }
                    }
                    ctx.phase = "Sunucu yavaşlattı · ${allowed.get()} bağlantı"; ctx.emit()
                    kotlinx.coroutines.delay(minOf(20_000L, 2_000L * attempt))
                }
            }
        }
        try { coroutineScope {
            repeat(workers) { w ->
                launch {
                    while (true) {
                        // Yavaşlatılmış bağlantı bekler; iş bitince beklemeyi bırakır (yoksa indirme sonda takılıyordu)
                        while (w >= allowed.get() && next.get() < tasks.size) kotlinx.coroutines.delay(500)
                        val k = next.getAndIncrement()
                        if (k >= tasks.size) break
                        val (si, i, seg) = tasks[k]
                        val f = segFile(si, i)
                        if (have(si, i)) continue
                        var data = fetchSegment(seg)
                        data = if (plans[si].raw) data else Hls.stripJunk(decrypt(seg, data))
                        val tmp = File(f.path + ".part")
                        tmp.writeBytes(data)
                        tmp.renameTo(f)
                        try { joiners[si].advance(false) } catch (_: java.io.IOException) {}
                        ctx.segDone++
                        if (ctx.segDone > 2 && e.type != "youtube") e.totalBytes = e.downloadedBytes * ctx.segTotal / ctx.segDone
                        // 8 sn sorunsuz: bir bağlantı geri
                        if (allowed.get() < workers && System.currentTimeMillis() - lastThrottle.get() > 8_000) {
                            val n = allowed.incrementAndGet()
                            ctx.phase = if (n >= workers) "İndiriliyor" else "Sunucu yavaşlattı · $n bağlantı"; ctx.emit()
                            lastThrottle.set(System.currentTimeMillis())
                        }
                    }
                }
            }
        } } catch (t: Throwable) { joiners.forEach { it.close() }; throw t }

        // 2) Akış dosyaları
        ctx.phase = "Birleştiriliyor"
        ctx.mergeStartedAt = System.currentTimeMillis()
        ctx.segTotal = 1000; ctx.segDone = 0
        ctx.emit()
        e.status = com.movietrack.app.data.Status.MERGING
        ctx.saveNow()
        val t0 = System.nanoTime()
        val early = joiners.sumOf { it.joined }
        val streamFiles = try {
            joiners.map { it.finish() }
        } finally { joiners.forEach { it.close() } }
        val concatMs = (System.nanoTime() - t0) / 1_000_000
        val streamBytes = streamFiles.sumOf { it.length() }
        val throttleNote = "indirme: $workers bağlantı, sunucu ${throttles.get()} kez 'çok fazla istek' dedi, en az ${minAllowed.get()} bağlantıya inildi\n"
        val timings = StringBuilder("hız: parça birleştirme ${speedOf(streamBytes, concatMs)} (${early} / ${tasks.size} parça indirme sırasında eklendi)")

        // 3) Çıktı
        val base = meta["base"]?.takeIf { it.isNotBlank() } ?: e.fileName.substringBeforeLast('.')
        val folder = meta["folder"] ?: Category.VIDEO
        val extras = ArrayList<String>()
        var merged = false
        val job = coroutineContext[kotlinx.coroutines.Job]
        val totalUs = ((plans.firstOrNull { it.role == "main" } ?: plans.first()).segments
            .filter { !(skipAds && it.isAd) }.sumOf { it.duration } * 1_000_000).toLong()
        val hasSepAudio = plans.any { it.role == "audio" }
        fun inputs(allAudio: Boolean, video: Boolean) = plans.indices.map { si ->
            val p = plans[si]
            // Ayrı ses izi seçildiyse ana akıştaki gömülü ses alınmaz (aynı dilin iki kopyası olmasın)
            Muxer.Input(streamFiles[si].path, takeVideo = video && p.role == "main",
                takeAudio = allAudio || p.role == "audio" || !hasSepAudio, lang = p.lang)
        }
        // Birleştirme ayrı iş parçacığında: büyük dosyayı açmak (setDataSource) bölünemiyor; Duraklat/Sil
        // basılınca indirme hemen durur, arka plandaki birleştirme ilk denetimde kendiliğinden biter
        // MP4 önce uygulamanın kendi klasörüne (doğrudan dosya sistemi) yazılır, sonra büyük bloklarla tek seferde
        // İndirilenler'e kopyalanır: MP4 yazıcısının çok sayıdaki küçük yazması bazı telefonlarda İndirilenler'in
        // ara katmanından (FUSE) geçince çok yavaşlıyor. Yer yetmezse doğrudan yazılır.
        // Disk alanı: last=true ise (bu akış dosyalarıyla başka çıktı yapılmayacak) akış dosyaları kopyalamadan
        // önce silinir; böylece aynı anda en fazla iki kopya durur (akış + MP4, sonra MP4 + son dosya).
        var streamsDropped = false
        fun freeBytes() = try { android.os.StatFs(dir.path).availableBytes } catch (_: Exception) { 0L }
        suspend fun runMux(out: OutputFile, ins: List<Muxer.Input>, last: Boolean): Muxer.Result {
            val done = kotlinx.coroutines.CompletableDeferred<Muxer.Result>()
            val inBytes = ins.sumOf { File(it.path).length() }
            // Ara dosya yalnızca hem kendisine hem (akışlar silinmeyecekse) kopyasına yer varsa
            val need = if (last) inBytes * 11 / 10 else inBytes * 22 / 10
            val tmp = if (freeBytes() > need + (64L shl 20)) File(dir, "out-${System.nanoTime()}.tmp") else null
            val ms0 = System.nanoTime()
            kotlin.concurrent.thread(name = "birlestirme-${e.id}") {
                try {
                    fun go(fd: java.io.FileDescriptor) = Muxer.mux(ins, fd, totalUs, active = { job?.isActive != false && !done.isCancelled }) { f ->
                        ctx.segDone = (f * (if (tmp != null) 900 else 1000)).toInt(); ctx.emit()
                    }
                    done.complete(if (tmp != null) FileOutputStream(tmp).use { go(it.fd) } else out.openRw(app).use { go(it.fileDescriptor) })
                } catch (t: Throwable) { done.completeExceptionally(t) }
            }
            val r = try { done.await() } catch (t: Throwable) { tmp?.delete(); throw t }
            val muxMs = (System.nanoTime() - ms0) / 1_000_000
            timings.append(", MP4 yazma ${speedOf(inBytes, muxMs)}${if (tmp == null) " (doğrudan, yer az)" else ""}")
            // Ses eksikse akışlarla yeniden birleştirilecek: o zaman silinmez
            if (last && !r.audioMissing) {
                streamFiles.forEach { it.delete() }
                streamsDropped = true
            }
            if (tmp != null && !streamsDropped && freeBytes() < tmp.length() + (32L shl 20)) {
                // Kopyaya yer kalmadı: ara dosya bırakılır, doğrudan son dosyaya yeniden yazılır
                tmp.delete()
                val r2 = kotlinx.coroutines.CompletableDeferred<Muxer.Result>()
                kotlin.concurrent.thread(name = "birlestirme-${e.id}") {
                    try { r2.complete(out.openRw(app).use { Muxer.mux(ins, it.fileDescriptor, totalUs, active = { job?.isActive != false && !r2.isCancelled }) { f -> ctx.segDone = (f * 1000).toInt(); ctx.emit() } }) }
                    catch (t: Throwable) { r2.completeExceptionally(t) }
                }
                timings.append(" (kopyaya yer kalmadı, doğrudan yeniden yazıldı)")
                return r2.await()
            }
            if (tmp != null) {
                val c0 = System.nanoTime()
                val prevPhase = ctx.phase
                ctx.phase = "Kaydediliyor"; ctx.segDone = 900; ctx.emit()
                try {
                    val len = tmp.length().coerceAtLeast(1)
                    out.openRw(app).use { pfd ->
                        FileOutputStream(pfd.fileDescriptor).use { os ->
                            FileInputStream(tmp).use { fin ->
                                val b = ByteArray(8 shl 20)
                                var copied = 0L
                                while (true) {
                                    val n = fin.read(b); if (n < 0) break
                                    os.write(b, 0, n); copied += n
                                    ctx.segDone = 900 + (copied * 100 / len).toInt(); ctx.emit()
                                    coroutineContext.ensureActive()
                                }
                            }
                        }
                    }
                    timings.append(", kopyalama ${speedOf(len, (System.nanoTime() - c0) / 1_000_000)}")
                } finally { tmp.delete(); ctx.phase = prevPhase }
            }
            return r
        }
        fun diag() = "Tanı: " + plans.indices.joinToString(" · ") { si -> "${if (plans[si].role == "main") "ana" else "ses[${plans[si].lang}]"} ${plans[si].segments.size} parça, ${describe(streamFiles[si])}" }
        val audioLang = plans.firstOrNull { it.role == "audio" }?.lang ?: meta["muxedLang"] ?: ""
        var usedAllAudio = false
        val report = StringBuilder()
        report.append("Tür: ${e.type} · ${plans.size} akış\n")
        plans.forEachIndexed { si, p ->
            report.append("akış$si ${if (p.role == "main") "ana" else "ses[${p.lang}] ${p.name}"}: ${p.segments.size} parça, ilk parça ${Format.host(p.segments.firstOrNull()?.url ?: "")}${p.segments.firstOrNull()?.url?.substringBefore('?')?.substringAfterLast('/')?.let { " /$it" } ?: ""}\n")
            report.append("   dosya: ${describe(streamFiles[si])}\n")
            report.append("   izler: ${Diag.tracks { ex -> ex.setDataSource(streamFiles[si].path) }}\n")
        }

        if (audioOnly) {
            val name = "$base.m4a"
            val out = OutputStore.create(app, name, "audio/mp4", folder)
            try {
                ctx.segTotal = 1000; ctx.segDone = 0
                val r = runMux(out, inputs(allAudio = !hasSepAudio, video = false), last = true)
                report.append("birleştirme (yalnız ses):\n").append(r.notes).append('\n')
                out.publish(app)
                e.outputUri = out.encode(); e.fileName = name; merged = true
                if (r.audioTracks == 0) { e.error = "Uyarı: kaynakta ses bulunamadı."; report.append(diag()).append('\n') }
            } catch (t: Throwable) {
                out.delete(app)
                if (t is kotlinx.coroutines.CancellationException || t is java.util.concurrent.CancellationException) throw t
                e.error = "Uyarı: ses dosyası oluşturulamadı; ses ayrı dosya olarak kaydedildi."
                report.append("ses dosyası hatası: ${Errors.technical(t)}\n")
            }
        } else if (meta["mode"] != "separate") {
            val name = "$base.mp4"
            val out = OutputStore.create(app, name, "video/mp4", folder)
            try {
                ctx.segTotal = 1000; ctx.segDone = 0
                val more = meta["alsoAudio"] == "1"
                var r = runMux(out, inputs(allAudio = false, video = true), last = !more)
                report.append("birleştirme:\n").append(r.notes).append('\n')
                // Seçilen ayrı ses izi boş ya da eksik çıktıysa ana akışın kendi sesiyle yeniden birleştir
                if (r.audioMissing && r.skippedAudio > 0) {
                    ctx.phase = "Ses yeniden ekleniyor"; ctx.segDone = 0; ctx.emit()
                    try { app.contentResolver.openFileDescriptor(out.uri, "rwt")?.close() } catch (_: Exception) {}
                    r = runMux(out, inputs(allAudio = true, video = true), last = !more); usedAllAudio = true
                    report.append("ses eksikti, ana akışın sesiyle yeniden:\n").append(r.notes).append('\n')
                    ctx.phase = "Birleştiriliyor"
                }
                val audioTracks = if (r.audioMissing) 0 else r.audioTracks
                out.publish(app)
                e.outputUri = out.encode()
                e.fileName = name
                merged = true
                if (audioTracks == 0) {
                    e.error = "Uyarı: dosyada ses yok ya da eksik. Sayfadaki ana listeyi (çoklu kalite) seçip yeniden deneyin."
                    report.append(diag()).append('\n')
                }
            } catch (t: Throwable) {
                out.delete(app)
                if (t is kotlinx.coroutines.CancellationException || t is java.util.concurrent.CancellationException) throw t
                e.error = if (Errors.classify(t).kind == Errors.Kind.STORAGE) "Uyarı: ${Errors.message(t)} Görüntü ve ses ayrı dosyalar olarak kaydedildi."
                    else "Uyarı: tek MP4 oluşturulamadı; görüntü ve ses ayrı dosyalar olarak kaydedildi."
                report.append("birleştirme hatası: ${Errors.technical(t)}\n")
            }
            // İstenirse ses ayrıca .m4a olarak da (müzik çalarlar, arka planda dinleme için)
            if (merged && meta["alsoAudio"] == "1" && !streamsDropped) {
                ctx.phase = "Ses ayrıca kaydediliyor"; ctx.segDone = 0; ctx.emit()
                val aname = "$base.${Lang.normalize(audioLang).ifEmpty { "ses" }}.m4a"
                val aout = OutputStore.create(app, aname, "audio/mp4", folder)
                try {
                    val r = runMux(aout, inputs(allAudio = usedAllAudio, video = false), last = true)
                    if (r.audioTracks > 0) { aout.publish(app); extras += "${aout.encode()}|${Lang.audLabel(audioLang)}" } else aout.delete(app)
                } catch (t: Throwable) {
                    aout.delete(app)
                    if (t is kotlinx.coroutines.CancellationException || t is java.util.concurrent.CancellationException) throw t
                }
                ctx.phase = "Birleştiriliyor"
            }
        }
        if (!merged) {
            for ((si, p) in plans.withIndex()) {
                // Birleştirme dosyası yazıldıktan sonra kaydedilemediyse akışlar silinmiş olabilir
                val f = streamFiles[si].takeIf { it.exists() } ?: userError("Dosya kaydedilemedi: ${e.error ?: "birleştirme sonrası yazma hatası"}")
                val ext = if (p.fmp4) (if (p.role == "audio") "m4a" else "mp4") else "ts"
                val name = if (p.role == "audio") "$base.aud-${Lang.normalize(p.lang).ifEmpty { "und" }}.$ext" else "$base.$ext"
                val out = OutputStore.create(app, name, if (ext == "ts") "video/mp2t" else if (ext == "m4a") "audio/mp4" else "video/mp4", folder)
                copyTo(f, out)
                f.delete() // sıradaki akışın kopyası için yer açılır
                out.publish(app)
                if (p.role == "main") { e.outputUri = out.encode(); e.fileName = name } else extras += "${out.encode()}|${Lang.audLabel(p.lang)}"
            }
        }

        // 4) Altyazılar (SRT, oynatıcılar dosya adından otomatik tanır)
        val subLines = (meta["subs"] ?: "").lines().filter { it.isNotBlank() }
        if (subLines.isNotEmpty()) { ctx.phase = "Altyazılar indiriliyor"; ctx.segTotal = 0; ctx.segDone = 0; ctx.emit() }
        for (line in subLines) {
            val (url, lang) = line.split('|').let { it[0] to it.getOrElse(1) { "" } }
            try {
                val srt = fetchSubtitle(url)
                if (srt.isBlank()) continue
                val name = "$base.${Lang.normalize(lang).ifEmpty { "und" }}.srt"
                val out = OutputStore.create(app, name, "application/x-subrip", folder)
                out.openRw(app).use { pfd -> FileOutputStream(pfd.fileDescriptor).use { it.write(srt.toByteArray()) } }
                out.publish(app)
                extras += "${out.encode()}|${Lang.subLabel(lang)}"
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
            }
        }
        e.extraOutputs = extras.joinToString("\n")
        report.append(throttleNote).append(timings).append('\n')
        // Tanı raporu: çıktıdaki izler ve sesin gerçekten duyulup duyulmadığı
        OutputFile.decode(e.outputUri)?.uri?.let { u ->
            report.append("çıktı ${e.fileName}: ${Diag.tracks { ex -> ex.setDataSource(app, u, null) }}\n")
            val level = Diag.audioLevel(app, u)
            report.append("ses ölçümü: $level\n")
            // Ölçüm sessiz diyorsa kartta uyarı çıksın (dokununca ayrıntı ve rapor)
            if (e.error == null && !audioOnly && (level.contains("SESSİZ") || level.startsWith("ses izi yok") || level.startsWith("ses izinde örnek yok") || level.startsWith("ses çözülemedi")))
                e.error = "Uyarı: çıktıda ses duyulmuyor. Dosyalar menüsünden tanı raporunu kopyalayıp gönderin."
        }
        e.meta = KV.encode(KV.decode(e.meta) + ("diag" to report.toString()))
        e.totalBytes = e.downloadedBytes
        dir.deleteRecursively()
    }

    private fun segFile(si: Int, i: Int) = File(dir, "${si}_$i.seg")
    private fun initFile(si: Int, url: String) = File(dir, "${si}_init_${url.hashCode().toUInt()}.seg")

    private suspend fun planHls(): List<Plan> {
        val plans = ArrayList<Plan>()
        // Tarayıcının yakaladığı liste varsa sunucuya yeniden sorma (bazı sunucular tek seferlik bağlantı verir)
        suspend fun fetch(u: String) = meta["inline:$u"] ?: Http.text(e, u)
        suspend fun load(url: String): Hls.Media {
            var u = url
            var text = fetch(u)
            if (Hls.isMaster(text)) {
                u = Hls.parseMaster(text, u).variants.firstOrNull()?.url ?: userError("Listede indirilebilir kalite bulunamadı.")
                text = fetch(u)
            }
            val m = Hls.parseMedia(text, u)
            if (m.drm) userError("Bu yayın DRM ile korunuyor; indirilemez.")
            return m
        }
        val main = load(meta["variant"] ?: e.url)
        if (main.live) userError("Canlı yayınlar bu sürümde desteklenmiyor.")
        plans += Plan("main", meta["muxedLang"] ?: "", "", main.segments, main.segments.firstOrNull()?.map != null)
        for (line in (meta["audios"] ?: "").lines().filter { it.isNotBlank() }) {
            val p = line.split('|')
            val m = load(p[0])
            plans += Plan("audio", p.getOrElse(1) { "" }, p.getOrElse(2) { "" }, m.segments, m.segments.firstOrNull()?.map != null)
        }
        return plans
    }

    /** YouTube: görüntü ve ses dosyaları 8 MB'lık aralıklarla paralel iner (YouTube tek parça hızını kısıyor). */
    private suspend fun planYoutube(): List<Plan> {
        fun ranges(url: String): List<Hls.Segment> {
            val total = YouTube.size(url)
            if (total <= 0) return listOf(Hls.Segment(url, 0.0, null, null, null, null, false, 0))
            val chunk = 8L shl 20
            return (0 until ((total + chunk - 1) / chunk).toInt()).map { i ->
                val s = i * chunk
                Hls.Segment(url, 0.0, longArrayOf(s, minOf(total, s + chunk) - 1), null, null, null, false, i.toLong())
            }
        }
        val plans = ArrayList<Plan>()
        val video = meta["variant"]?.takeIf { it.startsWith("http") } ?: userError("YouTube görüntü adresi alınamadı; videoyu yeniden açıp deneyin.")
        plans += Plan("main", "", "", ranges(video), fmp4 = true, raw = true)
        for (line in (meta["audios"] ?: "").lines().filter { it.isNotBlank() }) {
            val p = line.split('|')
            plans += Plan("audio", p.getOrElse(1) { "" }, p.getOrElse(2) { "" }, ranges(p[0]), fmp4 = true, raw = true)
        }
        val total = plans.sumOf { p -> YouTube.size(p.segments.first().url) }
        if (total > 0) e.totalBytes = total
        return plans
    }

    private suspend fun planDash(): List<Plan> {
        val mpd = Dash.parse(Http.text(e, e.url), e.url)
        if (mpd.drm) userError("Bu yayın DRM ile korunuyor; indirilemez.")
        val video = mpd.reps.firstOrNull { it.id == meta["variant"] } ?: mpd.reps.firstOrNull { it.kind == "video" }
        val plans = ArrayList<Plan>()
        video?.let { plans += Plan("main", "", "", it.segments, true) }
        for (line in (meta["audios"] ?: "").lines().filter { it.isNotBlank() }) {
            val p = line.split('|')
            mpd.reps.firstOrNull { it.id == p[0] && it.kind == "audio" }?.let { plans += Plan(if (plans.isEmpty()) "main" else "audio", it.lang, it.label, it.segments, true) }
        }
        if (plans.isEmpty()) userError("Listede indirilebilir kalite bulunamadı.")
        return plans
    }

    private suspend fun decrypt(seg: Hls.Segment, data: ByteArray): ByteArray {
        val k = seg.key ?: return data
        if (k.method != "AES-128") userError("Bu yayının şifreleme türü desteklenmiyor.")
        val key = keyCache[k.url] ?: Http.bytes(e, k.url).also { keyCache[k.url] = it }
        val iv = ByteArray(16)
        if (k.iv != null) {
            val hex = k.iv.removePrefix("0x").removePrefix("0X").padStart(32, '0')
            for (i in 0 until 16) iv[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        } else {
            var n = seg.seq
            for (i in 15 downTo 8) { iv[i] = (n and 0xff).toByte(); n = n shr 8 }
        }
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    /**
     * İndirirken birleştirme: bir akışın parçaları sırayla indikçe akış dosyasının sonuna eklenir.
     * Durum (kaçıncı parçaya gelindiği ve dosya boyu) yanındaki .pos dosyasında tutulur; indirme
     * duraklatılıp sürdürülünce kaldığı yerden devam eder. Tutarsızlıkta baştan kurulur (silinmiş parçalar
     * yeniden indirilir).
     * Eklenen parça dosyası, durum kaydedildikten hemen sonra silinir: parçalar ve akış dosyası aynı anda
     * diskte durmaz (eskiden indirme sonunda videonun iki kopyası birden duruyordu).
     */
    private inner class Joiner(val si: Int, val p: Plan, private val skipAds: Boolean) {
        val file = File(dir, "stream_$si.${if (p.fmp4) "mp4" else "ts"}")
        private val posFile = File(dir, "stream_$si.pos")
        private val sig = "${p.segments.size}|$skipAds|${p.fmp4}"
        private val lock = java.util.concurrent.locks.ReentrantLock()
        private var os: FileOutputStream? = null
        @Volatile private var next = 0
        private var lastMap: String? = null
        private var failed = false
        /** İndirme sırasında eklenen parça sayısı (tanı raporu için) */
        @Volatile var joined = 0; private set

        private fun open() {
            if (os != null) return
            var ok = false
            try {
                val st = posFile.takeIf { it.exists() }?.readText()?.split('\n')
                if (st != null && st.size >= 4 && st[0] == sig && file.exists() && file.length() >= st[2].toLong()) {
                    java.io.RandomAccessFile(file, "rw").use { it.setLength(st[2].toLong()) }
                    next = st[1].toInt(); lastMap = st[3].ifEmpty { null }; ok = true
                }
            } catch (_: Exception) {}
            if (!ok) { next = 0; lastMap = null; file.delete(); posFile.delete() }
            os = FileOutputStream(file, true)
        }

        /** Kaldığı yeri okur; indirme başlamadan çağrılır ki eklenmiş (silinmiş) parçalar yeniden indirilmesin. */
        fun prepare() {
            lock.lock()
            try { open() } finally { lock.unlock() }
        }

        /** Bu parça akış dosyasına eklendi (dosyası silinmiş olabilir). */
        fun isJoined(i: Int) = i < next

        private fun save() {
            os?.flush()
            val tmp = File(posFile.path + ".tmp")
            tmp.writeText("$sig\n$next\n${file.length()}\n${lastMap ?: ""}")
            tmp.renameTo(posFile)
        }

        /** @param all true: eksik parça atlanır ve sona kadar gidilir (indirme bitti); false: ilk eksikte durur */
        private fun run(all: Boolean, active: () -> Boolean) {
            open()
            val out = os!!
            var wrote = false
            val consumed = ArrayList<File>()
            while (next < p.segments.size && active()) {
                val seg = p.segments[next]
                if (!(skipAds && seg.isAd)) {
                    val f = segFile(si, next)
                    if (!f.exists()) { if (all) { next++; continue } else break }
                    if (seg.map != null && seg.map != lastMap) { initFile(si, seg.map).let { if (it.exists()) out.write(it.readBytes()) }; lastMap = seg.map }
                    FileInputStream(f).use { ins ->
                        // Ham ses parçaları (.aac/.mp3) başında ID3 etiketi taşır; ortadakiler okuyucuyu durdurur
                        if (!p.fmp4) skipId3(ins, f.length())
                        ins.copyTo(out, 1 shl 20)
                    }
                    wrote = true
                    consumed += f
                    if (!all) joined++
                }
                next++
            }
            if (wrote || all) save()
            // Durum kaydedildi: eklenen parçalar artık gereksiz. Kayıttan önce kapanırsa parça yerinde kalır ve
            // sürdürmede yeniden eklenir (akış dosyası kayıtlı boyuta kısaltılır), veri kaybolmaz.
            for (f in consumed) f.delete()
        }

        /** İndirme işçileri her parçadan sonra çağırır; başka bir işçi zaten ekliyorsa beklemeden döner. */
        fun advance(all: Boolean) {
            if (failed || !lock.tryLock()) return
            try { run(all) { true } } catch (x: java.io.IOException) { failed = true; throw x } finally { lock.unlock() }
        }

        /** İndirme bitince kalanları ekler ve akış dosyasını verir. */
        suspend fun finish(): File {
            val job = coroutineContext[kotlinx.coroutines.Job]
            lock.lock()
            try {
                // İndirme sırasında yazma hatası olduysa: dosya kayıtlı son duruma döndürülüp kaldığı yerden sürdürülür
                if (failed) { try { os?.close() } catch (_: Exception) {}; os = null; failed = false }
                run(true) { job?.isActive != false }
            } finally { lock.unlock() }
            coroutineContext.ensureActive()
            close()
            return file
        }

        fun close() {
            lock.lock()
            try { try { os?.close() } catch (_: Exception) {}; os = null } finally { lock.unlock() }
        }
    }

    private fun speedOf(bytes: Long, ms: Long) = "%.1f sn (%.1f MB/sn)".format(java.util.Locale.US, ms / 1000.0, if (ms > 0) bytes / 1048576.0 / (ms / 1000.0) else 0.0)

    /** Tanı için: dosyanın biçimi ve içindeki izler ("TS, görüntü 1 ses 0" ya da ilk baytlar). */
    private fun describe(f: File): String = try {
        if (!f.exists()) "dosya yok" else {
            val h = ByteArray(12)
            val n = FileInputStream(f).use { it.read(h) }
            val fmt = when {
                n >= 1 && h[0] == 0x47.toByte() -> "TS"
                n >= 3 && h[0] == 'I'.code.toByte() && h[1] == 'D'.code.toByte() && h[2] == '3'.code.toByte() -> "ID3+ses"
                n >= 2 && h[0] == 0xFF.toByte() && (h[1].toInt() and 0xF0) == 0xF0 -> "ADTS"
                n >= 8 && String(h, 4, 4, Charsets.ISO_8859_1) in setOf("ftyp", "styp", "moof", "sidx") -> "MP4"
                else -> "bilinmiyor(" + h.take(n.coerceAtLeast(0).coerceAtMost(8)).joinToString("") { "%02X".format(it) } + ")"
            }
            val ex = android.media.MediaExtractor()
            try {
                ex.setDataSource(f.path)
                var v = 0; var a = 0
                val mimes = ArrayList<String>()
                for (i in 0 until ex.trackCount) {
                    val m = ex.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME) ?: continue
                    if (m.startsWith("video/")) v++ else if (m.startsWith("audio/")) { a++; mimes += m.removePrefix("audio/") }
                }
                "$fmt, görüntü $v ses $a${if (mimes.isNotEmpty()) " (" + mimes.joinToString() + ")" else ""}, ${f.length() / 1048576} MB"
            } catch (t: Throwable) { "$fmt, okunamadı, ${f.length() / 1048576} MB" } finally { ex.release() }
        }
    } catch (t: Throwable) { "?" }

    /** Akış ID3v2 etiketiyle başlıyorsa etiketi atlar; değilse konumu değiştirmez. */
    private fun skipId3(ins: FileInputStream, len: Long) {
        if (len < 10) return
        val ch = ins.channel
        val h = ByteArray(10)
        val n = ins.read(h)
        if (n == 10 && h[0] == 'I'.code.toByte() && h[1] == 'D'.code.toByte() && h[2] == '3'.code.toByte()) {
            val size = ((h[6].toInt() and 0x7F) shl 21) or ((h[7].toInt() and 0x7F) shl 14) or ((h[8].toInt() and 0x7F) shl 7) or (h[9].toInt() and 0x7F)
            val footer = if ((h[5].toInt() and 0x10) != 0) 10 else 0
            ch.position(minOf(len, 10L + size + footer))
        } else ch.position(0)
    }

    private fun copyTo(f: File, out: OutputFile) {
        out.openRw(app).use { pfd -> FileOutputStream(pfd.fileDescriptor).use { os -> FileInputStream(f).use { it.copyTo(os, 1 shl 20) } } }
    }

    private suspend fun fetchSubtitle(url: String): String {
        // BOM kalırsa "WEBVTT" tanınmıyor ve VTT, .srt adıyla bozuk kaydediliyordu (oynatıcı hiç göstermiyordu)
        val text = Http.text(e, url).removePrefix("\uFEFF")
        val cues = ArrayList<Subtitles.Cue>()
        if (text.trimStart().startsWith("#EXTM3U")) {
            val m = Hls.parseMedia(text, url)
            // Parçalar sırayla değil 8'er 8'er paralel iner (uzun filmde 1000+ küçük dosya olabiliyor)
            val segs = m.segments.filter { !(meta["skipAds"] != "0" && it.isAd) }
            ctx.segTotal += segs.size; ctx.emit()
            val gate = kotlinx.coroutines.sync.Semaphore(8)
            val parts = kotlinx.coroutines.coroutineScope {
                segs.map { seg ->
                    async {
                        gate.withPermit {
                            // Tek parça inmezse altyazının tamamı kaybolmasın
                            val r = try { Subtitles.parseVtt(Http.text(e, seg.url)) } catch (t: Throwable) {
                                if (t is kotlinx.coroutines.CancellationException) throw t
                                emptyList<Subtitles.Cue>() to null
                            }
                            synchronized(ctx) { ctx.segDone++ }
                            ctx.emit()
                            r
                        }
                    }
                }.awaitAll()
            }
            val base = parts.mapNotNull { it.second }.minOrNull()
            for ((c, ts) in parts) {
                val off = if (ts != null && base != null) (ts - base) / 90000.0 else 0.0
                c.forEach { cues += it.copy(start = it.start + off, end = it.end + off) }
            }
        } else {
            // WEBVTT, SRT ya da başlıksız VTT: hepsi temiz SRT'ye çevrilir (oynatıcılar ".000" biçimli
            // VTT zamanlarını SRT içinde okuyamıyor). Çözülemeyen biçim (ASS vb.) olduğu gibi kalır.
            cues += Subtitles.parseVtt(text).first
            if (cues.isEmpty()) return text
        }
        return Subtitles.toSrt(cues)
    }
}
