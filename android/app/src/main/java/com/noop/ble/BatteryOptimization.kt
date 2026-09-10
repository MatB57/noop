package com.noop.ble

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Doze / battery-optimisation exemption.
 *
 * An app that is NOT whitelisted from battery optimisation gets its foreground service frozen
 * during Doze, so NOOP's strap link drops overnight, no R-R/HRV is banked, and the recovery
 * baseline never leaves "calibrating" — `AndroidDiagnostics` already calls this out as the #1 cause
 * of missed overnight background work. Google Play explicitly permits
 * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for an app maintaining a continuous connection to a
 * companion device, which is exactly what NOOP does with the strap.
 *
 * Nothing here runs on its own: the UI checks [isExempt] and, only if the user taps, launches
 * [requestIntent] (falling back to [settingsIntent] on OEMs that disable the direct prompt).
 * There is no macOS/iOS counterpart — CoreBluetooth background delivery needs no user-granted
 * power exemption.
 */
object BatteryOptimization {

    /** True when NOOP is already exempt from battery optimisation (background work allowed). */
    fun isExempt(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)

    /**
     * The per-app system dialog asking the user to let NOOP ignore battery optimisation
     * (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). Some OEMs disable this action, so callers
     * should catch `ActivityNotFoundException` and fall back to [settingsIntent].
     */
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))

    /** The full battery-optimisation list — the fallback when the direct prompt is unavailable. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}
