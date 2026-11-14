package com.movietrack.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Kullanıcı ayarları (SharedPreferences). Değişiklikler `version` akışıyla arayüze bildirilir. */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private fun changed() { _version.value++ }

    var maxConcurrent: Int
        get() = p.getInt("maxConcurrent", 3)
        set(v) { p.edit().putInt("maxConcurrent", v.coerceIn(1, 10)).apply(); changed() }

    /** Dosya başına bağlantı (parça) sayısı — 1DM'deki gibi en fazla 16. */
    var parts: Int
        get() = p.getInt("parts", 8)
        set(v) { p.edit().putInt("parts", v.coerceIn(1, 16)).apply(); changed() }

    var wifiOnly: Boolean
        get() = p.getBoolean("wifiOnly", false)
        set(v) { p.edit().putBoolean("wifiOnly", v).apply(); changed() }

    var adBlock: Boolean
        get() = p.getBoolean("adBlock", true)
        set(v) { p.edit().putBoolean("adBlock", v).apply(); changed() }

    var popupBlock: Boolean
        get() = p.getBoolean("popupBlock", true)
        set(v) { p.edit().putBoolean("popupBlock", v).apply(); changed() }

    var clipboardDetect: Boolean
        get() = p.getBoolean("clipboardDetect", true)
        set(v) { p.edit().putBoolean("clipboardDetect", v).apply(); changed() }

    var desktopMode: Boolean
        get() = p.getBoolean("desktopMode", false)
        set(v) { p.edit().putBoolean("desktopMode", v).apply(); changed() }

    /** HLS/DASH çıktısı: "mp4" (birleşik) | "separate" (ayrı dosyalar). */
    var outputMode: String
        get() = p.getString("outputMode", "mp4") ?: "mp4"
        set(v) { p.edit().putString("outputMode", v).apply(); changed() }

    var skipAds: Boolean
        get() = p.getBoolean("skipAds", true)
        set(v) { p.edit().putBoolean("skipAds", v).apply(); changed() }

    var audioLangs: String
        get() = p.getString("audioLangs", "tr,en") ?: "tr,en"
        set(v) { p.edit().putString("audioLangs", v).apply(); changed() }

    var subLangs: String
        get() = p.getString("subLangs", "tr,en") ?: "tr,en"
        set(v) { p.edit().putString("subLangs", v).apply(); changed() }

    /** Kullanıcının seçtiği klasör (SAF ağaç URI). Boşsa İndirilenler/MovieTrack. */
    var folderUri: String?
        get() = p.getString("folderUri", null)
        set(v) { p.edit().putString("folderUri", v).apply(); changed() }

    var homePage: String
        get() = p.getString("homePage", "https://www.google.com") ?: "https://www.google.com"
        set(v) { p.edit().putString("homePage", v).apply(); changed() }

    var searchEngine: String
        get() = p.getString("searchEngine", "https://www.google.com/search?q=") ?: "https://www.google.com/search?q="
        set(v) { p.edit().putString("searchEngine", v).apply(); changed() }

    var lastClipboard: String
        get() = p.getString("lastClipboard", "") ?: ""
        set(v) { p.edit().putString("lastClipboard", v).apply() }

    /** Oynatıcıda çift dokunma ve ileri/geri düğmelerinin sarma adımı (saniye) */
    var seekStepSec: Int
        get() = p.getInt("seekStepSec", 10)
        set(v) { p.edit().putInt("seekStepSec", v).apply(); changed() }

    /** Oynatıcıda ekranın sol / sağ yarısında dikey kaydırmanın işi: "brightness", "volume" ya da "none" */
    var gestureLeft: String
        get() = p.getString("gestureLeft", "brightness") ?: "brightness"
        set(v) { p.edit().putString("gestureLeft", v).apply(); changed() }
    var gestureRight: String
        get() = p.getString("gestureRight", "volume") ?: "volume"
        set(v) { p.edit().putString("gestureRight", v).apply(); changed() }

    /** Oynatıcıda sonradan eklenen harici ses / altyazı dosyaları (videoya özel, satır başına bir adres) */
    fun extAudio(key: String): List<String> = (p.getString("extAud:$key", "") ?: "").lines().filter { it.isNotBlank() }
    fun saveExtAudio(key: String, uris: List<String>) { p.edit().putString("extAud:$key", uris.joinToString("\n")).apply() }
    fun extSubs(key: String): List<String> = (p.getString("extSub:$key", "") ?: "").lines().filter { it.isNotBlank() }
    fun saveExtSubs(key: String, uris: List<String>) { p.edit().putString("extSub:$key", uris.joinToString("\n")).apply() }

    /** Videoya özel altyazı gecikmesi (ms; artı: altyazı daha geç çıkar) */
    fun subOffset(key: String): Long = p.getLong("subOff:$key", 0)
    fun saveSubOffset(key: String, ms: Long) { p.edit().putLong("subOff:$key", ms).apply() }

    fun resumePosition(key: String): Long = p.getLong("pos:$key", 0)
    fun saveResumePosition(key: String, ms: Long) { p.edit().putLong("pos:$key", ms).apply() }
}
