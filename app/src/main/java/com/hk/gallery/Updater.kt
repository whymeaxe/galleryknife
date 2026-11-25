package com.hk.gallery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Checks the public GalleryKnife releases for a newer version and installs it after the person confirms.
 * The check only asks GitHub for the latest version number and notes; nothing about the phone is sent.
 */
object Updater {
    const val REPO = "whymeaxe/galleryknife"

    class Info(val version: String, val notes: String, val url: String, val sha256: String?)

    fun currentVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

    /** True when [remote] is a higher dotted version than [local], for example 1.2.0 > 1.1.9. */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.trimStart('v', 'V').split('.', '-').mapNotNull { it.toIntOrNull() }
        val l = local.trimStart('v', 'V').split('.', '-').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }; val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun get(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "GalleryKnife-updater")
        if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()

    /** Reads the small latest.json kept in the repo (no rate limit). Falls back to the GitHub releases API. */
    suspend fun latest(): Info? = withContext(Dispatchers.IO) {
        runCatching {
            get("https://raw.githubusercontent.com/$REPO/main/latest.json")?.let { txt ->
                val j = JSONObject(txt)
                val url = j.optString("url")
                if (url.isNotBlank()) return@runCatching Info(j.getString("version"), j.optString("notes"), url, j.optString("sha256").takeIf { it.length == 64 })
            }
            val j = JSONObject(get("https://api.github.com/repos/$REPO/releases/latest") ?: return@runCatching null)
            val assets = j.getJSONArray("assets")
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk", true)) { url = a.getString("browser_download_url"); break }
            }
            url ?: return@runCatching null
            val body = j.optString("body")
            val sha = Regex("SHA-?256[:\\s]+([0-9a-fA-F]{64})").find(body)?.groupValues?.get(1)
            Info(j.getString("tag_name").trimStart('v', 'V'), body.lines().filterNot { it.trim().startsWith("SHA", true) }.joinToString("\n").trim(), url, sha)
        }.getOrNull()
    }

    /** Downloads the APK into the app's cache and checks its fingerprint against the release notes. */
    suspend fun download(ctx: Context, info: Info, onProgress: (Float) -> Unit): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(ctx.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
            val out = File(dir, "GalleryKnife-${info.version}.apk")
            val c = URL(info.url).openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 20000
            val total = c.contentLengthLong
            val md = MessageDigest.getInstance("SHA-256")
            c.inputStream.use { i ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024); var done = 0L
                    while (true) {
                        val n = i.read(buf); if (n < 0) break
                        o.write(buf, 0, n); md.update(buf, 0, n); done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (info.sha256 != null && !hex.equals(info.sha256, ignoreCase = true)) { out.delete(); return@runCatching null }
            out
        }.getOrNull()
    }

    /** Opens Android's installer. The first time, the person must allow GalleryKnife to install apps. */
    fun install(ctx: Context, apk: File) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(fileUri(ctx, apk), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}
