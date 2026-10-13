package com.example.llamanode.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.llamanode.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class AppReleaseInfo(
    val tagName: String,
    val releaseName: String,
    val releaseNotes: String,
    val apkDownloadUrl: String?,
    val apkFileName: String?,
    val isNewer: Boolean
)

object UpdateManager {

    val currentVersion: String
        get() = BuildConfig.VERSION_NAME.ifEmpty { "0.2.0" }

    /**
     * Checks the latest release from the llama-cpp-android-node repository.
     */
    suspend fun checkLatestRelease(): Result<AppReleaseInfo> = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/adil-adysh/llama-cpp-android-node/releases/latest")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "LlamaNode-Android")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                connectTimeout = 8000
                readTimeout = 8000
            }

            if (conn.responseCode == 200) {
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                val releaseInfo = parseReleaseJson(json, currentVersion)
                Result.success(releaseInfo)
            } else {
                Result.failure(Exception("GitHub API returned status ${conn.responseCode}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun parseReleaseJson(jsonStr: String, currentVersionStr: String): AppReleaseInfo {
        val obj = JSONObject(jsonStr)
        val tagName = obj.optString("tag_name", "unknown")
        val releaseName = obj.optString("name", tagName)
        val body = obj.optString("body", "")

        var apkDownloadUrl: String? = null
        var apkFileName: String? = null

        val assets = obj.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.endsWith(".apk")) {
                    apkFileName = name
                    apkDownloadUrl = if (asset.has("browser_download_url")) asset.getString("browser_download_url") else null
                    break
                }
            }
        }

        val isNewer = isTagNewerThan(tagName, currentVersionStr)

        return AppReleaseInfo(
            tagName = tagName,
            releaseName = releaseName,
            releaseNotes = body,
            apkDownloadUrl = apkDownloadUrl,
            apkFileName = apkFileName,
            isNewer = isNewer
        )
    }

    fun isTagNewerThan(remoteTag: String, localVersion: String): Boolean {
        if (remoteTag.isBlank() || remoteTag == "unknown") return false
        val cleanRemote = remoteTag.trim().removePrefix("v").substringBefore("-")
        val cleanLocal = localVersion.trim().removePrefix("v").substringBefore("-")

        val remoteParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }
        val localParts = cleanLocal.split(".").mapNotNull { it.toIntOrNull() }

        if (remoteParts.isEmpty() || localParts.isEmpty()) {
            return false
        }

        val maxLen = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }

        // Check run numbers if versions match (e.g. v0.2.0-b11429.r2 vs v0.2.0-b11429)
        val remoteSuffix = remoteTag.substringAfter("-", "")
        val localSuffix = localVersion.substringAfter("-", "")
        if (remoteSuffix.contains(".r") && localSuffix.contains(".r")) {
            val rRun = remoteSuffix.substringAfter(".r").takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            val lRun = localSuffix.substringAfter(".r").takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            return rRun > lRun
        } else if (remoteSuffix.contains(".r") && !localSuffix.contains(".r")) {
            return true
        }

        return false
    }

    /**
     * Opens the browser or download URL for user to download and install update.
     */
    fun openDownloadUrl(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Installs an APK file using the system PackageInstaller via FileProvider.
     */
    fun installApk(context: Context, apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
