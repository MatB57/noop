package com.noop.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app APK update for the sideloaded Android build.
 *
 * NOOP ships as a sideloaded APK (no Play Store), so there is no automatic update path. This does the
 * minimum to make one non-technical-user-proof: download the release APK into the app's cache and
 * hand it to the system package installer, so the user taps "Update" once instead of hunting a
 * GitHub asset in a browser. Nothing is sent — the APK comes straight from the public GitHub
 * release. The launch-time check + prompt lives in [com.noop.ui.UpdateGate]; this is just plumbing.
 *
 * No iOS/macOS counterpart: on iOS the AltStore/SideStore source already delivers one-tap updates,
 * and macOS is a drag-to-Applications zip.
 */
object InAppUpdate {

    /** True once the user has allowed NOOP to install packages (the API 26+ per-app gate). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** Send the user to the per-app "install unknown apps" screen. They grant once, then retry. */
    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** App-private dir the downloaded APKs live in (covered by the FileProvider `updates/` path). */
    private fun updatesDir(context: Context): File =
        File(context.cacheDir, "updates").apply { mkdirs() }

    /**
     * Download [apkUrl] to `cache/updates/NOOP-v<version>.apk`, reporting 0..1 progress. Any stale
     * APKs in the dir are cleared first. Runs on IO; returns the file or throws (the caller catches
     * and falls back to the release page).
     */
    suspend fun download(
        context: Context,
        version: String,
        apkUrl: String,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = updatesDir(context)
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "NOOP-v$version.apk")

        var conn = URL(apkUrl).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        // GitHub asset URLs 302 to a signed CDN URL; HttpURLConnection won't cross http<->https on
        // its own, so follow one hop by hand if the first response is a redirect.
        if (conn.responseCode in 300..399) {
            val loc = conn.getHeaderField("Location")
            conn.disconnect()
            conn = (URL(loc).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 60_000
            }
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 }
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total != null) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        onProgress(1f)
        out
    }

    /** Hand [apk] to the system package installer (the normal "Update NOOP?" screen). */
    fun install(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    /** Open the release page in a browser — the fallback when there's no APK asset or install fails. */
    fun openReleasePage(context: Context, url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
