package com.julia.mediabuttonblocker

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.julia.mediabuttonblocker.ui.theme.MediaButtonBlockerTheme

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Whether or not the user grants POST_NOTIFICATIONS we still start the
            // service — Android just won't show the FGS notification on Android 13+
            // until the permission is granted, but the FGS itself still runs.
            startBlocker()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MediaButtonBlockerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    BlockerScreen(
                        initialEnabled = BlockerPrefs.isEnabled(this),
                        onToggle = ::onToggle,
                    )
                }
            }
        }
    }

    private fun onToggle(enabled: Boolean) {
        BlockerPrefs.setEnabled(this, enabled)
        if (enabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startBlocker()
            }
        } else {
            BlockerService.stop(this)
        }
    }

    private fun startBlocker() {
        BlockerService.start(this)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockerScreen(
    initialEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    var enabled by remember { mutableStateOf(initialEnabled) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var pendingDownloadId by remember { mutableLongStateOf(-1L) }
    val context = LocalContext.current

    // Once per Activity creation, hit the GitHub releases API and surface the
    // banner if a newer release exists. Network failures silently leave
    // updateInfo == null so the banner stays hidden.
    LaunchedEffect(Unit) {
        updateInfo = UpdateChecker.fetchLatestRelease(BuildConfig.VERSION_NAME)
    }

    // Listen for DownloadManager completion so we can hand the APK to the
    // system installer immediately. Registered as RECEIVER_EXPORTED because
    // the broadcast originates from the system DownloadManager process on
    // Android 13+.
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val finishedId = intent.getLongExtra(
                    DownloadManager.EXTRA_DOWNLOAD_ID,
                    -1L,
                )
                if (finishedId == -1L || finishedId != pendingDownloadId) return
                val apk = Updater.downloadedApkFile(ctx, finishedId)
                pendingDownloadId = -1L
                if (apk != null) Updater.installDownloadedApk(ctx, apk)
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            updateInfo?.let { info ->
                UpdateBanner(
                    info = info,
                    isDownloading = pendingDownloadId != -1L,
                    onUpdateClick = {
                        pendingDownloadId = Updater.startDownload(context, info)
                    },
                )
            }
            ToggleCard(enabled = enabled, onCheckedChange = {
                enabled = it
                onToggle(it)
            })
            StatusCard(enabled = enabled)
        }
    }
}

@Composable
private fun ToggleCard(enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Block,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.padding(horizontal = 8.dp))
                Column {
                    Text(
                        text = context.getString(R.string.toggle_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = context.getString(R.string.toggle_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = enabled, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun StatusCard(enabled: Boolean) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Text(
                text = if (enabled) {
                    context.getString(R.string.status_enabled)
                } else {
                    context.getString(R.string.status_disabled)
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun UpdateBanner(
    info: UpdateInfo,
    isDownloading: Boolean,
    onUpdateClick: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.padding(horizontal = 8.dp))
                Text(
                    text = context.getString(R.string.update_banner_title, info.version),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Button(onClick = onUpdateClick, enabled = !isDownloading) {
                Text(
                    text = if (isDownloading) {
                        context.getString(R.string.update_button_downloading)
                    } else {
                        context.getString(R.string.update_button)
                    },
                )
            }
        }
    }
}
