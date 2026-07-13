package net.rpcsx.utils

import android.net.Uri
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.ActivityNotFoundException
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rpcsx.provider.AppDataDocumentProvider
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException

object FileUtil {
    fun saveGameFolderUri(prefs: SharedPreferences, uri: Uri) {
        prefs.edit { putString("selected_game_folder", uri.toString()) }
    }

    // Single-file copy used for config import/export and custom driver
    // installation - unrelated to game/ISO directory registration, which is
    // handled natively in place (RPCSX.collectGameInfoFromUri /
    // collectIsoInfoFromUri) without ever copying game data.
    fun saveFile(context: Context, source: Uri, target: String) {
        var bis: BufferedInputStream? = null
        var bos: BufferedOutputStream? = null

        try {
            bis = BufferedInputStream(
                FileInputStream(
                    context.contentResolver.openFileDescriptor(
                        source, "r"
                    )!!.fileDescriptor
                )
            )

            bos = BufferedOutputStream(FileOutputStream(target, false))
            val buf = ByteArray(64 * 1024)
            var n = bis.read(buf)
            while (n != -1) {
                bos.write(buf, 0, n)
                n = bis.read(buf)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            bis?.close()
            bos?.close()
        }
    }

    fun deleteCache(ctx: Context, gameId: String, onComplete: (Boolean) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val result = File(ctx.getExternalFilesDir(null)!!, "cache/cache/$gameId").deleteRecursively()
            withContext(Dispatchers.Main) {
                onComplete(result)
            }
        }
    }

    fun importConfig(ctx: Context, uri: Uri): Boolean {
        return try {
            val docFile = DocumentFile.fromSingleUri(ctx, uri)
            if (docFile == null || (docFile.name?.endsWith(".yml", true) != true)) return false
            val inputStream: InputStream = ctx.contentResolver.openInputStream(uri) ?: return false
            val outputFile: File = ctx.getExternalFilesDir(null)?.resolve("config")?.resolve("config.yml") ?: return false
            val outputStream: OutputStream = outputFile.outputStream()
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun exportConfig(ctx: Context, uri: Uri): Boolean {
        return try {
            val inputFile = ctx.getExternalFilesDir(null)?.resolve("config")?.resolve("config.yml") ?: return false
            val inputStream: InputStream = inputFile.inputStream()
            val outputStream: OutputStream = ctx.contentResolver.openOutputStream(uri) ?: return false
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
           true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun launchInternalDir(ctx: Context): Boolean {
        if (!ctx.launchBrowseIntent(Intent.ACTION_VIEW)) {
            if (!ctx.launchBrowseIntent()) {
                if (!ctx.launchBrowseIntent(Intent.ACTION_OPEN_DOCUMENT_TREE)) {
                    return false
                }
            }
        }
        return true
    }

    private fun Context.launchBrowseIntent(
        action: String = "android.provider.action.BROWSE"
    ): Boolean {
        return try {
            val intent = Intent(action).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                data = DocumentsContract.buildRootUri(
                    AppDataDocumentProvider.AUTHORITY, AppDataDocumentProvider.ROOT_ID
                )
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            println("No activity found to handle $action intent")
            false
        }
    }
}
