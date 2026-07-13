package net.rpcsx.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.rpcsx.R
import net.rpcsx.RPCSX
import net.rpcsx.dialogs.AlertDialogQueue
import net.rpcsx.ui.channels.DevRpcsxChannel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import kotlin.system.exitProcess

object RpcsxUpdater {
    @Serializable
    data class DownloadedVersion(
        val version: String,
        val arch: String,
        val fileName: String,
        val filePath: String
    )

    /** Any "<name>_<arch>_<version>.so" in the data dir, whatever the library is called. */
    private fun isCoreFile(file: File) = file.name.endsWith(".so") && getFileVersion(file) != null

    fun getDownloadedVersions(context: Context): List<DownloadedVersion> {
        val files = context.filesDir.listFiles() ?: return emptyList()
        return files.filter { isCoreFile(it) }
            .mapNotNull { file ->
                val version = getFileVersion(file) ?: return@mapNotNull null
                val arch = getFileArch(file) ?: return@mapNotNull null
                DownloadedVersion(
                    version = version,
                    arch = arch,
                    fileName = file.name,
                    filePath = file.absolutePath
                )
            }
    }

    fun syncDownloadedVersionsJson(context: Context) {
        val versions = getDownloadedVersions(context)
        val jsonString = Json.encodeToString(versions)
        try {
            val file = File(context.filesDir, "downloaded_versions.json")
            file.writeText(jsonString)
        } catch (e: Exception) {
            Log.e("RpcsxUpdater", "Failed to write downloaded_versions.json", e)
        }
    }

    fun deleteVersion(context: Context, version: DownloadedVersion) {
        val file = File(version.filePath)
        if (file.exists()) {
            file.delete()
        }
        val currentLib = GeneralSettings["rpcsx_library"] as? String
        if (currentLib == version.filePath) {
            GeneralSettings["rpcsx_library"] = null
            GeneralSettings["rpcsx_installed_arch"] = null
            RPCSX.activeLibrary.value = null
        }
        syncDownloadedVersionsJson(context)
    }

    fun wipeDownloads(context: Context) {
        val files = context.filesDir.listFiles() ?: return
        files.forEach { file ->
            if (isCoreFile(file)) {
                file.delete()
            }
        }
        GeneralSettings["rpcsx_library"] = null
        GeneralSettings["rpcsx_installed_arch"] = null
        RPCSX.activeLibrary.value = null
        syncDownloadedVersionsJson(context)
    }

    fun getCurrentVersion(): String? {
        if (RPCSX.activeLibrary.value == null) {
            return null
        }

        return "v" + RPCSX.instance.getVersion().trim().removeSuffix(" Draft").trim() + "-" + GeneralSettings["rpcsx_installed_arch"]
    }

    fun getFileArch(file: File): String? {
        val parts = file.name.removeSuffix(".so").split("_")
        if (parts.size != 3) {
            return null
        }

        return parts[1]
    }

    fun getFileVersion(file: File): String? {
        val parts = file.name.removeSuffix(".so").split("_")
        if (parts.size != 3) {
            return null
        }
        val arch = parts[1]
        val version = parts[2]
        return "$version-$arch"
    }

    fun getAbi(): String = Build.SUPPORTED_64_BIT_ABIS[0]

    fun getArch(): String {
        return when (getAbi()) {
            "x86_64" -> "x86-64"
            else -> GeneralSettings["rpcsx_arch"] as? String ?: "armv8-a"
        }
    }

    fun setArch(arch: String) {
        GeneralSettings["rpcsx_arch"] = arch
    }

    /**
     * Core built for this device inside [release]. Only the library name varies
     * between build repos (librpcsx-android-..., librpcs3-android-...), so that
     * is the sole wildcard: ABI and architecture still have to match exactly.
     */
    private fun findAsset(release: GitHub.Release): GitHub.Asset? {
        val suffix = "-android-${getAbi()}-${getArch()}.so"
        return release.assets.find {
            it.browser_download_url != null && it.name.startsWith("lib") && it.name.endsWith(suffix)
        }
    }

    private suspend fun fetchRelease(onError: (String) -> Unit): GitHub.Release? {
        val url = GeneralSettings["rpcsx_channel"] as? String ?: DevRpcsxChannel

        return when (val fetchResult = GitHub.fetchLatestRelease(url)) {
            is GitHub.FetchResult.Success<*> -> fetchResult.content as GitHub.Release
            is GitHub.FetchResult.Error -> {
                onError(fetchResult.message)
                null
            }
        }
    }

    suspend fun checkForUpdate(): String? {
        // Update checks run unattended, so an unreachable channel stays silent.
        val release = fetchRelease { } ?: return null
        if (findAsset(release) == null) {
            return null
        }

        val releaseVersion = "${release.name}-${getArch()}"

        if (RPCSX.activeLibrary.value == null) {
            return releaseVersion
        }

        if (getCurrentVersion() == releaseVersion || releaseVersion == GeneralSettings["rpcsx_bad_version"]) {
            return null
        }

        return releaseVersion
    }

    suspend fun downloadUpdate(destinationDir: File, progressCallback: (Long, Long) -> Unit): File? {
        val release = fetchRelease {
            AlertDialogQueue.showDialog("RPCSX Download Error", it)
        } ?: return null

        val arch = getArch()
        val releaseVersion = "${release.name}-$arch"
        if (releaseVersion == getCurrentVersion()) {
            return null
        }

        val downloadUrl = findAsset(release)?.browser_download_url ?: return null
        val target = File(destinationDir, "librpcsx-android_${arch}_${release.name}.so")

        if (target.exists()) {
            return target
        }

        val tmp = File(destinationDir, "librpcsx.so.tmp")
        withContext(Dispatchers.IO) {
            if (tmp.exists()) {
                tmp.delete()
            }
            tmp.createNewFile()
        }

        tmp.deleteOnExit()

        return when (val downloadStatus = GitHub.downloadAsset(downloadUrl, tmp, progressCallback)) {
            is GitHub.DownloadStatus.Success -> {
                withContext(Dispatchers.IO) {
                    tmp.renameTo(target)
                }
                target
            }

            is GitHub.DownloadStatus.Error -> {
                AlertDialogQueue.showDialog("RPCSX Download Error", downloadStatus.message ?: "Unexpected error")
                null
            }
        }
    }

    fun installUpdate(context: Context, updateFile: File): Boolean {
        val restart = {
            val packageManager = context.packageManager
            val intent = packageManager.getLaunchIntentForPackage(context.packageName)
            val mainIntent = Intent.makeRestartActivityTask(intent!!.component)
            mainIntent.setPackage(context.packageName)
            context.startActivity(mainIntent)
            GeneralSettings.sync()
            exitProcess(0)
        }

        val prevLibrary = GeneralSettings["rpcsx_library"] as? String
        val prevArch = GeneralSettings["rpcsx_installed_arch"] as? String
        GeneralSettings["rpcsx_library"] = updateFile.toString()
        GeneralSettings["rpcsx_update_status"] = null
        GeneralSettings["rpcsx_installed_arch"] = getFileArch(updateFile)

        Log.e("RPCSX-UI", "registered update file ${GeneralSettings["rpcsx_library"]}")
        syncDownloadedVersionsJson(context)

        if (prevLibrary == null) {
            restart()
        }

        GeneralSettings["rpcsx_prev_library"] = prevLibrary
        GeneralSettings["rpcsx_prev_installed_arch"] = prevArch
        AlertDialogQueue.showDialog(
            title = context.getString(R.string.rpcsx_update_available),
            message = context.getString(R.string.restart_ui_to_apply_change),
            onConfirm = { restart() }
        )
        return true
    }
}
