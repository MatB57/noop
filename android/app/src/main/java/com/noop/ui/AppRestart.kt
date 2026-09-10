package com.noop.ui

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import kotlin.system.exitProcess

/**
 * Relaunch NOOP from scratch.
 *
 * A backup restore / data import swaps the on-disk SQLite file, but the running process still holds
 * open Room + GRDB connections, cached repositories, view-models and in-memory LiveState built from
 * the OLD database — which is why a restore used to show stale "calibrating" / "No Data" until the
 * user force-quit and reopened the app by hand. This does that for them: start a fresh task and
 * hard-exit the current process so every handle and cache is gone and the new DB is read clean.
 */
object AppRestart {

    /** Toast-then-relaunch after [delayMs] so the user sees the confirmation before the screen blinks. */
    fun relaunch(context: Context, delayMs: Long = 700L) {
        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = appLaunchIntent(appContext).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            // Hard-kill: a graceful finish would leave the old DB handles / analytics caches alive.
            exitProcess(0)
        }, delayMs)
    }
}
