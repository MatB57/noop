package com.noop.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.noop.BuildConfig
import com.noop.update.InAppUpdate
import com.noop.update.UpdateCheck
import kotlinx.coroutines.launch

/** Don't hit the releases API more than once per this window across launches. */
private const val UPDATE_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000  // 6 h

private sealed interface UpdateGateState {
    data object Hidden : UpdateGateState
    data class Prompt(val info: UpdateCheck.Result.Available) : UpdateGateState
    data class Downloading(val info: UpdateCheck.Result.Available, val pct: Float) : UpdateGateState
}

/**
 * Launch-time update gate for the sideloaded Android build.
 *
 * On a throttled schedule it asks the public GitHub release API for the latest version; if it's
 * newer than the running build AND the user hasn't tapped "Later" on that exact version, it pops a
 * dialog offering a one-tap update — download the APK into cache, then hand it to the system
 * installer. Rendered once, over the live app, from [NoopRoot]. iOS gets equivalent one-tap updates
 * from its AltStore/SideStore source, so there is no counterpart there.
 */
@Composable
fun UpdateGate() {
    val context = LocalContext.current
    val prefs = remember { NoopPrefs.of(context) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateGateState>(UpdateGateState.Hidden) }

    LaunchedEffect(Unit) {
        val last = prefs.getLong(NoopPrefs.KEY_LAST_UPDATE_CHECK_MS, 0L)
        if (System.currentTimeMillis() - last < UPDATE_CHECK_INTERVAL_MS) return@LaunchedEffect
        val result = UpdateCheck.check(BuildConfig.VERSION_NAME)
        prefs.edit().putLong(NoopPrefs.KEY_LAST_UPDATE_CHECK_MS, System.currentTimeMillis()).apply()
        if (result is UpdateCheck.Result.Available && result.apkUrl != null) {
            val snoozed = prefs.getString(NoopPrefs.KEY_UPDATE_SNOOZED_VERSION, "") ?: ""
            if (result.version != snoozed) state = UpdateGateState.Prompt(result)
        }
    }

    when (val s = state) {
        UpdateGateState.Hidden -> Unit

        is UpdateGateState.Prompt -> AlertDialog(
            onDismissRequest = { state = UpdateGateState.Hidden },
            title = { Text("NOOP ${s.info.version} is available") },
            text = {
                val body = buildString {
                    if (s.info.notes.isNotBlank()) append(s.info.notes.take(280).trim())
                    if (s.info.apkSize > 0) {
                        if (isNotEmpty()) append("\n\n")
                        append("Download ${s.info.apkSize / (1024 * 1024)} MB")
                    }
                }
                Text(if (body.isBlank()) "A newer version is ready to install." else body)
            },
            confirmButton = {
                TextButton(onClick = {
                    val info = s.info
                    val apk = info.apkUrl ?: return@TextButton
                    if (!InAppUpdate.canInstall(context)) {
                        InAppUpdate.requestInstallPermission(context)
                        Toast.makeText(
                            context,
                            "Allow installing apps, then reopen NOOP to update.",
                            Toast.LENGTH_LONG,
                        ).show()
                        state = UpdateGateState.Hidden
                        return@TextButton
                    }
                    state = UpdateGateState.Downloading(info, 0f)
                    scope.launch {
                        runCatching {
                            InAppUpdate.download(context, info.version, apk) { p ->
                                state = UpdateGateState.Downloading(info, p)
                            }
                        }.onSuccess { file ->
                            state = UpdateGateState.Hidden
                            InAppUpdate.install(context, file)
                        }.onFailure {
                            state = UpdateGateState.Hidden
                            Toast.makeText(
                                context,
                                "Update download failed — opening the release page.",
                                Toast.LENGTH_LONG,
                            ).show()
                            InAppUpdate.openReleasePage(context, info.url)
                        }
                    }
                }) { Text("Update") }
            },
            dismissButton = {
                TextButton(onClick = {
                    prefs.edit()
                        .putString(NoopPrefs.KEY_UPDATE_SNOOZED_VERSION, s.info.version)
                        .apply()
                    state = UpdateGateState.Hidden
                }) { Text("Later") }
            },
        )

        is UpdateGateState.Downloading -> AlertDialog(
            onDismissRequest = { },
            title = { Text("Updating to ${s.info.version}") },
            text = {
                Column {
                    Text("Downloading… ${(s.pct * 100).toInt()}%")
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = s.pct,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {},
        )
    }
}
