package net.rpcsx

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import net.rpcsx.utils.GeneralSettings
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

enum class GameDirectoryKind {
    Games, Iso
}

data class GameDirectory(val uri: String, val kind: GameDirectoryKind = GameDirectoryKind.Games)

// Persists the set of user-added library folders (SAF tree URIs) so they can
// be listed, re-scanned and removed from the "Manage directories" screen. The
// actual persistable read permission is taken/released by the caller
// (AppNavHost / GameDirectoriesScreen) via ContentResolver.
object GameDirectoryRepository {
    private const val KEY = "game_directories"

    val directories = mutableStateListOf<GameDirectory>()

    fun load() {
        directories.clear()
        val raw = GeneralSettings[KEY] as? String ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val element = arr.get(i)
                if (element is JSONObject) {
                    val kind = try {
                        GameDirectoryKind.valueOf(element.getString("kind"))
                    } catch (_: Exception) {
                        GameDirectoryKind.Games
                    }
                    directories.add(GameDirectory(element.getString("uri"), kind))
                } else {
                    // Legacy format: a bare URI string, always a game folder.
                    directories.add(GameDirectory(element.toString(), GameDirectoryKind.Games))
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun persist() {
        val arr = JSONArray()
        directories.forEach {
            arr.put(JSONObject().apply {
                put("uri", it.uri)
                put("kind", it.kind.name)
            })
        }
        GeneralSettings[KEY] = arr.toString()
    }

    fun add(uri: Uri, kind: GameDirectoryKind) {
        val value = uri.toString()
        if (directories.none { it.uri == value }) {
            directories.add(GameDirectory(value, kind))
            persist()
        }
    }

    fun remove(dir: GameDirectory) {
        if (directories.remove(dir)) {
            persist()
        }
    }

    // "Add game folder" flow: resolves the SAF tree URI to a real path
    // natively and registers the game(s) in place - no copy.
    // collectGameInfoFromUri already detects a single game at the root or
    // recurses into subdirectories looking for PARAM.SFO.
    fun scanGameDirectory(context: Context, uri: Uri) {
        if (!StorageAccess.isGranted()) {
            StorageAccess.requestAccess(context)
        }

        thread {
            val progress = ProgressRepository.create(context, context.getString(R.string.installing_dir))
            val ok = RPCSX.instance.collectGameInfoFromUri(uri.toString(), progress)
            if (!ok) {
                ProgressRepository.onProgressEvent(
                    progress, -1, 0, context.getString(R.string.directory_not_resolvable)
                )
            } else {
                ProgressRepository.onProgressEvent(progress, 1, 1)
            }
        }
    }

    // "Add ISO directory" flow: same in-place contract as scanGameDirectory,
    // but scans for loose .iso files instead of PARAM.SFO-based game folders.
    fun scanIsoDirectory(context: Context, uri: Uri) {
        if (!StorageAccess.isGranted()) {
            StorageAccess.requestAccess(context)
        }

        thread {
            val progress = ProgressRepository.create(context, context.getString(R.string.installing_dir))
            val ok = RPCSX.instance.collectIsoInfoFromUri(uri.toString(), progress)
            if (!ok) {
                ProgressRepository.onProgressEvent(
                    progress, -1, 0, context.getString(R.string.directory_not_resolvable)
                )
            } else {
                ProgressRepository.onProgressEvent(progress, 1, 1)
            }
        }
    }

    // Removes a directory from the managed list and drops its games from the
    // list (not from disk) - the underlying resolved real path is asked from
    // native code so no SAF URI parsing lives in Kotlin.
    fun removeAndForget(dir: GameDirectory) {
        val path = try {
            RPCSX.instance.resolveTreeUriToPath(dir.uri)
        } catch (_: Exception) {
            null
        }

        remove(dir)

        if (!path.isNullOrEmpty()) {
            GameRepository.removeByDirectory(path)
        }
    }
}
