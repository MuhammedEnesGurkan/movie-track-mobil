package com.movietrack.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.movietrack.app.data.DownloadEntity
import com.movietrack.app.download.OutputFile
import com.movietrack.app.player.PlayerActivity
import com.movietrack.app.util.Category
import java.io.File

/** İndirilen dosyaları açma / paylaşma. */
object Open {
    fun contentUri(ctx: Context, encoded: String?): Uri? {
        val u = OutputFile.decode(encoded)?.uri ?: return null
        return if (u.scheme == "file") FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", File(u.path!!)) else u
    }

    fun open(ctx: Context, e: DownloadEntity) {
        val uri = contentUri(ctx, e.outputUri) ?: return
        if (e.category == Category.VIDEO || e.category == Category.MUSIC) {
            val subs = e.extraOutputs.lines().filter { it.contains("subtitle:") }.mapNotNull { line ->
                contentUri(ctx, line.substringBeforeLast('|'))?.toString()?.let { it to line.substringAfterLast('|').removePrefix("subtitle:") }
            }
            val i = Intent(ctx, PlayerActivity::class.java)
                .setData(uri)
                .putExtra("title", e.fileName)
                .putExtra("subs", ArrayList(subs.map { it.first }))
                .putExtra("subLangs", ArrayList(subs.map { it.second }))
            // Ayrı indirilen ses dosyaları (aud:xx) oynatıcıda videoya eklenir
            val auds = e.extraOutputs.lines().filter { it.contains("|aud:") }.mapNotNull { line ->
                contentUri(ctx, line.substringBeforeLast('|'))?.toString()?.let { it to line.substringAfterLast('|').removePrefix("aud:") }
            }
            i.putExtra("audios", ArrayList(auds.map { it.first }))
                .putExtra("audioLangs", ArrayList(auds.map { it.second }))
            ctx.startActivity(i)
            return
        }
        try {
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, e.mime.ifEmpty { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Aç"))
        } catch (_: Exception) {
            Toast.makeText(ctx, "Bu dosyayı açacak uygulama yok", Toast.LENGTH_SHORT).show()
        }
    }

    fun share(ctx: Context, e: DownloadEntity) {
        val uri = contentUri(ctx, e.outputUri) ?: return
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(e.mime.ifEmpty { "*/*" }).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Paylaş"))
    }

    fun shareLink(ctx: Context, url: String) {
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), "Bağlantıyı paylaş"))
    }
}
