package com.movietrack.app.player

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.text.HtmlCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.movietrack.app.App
import com.movietrack.app.download.Subtitles
import com.movietrack.app.util.Lang
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Dahili oynatıcı (Media3/ExoPlayer): MP4/MKV/WebM/TS, harici altyazı (SRT/VTT/ASS), harici ses dosyası
 * (ayrı indirilen ya da sonradan seçilen ses videoya eklenir), ses/altyazı izi seçimi, hız, döngü,
 * en-boy oranı, iki parmakla yakınlaştırma/kaydırma, altyazı zamanlaması, ayarlanabilir sarma adımı,
 * Resim içinde Resim, ekran kilidi, parlaklık/ses hareketleri (hangi yarının neyi ayarlayacağı
 * ayarlardan seçilir), kaldığın yerden devam.
 */
@UnstableApi
class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var hint: TextView
    private lateinit var overlay: SubtitleView
    private lateinit var syncPanel: View
    private lateinit var syncValue: TextView
    private var locked = false
    private var resumeKey = ""
    private val settings get() = App.instance.settings
    private val seekMs get() = settings.seekStepSec * 1000L

    // Yakınlaştırma (MX Player gibi iki parmakla büyüt/küçült, sürükle)
    private var zoom = 1f
    private var pinching = false

    // Harici altyazılar uygulamada çizilir: böylece zamanlaması canlı kaydırılabilir
    private class ExtSub(val lang: String, val cues: List<Subtitles.Cue>, val name: String? = null)
    private val extSubs = ArrayList<ExtSub>()
    private var extIdx = -1
    private var subOffsetMs = 0L
    private var shownCues: List<Subtitles.Cue>? = null

    // Videoya ayrı dosyadan eklenen sesler: MergingMediaSource ile aynı zaman çizgisinde çalınır.
    // Kaynak sırası: 0 = video, 1.. = extAudios. picked: kullanıcının oynatıcıdan seçtiği dosya.
    private class ExtAudio(val uri: String, val name: String, val picked: Boolean)
    private val extAudios = ArrayList<ExtAudio>()
    // ExoPlayer'ın kendisinin çizdiği altyazılar (ASS/SSA vb. uygulamada okunamayanlar)
    private val exoSubConfigs = ArrayList<MediaItem.SubtitleConfiguration>()
    private var mainUri: Uri = Uri.EMPTY
    /** İzler hazır olunca seçilecek harici ses kaynağı (1..), yoksa -1 */
    private var pendingAudioSource = -1
    /** Az önce eklenen dosya açılamazsa geri alınsın diye */
    private var justAdded: (() -> Unit)? = null

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { onAudioPicked(it) }
    private val pickSub = registerForActivityResult(ActivityResultContracts.OpenDocument()) { onSubPicked(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val uri = intent.data ?: run { finish(); return }
        resumeKey = uri.toString()
        subOffsetMs = settings.subOffset(resumeKey)

        // Telefonda yerleşik çözücüsü olmayan sesler (AC-3, E-AC-3, DTS, MP2…): uygulamada FFmpeg çözücü
        // varsa onunla çalınır (indirici'de var; yoksa bu ayar etkisizdir)
        player = ExoPlayer.Builder(this, androidx.media3.exoplayer.DefaultRenderersFactory(this)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)).build()
        // İleri/geri düğmeleri ayarlardaki adımı kullanır (oynatıcı yeniden kurulmadan değişebilir)
        val controlled = object : ForwardingPlayer(player) {
            override fun getSeekBackIncrement() = seekMs
            override fun getSeekForwardIncrement() = seekMs
            override fun seekBack() = seekBy(-seekMs)
            override fun seekForward() = seekBy(seekMs)
        }
        view = PlayerView(this).apply {
            this.player = controlled
            keepScreenOn = true
            setBackgroundColor(Color.BLACK)
        }
        overlay = SubtitleView(this).apply { setUserDefaultStyle(); setUserDefaultTextSize() }
        hint = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; visibility = View.GONE
            setBackgroundColor(0x88000000.toInt()); setPadding(24, 12, 24, 12)
        }
        val root = FrameLayout(this)
        root.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(overlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(toolbar(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        syncPanel = syncPanel()
        root.addView(syncPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(150) })
        setContentView(root)
        setupGestures()

        mainUri = uri
        val subs = intent.getStringArrayListExtra("subs").orEmpty()
        val langs = intent.getStringArrayListExtra("subLangs").orEmpty()
        // SRT/VTT okunabiliyorsa uygulamada çizilir; okunamayanlar (ASS vb.) ExoPlayer'a bırakılır
        val rest = ArrayList<Pair<String, String?>>()
        subs.forEachIndexed { i, s -> if (!addParsedSub(s, langs.getOrNull(i) ?: "")) rest += s to langs.getOrNull(i) }
        // Daha önce bu video için oynatıcıdan eklenen altyazı dosyaları
        settings.extSubs(resumeKey).filter { it !in subs && canRead(it) }.forEach { s ->
            val name = fileName(Uri.parse(s))
            if (!addParsedSub(s, langFromName(name), name)) rest += s to langFromName(name)
        }
        val preferred = langs.firstOrNull()?.let { Lang.normalize(it) }?.takeIf { it.isNotEmpty() && it != "und" }
        if (extSubs.isNotEmpty()) extIdx = extSubs.indexOfFirst { Lang.normalize(it.lang) == preferred }.coerceAtLeast(0)
        rest.forEachIndexed { i, (s, lang) -> exoSubConfigs += subConfig(s, lang, extIdx < 0 && i == 0) }

        // Ayrı indirilen ses dosyaları ve daha önce oynatıcıdan eklenenler
        val auds = intent.getStringArrayListExtra("audios").orEmpty()
        val audLangs = intent.getStringArrayListExtra("audioLangs").orEmpty()
        auds.forEachIndexed { i, a ->
            val l = audLangs.getOrNull(i).orEmpty().takeIf { it.isNotBlank() && it != "und" }
            extAudios += ExtAudio(a, if (l != null) "İndirilen ses (${Lang.displayName(l)})" else "İndirilen ses", picked = false)
        }
        settings.extAudio(resumeKey).filter { u -> extAudios.none { it.uri == u } && canRead(u) }.forEach {
            extAudios += ExtAudio(it, fileName(Uri.parse(it)), picked = true)
        }
        // Kullanıcı daha önce bir ses seçtiyse yine onunla başlasın
        extAudios.indexOfLast { it.picked }.takeIf { it >= 0 }?.let { pendingAudioSource = it + 1 }

        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            // Uygulamanın çizdiği altyazı açıkken videodaki altyazı üst üste binmesin
            if (extIdx >= 0) setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            else if (exoSubConfigs.isNotEmpty()) setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(preferred).setSelectUndeterminedTextLanguage(true)
        }.build()
        view.setShowSubtitleButton(extSubs.isEmpty())
        player.setMediaSource(buildSource())
        player.prepare()
        val pos = settings.resumePosition(resumeKey)
        if (pos > 5000) player.seekTo(pos)
        player.playWhenReady = true
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) settings.saveResumePosition(resumeKey, 0)
                if (state == Player.STATE_READY) justAdded = null
            }

            override fun onTracksChanged(tracks: Tracks) {
                val src = pendingAudioSource
                if (src < 1) return
                val g = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && sourceOf(it) == src } ?: return
                pendingAudioSource = -1
                selectAudio(g)
            }

            override fun onPlayerError(error: PlaybackException) {
                // Yeni eklenen dosya bozuksa onu çıkarıp videoya kaldığı yerden devam et
                val undo = justAdded ?: return
                justAdded = null
                undo()
                flash("Dosya açılamadı, kaldırıldı")
                reload()
            }
        })
        overlay.post(subTick)
    }

    // ------------------------------------------------------------------ altyazı çizimi

    private val subTick = object : Runnable {
        override fun run() {
            renderSubs()
            overlay.postDelayed(this, 50)
        }
    }

    private fun renderSubs() {
        val sub = extSubs.getOrNull(extIdx)
        val now = if (sub == null) emptyList() else {
            val t = (player.currentPosition - subOffsetMs) / 1000.0
            sub.cues.filter { it.start <= t && t < it.end }
        }
        if (now == shownCues) return
        shownCues = now
        overlay.setCues(now.map { c ->
            androidx.media3.common.text.Cue.Builder()
                .setText(HtmlCompat.fromHtml(c.text.replace("\n", "<br>"), HtmlCompat.FROM_HTML_MODE_LEGACY))
                .build()
        })
    }

    private fun chooseSubtitle() {
        val names = extSubs.map { s -> s.name ?: if (s.lang.isBlank()) "Altyazı" else Lang.displayName(s.lang) } +
            listOf("Videodaki altyazılar…", "Harici altyazı dosyası ekle…", "Kapalı")
        AlertDialog.Builder(this).setTitle("Altyazı")
            .setSingleChoiceItems(names.toTypedArray(), if (extIdx >= 0) extIdx else -1) { d, which ->
                d.dismiss()
                when {
                    which < extSubs.size -> { extIdx = which; textTracks(false) }
                    which == extSubs.size -> { extIdx = -1; textTracks(true); trackDialog(C.TRACK_TYPE_TEXT, "Altyazı") }
                    which == extSubs.size + 1 -> pickSub.launch(arrayOf("*/*"))
                    else -> { extIdx = -1; textTracks(false) }
                }
                renderSubs()
            }.show()
    }

    /** SRT/VTT dosyasını okuyup uygulamanın çizdiği altyazılara ekler; okunamazsa false. */
    private fun addParsedSub(uri: String, lang: String, name: String? = null): Boolean {
        val cues = try {
            val text = contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes().toString(Charsets.UTF_8) }?.removePrefix("\uFEFF") ?: ""
            if (text.contains("[Script Info]")) emptyList() else Subtitles.parseVtt(text).first.sortedBy { it.start }
        } catch (_: Exception) { emptyList() }
        if (cues.isEmpty()) return false
        extSubs += ExtSub(lang, cues, name)
        return true
    }

    private fun subConfig(uri: String, lang: String?, default: Boolean, name: String = uri): MediaItem.SubtitleConfiguration {
        val mime = when {
            name.endsWith(".vtt", true) -> MimeTypes.TEXT_VTT
            name.endsWith(".ass", true) || name.endsWith(".ssa", true) -> MimeTypes.TEXT_SSA
            name.endsWith(".ttml", true) || name.endsWith(".dfxp", true) -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        return MediaItem.SubtitleConfiguration.Builder(Uri.parse(uri)).setMimeType(mime).setLanguage(lang)
            .setLabel("subtitle:${Lang.normalize(lang).ifEmpty { "und" }}")
            .setSelectionFlags(if (default) C.SELECTION_FLAG_DEFAULT else 0).build()
    }

    private fun onSubPicked(uri: Uri?) {
        uri ?: return
        keepAccess(uri)
        val s = uri.toString()
        val name = fileName(uri)
        val lang = langFromName(name)
        if (addParsedSub(s, lang, name)) {
            extIdx = extSubs.size - 1
            textTracks(false)
            view.setShowSubtitleButton(false)
            renderSubs()
        } else if (Regex("\\.(ass|ssa|vtt|srt|ttml|dfxp)$", RegexOption.IGNORE_CASE).containsMatchIn(name)) {
            // Uygulamanın okuyamadığı biçim (ASS gibi): ExoPlayer çizer, kaynak yeniden kurulur
            val cfg = subConfig(s, lang.ifEmpty { null }, true, name)
            for (k in exoSubConfigs.indices) exoSubConfigs[k] = exoSubConfigs[k].buildUpon().setSelectionFlags(0).build()
            exoSubConfigs += cfg
            extIdx = -1
            renderSubs()
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).clearOverridesOfType(C.TRACK_TYPE_TEXT).build()
            justAdded = { exoSubConfigs.remove(cfg); settings.saveExtSubs(resumeKey, settings.extSubs(resumeKey) - s) }
            reload()
        } else {
            flash("Bu dosya altyazı olarak okunamadı")
            return
        }
        settings.saveExtSubs(resumeKey, (settings.extSubs(resumeKey) + s).distinct())
        flash("Altyazı eklendi: $name")
    }

    private fun textTracks(on: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on).build()
    }

    /** Altta açılan zamanlama paneli: video oynamaya devam eder, değişiklik anında görünür. */
    private fun syncPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; visibility = View.GONE
            background = GradientDrawable().apply { setColor(0xDD202020.toInt()); cornerRadius = dp(16).toFloat() }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true // dokunuşlar alttaki oynatıcıya geçmesin
        }
        syncValue = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER }
        panel.addView(syncValue)
        panel.addView(TextView(this).apply {
            text = "Altyazı erken geliyorsa +, geç geliyorsa − bas"; setTextColor(0xFFBBBBBB.toInt()); textSize = 12f; gravity = Gravity.CENTER
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun b(label: String, action: () -> Unit) = row.addView(TextView(this).apply {
            text = label; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = dp(10).toFloat() }
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(3), dp(8), dp(3), 0) })
        b("−1") { shiftSubs(-1000) }
        b("−0,1") { shiftSubs(-100) }
        b("Sıfırla") { shiftSubs(-subOffsetMs) }
        b("+0,1") { shiftSubs(100) }
        b("+1") { shiftSubs(1000) }
        b("✓") { panel.visibility = View.GONE }
        panel.addView(row)
        return panel
    }

    private fun shiftSubs(deltaMs: Long) {
        subOffsetMs += deltaMs
        settings.saveSubOffset(resumeKey, subOffsetMs)
        updateSyncLabel()
        renderSubs()
    }

    private fun updateSyncLabel() {
        val s = String.format(Locale("tr"), "%+.1f sn", subOffsetMs / 1000.0)
        syncValue.text = when {
            subOffsetMs > 0 -> "Altyazı gecikmesi: $s (daha geç)"
            subOffsetMs < 0 -> "Altyazı gecikmesi: $s (daha erken)"
            else -> "Altyazı gecikmesi: 0 sn"
        }
    }

    private fun openSync() {
        if (extSubs.isEmpty()) { flash("Zamanı ayarlanabilir altyazı yok"); return }
        if (extIdx < 0) { extIdx = 0; textTracks(false) }
        updateSyncLabel()
        syncPanel.visibility = if (syncPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ üst çubuk ve menü

    private fun toolbar(): View {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(16, 48, 16, 0) }
        fun btn(icon: Int, desc: String, onClick: (View) -> Unit) = ImageButton(this).apply {
            setImageResource(icon); contentDescription = desc; background = null; setColorFilter(Color.WHITE)
            setOnClickListener(onClick)
        }.also { bar.addView(it, LinearLayout.LayoutParams(120, 120)) }
        val title = TextView(this).apply { text = intent.getStringExtra("title") ?: ""; setTextColor(Color.WHITE); textSize = 15f; maxLines = 1 }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        // Altyazı zamanlaması: kayan altyazıyı düzeltmek için
        bar.addView(TextView(this).apply {
            text = "Aa±"; contentDescription = "Altyazı zamanlaması"; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            setOnClickListener { openSync() }
        }, LinearLayout.LayoutParams(120, 120))
        btn(android.R.drawable.ic_menu_manage, "Hız ve ayarlar") { v -> menu(v) }
        btn(android.R.drawable.ic_menu_crop, "En-boy oranı") {
            resetZoom()
            view.resizeMode = when (view.resizeMode) {
                AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            flash(when (view.resizeMode) { AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Yakınlaştır"; AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Doldur"; else -> "Sığdır" })
        }
        btn(android.R.drawable.ic_lock_lock, "Ekranı kilitle") {
            locked = !locked
            view.useController = !locked
            flash(if (locked) "Kilitli (açmak için kilide dokun)" else "Kilit açıldı")
        }
        if (Build.VERSION.SDK_INT >= 26) btn(android.R.drawable.ic_menu_slideshow, "Resim içinde Resim") { enterPip() }
        return bar
    }

    private fun menu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        val speed = pm.menu.addSubMenu("Hız (${player.playbackParameters.speed}×)")
        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { sp -> speed.add("${sp}×").setOnMenuItemClickListener { player.playbackParameters = PlaybackParameters(sp); true } }
        val step = pm.menu.addSubMenu("Sarma adımı (${settings.seekStepSec} sn)")
        SEEK_STEPS.forEach { sec ->
            step.add("$sec sn").setOnMenuItemClickListener { settings.seekStepSec = sec; flash("Sarma adımı: $sec sn"); true }
        }
        pm.menu.add("Ses izi seç (aud:)").setOnMenuItemClickListener { chooseAudio(); true }
        pm.menu.add("Altyazı seç (subtitle:)").setOnMenuItemClickListener { chooseSubtitle(); true }
        pm.menu.add("Harici ses dosyası ekle…").setOnMenuItemClickListener { launchAudioPicker(); true }
        pm.menu.add("Harici altyazı dosyası ekle…").setOnMenuItemClickListener { pickSub.launch(arrayOf("*/*")); true }
        pm.menu.add("Altyazı zamanlaması").setOnMenuItemClickListener { openSync(); true }
        if (zoom != 1f) pm.menu.add("Yakınlaştırmayı sıfırla").setOnMenuItemClickListener { resetZoom(); true }
        pm.menu.add(if (player.repeatMode == Player.REPEAT_MODE_ONE) "Döngüyü kapat" else "Döngü").setOnMenuItemClickListener {
            player.repeatMode = if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE; true
        }
        pm.show()
    }

    private fun trackDialog(type: Int, title: String) {
        TrackSelectionDialogBuilder(this, title, player, type).build().show()
    }

    // ------------------------------------------------------------------ harici ses

    private fun buildSource(): MediaSource {
        val item = MediaItem.Builder().setUri(mainUri).setSubtitleConfigurations(exoSubConfigs).build()
        val main = DefaultMediaSourceFactory(this).createMediaSource(item)
        if (extAudios.isEmpty()) return main
        val factory = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(this))
        val audios: List<MediaSource> = extAudios.map { factory.createMediaSource(MediaItem.fromUri(it.uri)) }
        // Zaman çizgisi videodan gelir; ses dosyası kısa/uzun olsa da video boyunca oynar
        return MergingMediaSource(main, *audios.toTypedArray())
    }

    /** Kaynağı yeniden kurar (ses/altyazı eklenince); konum ve oynatma durumu korunur. */
    private fun reload() {
        val pos = player.currentPosition.coerceAtLeast(0)
        val play = player.playWhenReady
        player.setMediaSource(buildSource(), pos)
        player.prepare()
        player.playWhenReady = play
    }

    /** İzin MergingMediaSource'taki kaynak sırası (0: video, 1..: harici sesler). Kimlik "kaynak:iz" biçimindedir. */
    private fun sourceOf(g: Tracks.Group): Int =
        if (extAudios.isEmpty()) 0 else g.mediaTrackGroup.id.substringBefore(':').toIntOrNull() ?: 0

    private fun selectAudio(g: Tracks.Group) {
        if (!g.isTrackSupported(0)) { flash("Bu ses biçimi bu telefonda çalınamıyor"); return }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0))
            .build()
    }

    private fun audioName(g: Tracks.Group, i: Int): String {
        val f = g.getTrackFormat(0)
        val src = sourceOf(g)
        val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { Lang.displayName(it) }
        val details = listOfNotNull(
            f.label?.takeIf { it.isNotBlank() && !it.startsWith("aud:") } ?: lang,
            codecName(f),
            when (f.channelCount) { Format.NO_VALUE, 0 -> null; 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${f.channelCount} kanal" },
        )
        val base = if (src >= 1) "Harici: ${extAudios.getOrNull(src - 1)?.name ?: "ses"}" else "Videodaki ses ${i + 1}"
        return base + (if (details.isNotEmpty()) " · " + details.joinToString(" · ") else "") +
            (if (!g.isTrackSupported(0)) " (çalınamaz)" else "")
    }

    private fun codecName(f: Format): String? = when (f.sampleMimeType) {
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_AC3 -> "AC-3"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_VORBIS -> "Vorbis"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        null -> null
        else -> f.sampleMimeType!!.substringAfter('/').uppercase(Locale.ROOT)
    }

    private fun chooseAudio() {
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val names = groups.mapIndexed { i, g -> audioName(g, i) }.toMutableList()
        names += "Harici ses dosyası ekle…"
        val picked = extAudios.any { it.picked }
        if (picked) names += "Eklenen harici sesleri kaldır"
        AlertDialog.Builder(this).setTitle("Ses")
            .setSingleChoiceItems(names.toTypedArray(), groups.indexOfFirst { it.isSelected }) { d, which ->
                d.dismiss()
                when {
                    which < groups.size -> selectAudio(groups[which])
                    which == groups.size -> launchAudioPicker()
                    else -> {
                        extAudios.removeAll { it.picked }
                        settings.saveExtAudio(resumeKey, emptyList())
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
                        reload()
                        flash("Harici sesler kaldırıldı")
                    }
                }
            }.show()
    }

    private fun launchAudioPicker() {
        // .m4a/.mka dosyaları bazı dosya yöneticilerinde video ya da bilinmeyen tür görünür
        pickAudio.launch(arrayOf("audio/*", "video/*", "application/octet-stream"))
    }

    private fun onAudioPicked(uri: Uri?) {
        uri ?: return
        keepAccess(uri)
        val s = uri.toString()
        val idx = extAudios.indexOfFirst { it.uri == s }
        if (idx >= 0) {
            player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && sourceOf(it) == idx + 1 }?.let { selectAudio(it) }
            flash("Bu ses zaten ekli, ona geçildi")
            return
        }
        val a = ExtAudio(s, fileName(uri), picked = true)
        extAudios += a
        settings.saveExtAudio(resumeKey, extAudios.filter { it.picked }.map { it.uri })
        pendingAudioSource = extAudios.size
        justAdded = {
            extAudios.remove(a)
            pendingAudioSource = -1
            settings.saveExtAudio(resumeKey, extAudios.filter { it.picked }.map { it.uri })
        }
        reload()
        flash("Ses eklendi: ${a.name}")
    }

    // ------------------------------------------------------------------ dosya yardımcıları

    private fun keepAccess(uri: Uri) {
        // Video bir dahaki açılışta da aynı dosyaları bulsun
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
    }

    private fun canRead(uri: String): Boolean = try {
        contentResolver.openInputStream(Uri.parse(uri))?.use { true } ?: false
    } catch (_: Exception) { false }

    private fun fileName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment?.substringAfterLast('/') ?: "dosya"
    }

    /** "Film.tr.srt" → "tr" (tanınan bir dil kodu yoksa boş) */
    private fun langFromName(name: String): String {
        val code = name.substringBeforeLast('.').substringAfterLast('.', "")
        return if (code.length in 2..3 && Lang.displayName(code) != "Bilinmeyen dil") code else ""
    }

    // ------------------------------------------------------------------ hareketler

    private fun contentFrame(): View? = view.findViewById(androidx.media3.ui.R.id.exo_content_frame)

    private fun applyZoom() {
        val f = contentFrame() ?: return
        f.scaleX = zoom; f.scaleY = zoom
        // Görüntü ekrandan tamamen kaçmasın
        val mx = (f.width * (zoom - 1f) / 2f).coerceAtLeast(0f)
        val my = (f.height * (zoom - 1f) / 2f).coerceAtLeast(0f)
        f.translationX = f.translationX.coerceIn(-mx, mx)
        f.translationY = f.translationY.coerceIn(-my, my)
    }

    private fun resetZoom() {
        zoom = 1f
        contentFrame()?.apply { translationX = 0f; translationY = 0f }
        applyZoom()
    }

    private fun seekBy(d: Long) {
        val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + d).coerceIn(0, dur))
    }

    private var seekSum = 0L
    private var lastSeekAt = 0L
    private var scrollMode = 0 // 0: henüz belli değil, 1: dikey (ses/parlaklık), -1: yoksay
    private var startBright = 0.5f
    private var startVol = 0f

    private fun systemBrightness(): Float = try {
        android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
    } catch (_: Exception) { 0.5f }

    private fun bar(f: Float): String {
        val n = (f.coerceIn(0f, 1f) * 10).roundToInt()
        return "▮".repeat(n) + "▯".repeat(10 - n)
    }

    /**
     * Dikey kaydırma: sol ve sağ yarının işi ayarlardan seçilir (varsayılan sol parlaklık, sağ ses).
     * Çift dokunma: kenarlarda ayarlardaki adım kadar sarar (art arda dokundukça toplanır), ortada oynat/duraklat. İki parmak: büyüt/küçült ve sürükle.
     */
    private fun setupGestures() {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        var lastFx = 0f; var lastFy = 0f
        val sgd = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                if (locked) return false
                lastFx = d.focusX; lastFy = d.focusY
                return true
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoom = (zoom * d.scaleFactor).coerceIn(0.25f, 4f)
                contentFrame()?.let { f ->
                    f.translationX += d.focusX - lastFx
                    f.translationY += d.focusY - lastFy
                }
                lastFx = d.focusX; lastFy = d.focusY
                applyZoom()
                flash("%${(zoom * 100).roundToInt()}")
                return true
            }

            override fun onScaleEnd(d: ScaleGestureDetector) {
                // %100'e yakınsa tam oturt (orijinal boyuta dönmek kolay olsun)
                if (abs(zoom - 1f) < 0.06f) { resetZoom(); flash("Orijinal boyut") }
            }
        })
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            // Değer parmağın toplam yolundan hesaplanır: küçük adımlar kaybolmaz, geri sürükleyince geri gelir
            override fun onDown(e: MotionEvent): Boolean {
                scrollMode = 0
                val b = window.attributes.screenBrightness
                startBright = if (b >= 0) b else systemBrightness()
                startVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                return false
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (locked || pinching || e1 == null || e2.pointerCount > 1) return false
                val h = view.height.toFloat().coerceAtLeast(1f)
                if (scrollMode == 0) {
                    scrollMode = if (abs(e2.y - e1.y) > abs(e2.x - e1.x)) 1 else -1
                    // Üst/alt kenardan sistem çubuğu çekilirken tetiklenmesin
                    if (e1.y < h * 0.06f || e1.y > h * 0.94f) scrollMode = -1
                }
                if (scrollMode != 1) return false
                val action = if (e1.x < view.width / 2) settings.gestureLeft else settings.gestureRight
                // Ekran yüksekliğinin %80'i kadar kaydırma: en düşükten en yükseğe
                val frac = (e1.y - e2.y) / (h * 0.8f)
                when (action) {
                    "brightness" -> {
                        val lp = window.attributes
                        lp.screenBrightness = (startBright + frac).coerceIn(0.01f, 1f)
                        window.attributes = lp
                        flash("☀ Parlaklık  ${bar(lp.screenBrightness)}  %${(lp.screenBrightness * 100).roundToInt()}")
                    }
                    "volume" -> {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        val v = (startVol + frac * max).roundToInt().coerceIn(0, max)
                        if (v != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                        flash((if (v == 0) "🔇 Ses" else "🔊 Ses") + "  ${bar(v.toFloat() / max)}  $v/$max")
                    }
                    else -> return false
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked || pinching) return false
                val third = view.width / 3f
                if (e.x in third..third * 2) {
                    player.playWhenReady = !player.playWhenReady
                    flash(if (player.playWhenReady) "▶" else "❚❚")
                    return true
                }
                val d = if (e.x < third) -seekMs else seekMs
                val now = System.currentTimeMillis()
                seekSum = if (now - lastSeekAt < 1500 && (seekSum > 0) == (d > 0)) seekSum + d else d
                lastSeekAt = now
                seekBy(d)
                flash(if (d < 0) "« ${abs(seekSum) / 1000} sn" else "${seekSum / 1000} sn »")
                return true
            }
        })
        view.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) pinching = false
            sgd.onTouchEvent(ev)
            if (ev.pointerCount > 1 || sgd.isInProgress) pinching = true
            if (pinching) return@setOnTouchListener ev.actionMasked != MotionEvent.ACTION_UP
            gd.onTouchEvent(ev)
            // Ses/parlaklık kaydırması bitince parmak kalkarken denetimler açılıp kapanmasın
            scrollMode == 1 && ev.actionMasked != MotionEvent.ACTION_DOWN
        }
    }

    private fun flash(text: String) {
        hint.text = text
        hint.visibility = View.VISIBLE
        hint.removeCallbacks(hideHint)
        hint.postDelayed(hideHint, 900)
    }

    private val hideHint = Runnable { hint.visibility = View.GONE }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    // ------------------------------------------------------------------ PiP ve yaşam döngüsü

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26) return
        val fmt = player.videoFormat
        val ratio = if (fmt != null && fmt.width > 0 && fmt.height > 0) Rational(fmt.width, fmt.height) else Rational(16, 9)
        val clamped = when {
            ratio.toFloat() > 2.39f -> Rational(239, 100)
            ratio.toFloat() < 0.42f -> Rational(42, 100)
            else -> ratio
        }
        enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(clamped).build())
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player.isPlaying) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        view.useController = !isInPictureInPictureMode && !locked
        if (isInPictureInPictureMode) { syncPanel.visibility = View.GONE; resetZoom() }
    }

    override fun onStop() {
        super.onStop()
        settings.saveResumePosition(resumeKey, player.currentPosition)
        if (!isInPictureInPictureMode) player.pause()
    }

    override fun onDestroy() {
        if (::overlay.isInitialized) overlay.removeCallbacks(subTick)
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    companion object {
        val SEEK_STEPS = listOf(5, 10, 15, 30, 60)
    }
}
