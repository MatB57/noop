package com.noop.update

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.noop.data.DataBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Best-effort safety-net backup fired right before an in-app update installs.
 *
 * A signature mismatch (or any other install hiccup) can force Android to uninstall-then-reinstall
 * instead of updating in place, which wipes the app's private database. Writing one fresh snapshot
 * to the public Downloads folder — which SURVIVES an uninstall, unlike app-private storage — right
 * before the update starts means there's always something recent to restore from Backup & Sync
 * afterwards, even for someone who never set up a backup folder.
 *
 * Never blocks or fails the update: every error is swallowed, and [InAppUpdate.download] runs
 * regardless of the outcome here. Requires API 29+ (a `MediaStore.Downloads` insert needs no
 * permission there); older devices are skipped rather than requesting a permission the app
 * otherwise doesn't need.
 */
object AutoBackup {

    suspend fun beforeUpdate(context: Context, version: String) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext
        runCatching {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "NOOP-preupdate-v$version-$stamp.noopbak")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.applicationContext.contentResolver
            val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching
            DataBackup.exportTo(context, uri)
        }
        Unit
    }
}
