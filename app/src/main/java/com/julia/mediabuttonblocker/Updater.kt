package com.julia.mediabuttonblocker

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Downloads APK updates produced by [UpdateChecker] and hands them to the
 * system installer. Wraps Android's [DownloadManager] so the user gets a
 * normal progress notification in the status bar while the file downloads.
 */
object Updater {

    private const val TAG = "Updater"

    /**
     * Enqueues a download for [info]'s APK and returns the [DownloadManager]
     * download ID. Caller is expected to listen for
     * [DownloadManager.ACTION_DOWNLOAD_COMPLETE] and pass the same ID back to
     * [installDownloadedApk] when the broadcast fires.
     *
     * The destination is the app's external-files Download directory
     * (`/sdcard/Android/data/<package>/files/Download`). Using app-private
     * external storage means we don't need WRITE_EXTERNAL_STORAGE on any
     * supported Android version.
     */
    fun startDownload(context: Context, info: UpdateInfo): Long {
        // Wipe any stale APKs from previous update attempts so DownloadManager
        // doesn't append "-1", "-2", ... suffixes that would confuse the
        // FileProvider lookup later.
        downloadDir(context).listFiles()?.forEach { it.delete() }

        val request = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle(context.getString(R.string.update_download_title))
            .setDescription(context.getString(R.string.update_download_subtitle, info.version))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setMimeType("application/vnd.android.package-archive")
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, info.apkFileName)

        val dm = context.getSystemService(DownloadManager::class.java)
        return dm.enqueue(request)
    }

    /**
     * Returns the file [DownloadManager] wrote when [startDownload] completed,
     * or `null` if the download row no longer exists or never finished.
     */
    fun downloadedApkFile(context: Context, downloadId: Long): File? {
        val dm = context.getSystemService(DownloadManager::class.java)
        val query = DownloadManager.Query().setFilterById(downloadId)
        dm.query(query).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) return null
            val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val uriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            if (statusIdx == -1 || uriIdx == -1) return null
            if (cursor.getInt(statusIdx) != DownloadManager.STATUS_SUCCESSFUL) return null
            val localUri = cursor.getString(uriIdx) ?: return null
            val parsed = Uri.parse(localUri)
            return parsed.path?.let(::File)
        }
    }

    /**
     * Launches the system package installer for the downloaded APK. The user
     * still has to confirm in the system "Install update?" dialog — Android
     * does not allow side-loaded apps to install other apps silently.
     */
    fun installDownloadedApk(context: Context, apk: File) {
        if (!apk.exists()) {
            Log.w(TAG, "APK file does not exist: ${apk.absolutePath}")
            return
        }
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun downloadDir(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "Download")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
