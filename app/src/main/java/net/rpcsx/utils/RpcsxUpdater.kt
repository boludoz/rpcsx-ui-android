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

    fun getDownloadedVersions(context: Context): List<DownloadedVersion> {
        val filesDir = context.filesDir
        val files = filesDir.listFiles() ?: return emptyList()
        return files.filter { it.name.startsWith("librpcsx-android_") && it.name.endsWith(".so") }
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
        val filesDir = context.filesDir
        val files = filesDir.listFiles() ?: return
        files.forEach { file ->
            if (file.name.startsWith("librpcsx-android_") && file.name.endsWith(".so")) {
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

    suspend fun checkForUpdate(): String? {
        val url = GeneralSettings["rpcsx_channel"] as? String ?: DevRpcsxChannel

        val arch = getArch()
        when (val fetchResult = GitHub.fetchLatestRelease(url)) {
            is GitHub.FetchResult.Success<*> -> {
                val release = fetchResult.content as GitHub.Release
                val releaseVersion = "${release.name}-${arch}"

                if (release.assets.find { it.name == "librpcsx-android-${getAbi()}-${arch}.so" }?.browser_download_url == null) {
                    return null
                }

                if (RPCSX.activeLibrary.value == null) {
                    return releaseVersion
                }

                if (getCurrentVersion() != releaseVersion && releaseVersion != GeneralSettings["rpcsx_bad_version"]) {
                    return releaseVersion
                }
            }
            is GitHub.FetchResult.Error -> {
//                AlertDialogQueue.showDialog("Check For RPCSX Updates Error", fetchResult.message)
            }
        }

        return null
    }

    suspend fun downloadUpdate(destinationDir: File, progressCallback: (Long, Long) -> Unit): File? {
        val url = GeneralSettings["rpcsx_channel"] as? String ?: DevRpcsxChannel
        val arch = getArch()

        when (val fetchResult = GitHub.fetchLatestRelease(url)) {
            is GitHub.FetchResult.Success<*> -> {
                val release = fetchResult.content as GitHub.Release
                val releaseVersion = "${release.name}-${arch}"
                val releaseAsset = release.assets.find { it.name == "librpcsx-android-${getAbi()}-$arch.so" }

                if (releaseVersion != getCurrentVersion() && releaseAsset?.browser_download_url != null) {
                    val target = File(destinationDir, "librpcsx-android_${arch}_${release.name}.so")

                    if (target.exists()) {
                        return target
                    }

                    val tmp = File(destinationDir, "librpcsx.so.tmp")
                    if (tmp.exists()) {
                        withContext(Dispatchers.IO) {
                            tmp.delete()
                        }
                    }

                    withContext(Dispatchers.IO) {
                        tmp.createNewFile()
                    }

                    tmp.deleteOnExit()

                    when (val downloadStatus = GitHub.downloadAsset(releaseAsset.browser_download_url, tmp, progressCallback)) {
                        is GitHub.DownloadStatus.Success -> {
                            withContext(Dispatchers.IO) {
                                tmp.renameTo(target)
                            }
                            return target
                        }
                        is GitHub.DownloadStatus.Error ->
                            AlertDialogQueue.showDialog("RPCSX Download Error", downloadStatus.message ?: "Unexpected error")
                    }
                }
            }
            is GitHub.FetchResult.Error -> {
                AlertDialogQueue.showDialog("RPCSX Download Error", fetchResult.message)
            }
        }

        return null
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
