package net.rpcsx

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.documentfile.provider.DocumentFile
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
            // A bare thread lets any exception reach the default handler and
            // kill the process. This runs on every launch for each persisted
            // directory, so an unhandled throw here is a boot crash loop.
            try {
                val ok = RPCSX.instance.collectGameInfoFromUri(uri.toString(), progress)
                if (!ok) {
                    ProgressRepository.onProgressEvent(
                        progress, -1, 0, context.getString(R.string.directory_not_resolvable)
                    )
                } else {
                    ProgressRepository.onProgressEvent(progress, 1, 1)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                ProgressRepository.onProgressEvent(
                    progress, -1, 0, context.getString(R.string.directory_not_resolvable)
                )
            }
        }
    }

    // "Add ISO directory" flow: enumerate the tree through SAF (works for any
    // DocumentsProvider, no storage permission needed - the picker's
    // persisted grant is enough) and register each .iso through the normal
    // fd-based install path.
    fun scanIsoDirectory(context: Context, uri: Uri) {
        thread {
            val progress = ProgressRepository.create(context, context.getString(R.string.installing_dir))

            val tree = DocumentFile.fromTreeUri(context, uri)
            if (tree == null || !tree.isDirectory) {
                ProgressRepository.onProgressEvent(
                    progress, -1, 0, context.getString(R.string.directory_not_resolvable)
                )
                return@thread
            }

            val isos = ArrayList<Uri>()

            fun walk(dir: DocumentFile) {
                dir.listFiles().forEach { entry ->
                    if (entry.isDirectory) {
                        walk(entry)
                    } else if (entry.name?.endsWith(".iso", ignoreCase = true) == true) {
                        isos.add(entry.uri)
                    }
                }
            }
            walk(tree)

            ProgressRepository.onProgressEvent(progress, 1, 1)

            if (isos.isNotEmpty()) {
                PrecompilerService.start(context, PrecompilerServiceAction.Install, isos)
            }
        }
    }

    // Removes a directory from the managed list, drops its games from the
    // list (not from disk) and releases the persisted SAF grant.
    fun removeAndForget(context: Context, dir: GameDirectory) {
        remove(dir)

        // fd-registered games carry the child document URI, which is prefixed
        // by the tree URI; legacy in-place entries carry the resolved path.
        GameRepository.removeByDirectory(dir.uri)
        val path = try {
            RPCSX.instance.resolveTreeUriToPath(dir.uri)
        } catch (_: Exception) {
            null
        }
        if (!path.isNullOrEmpty()) {
            GameRepository.removeByDirectory(path)
        }

        try {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(dir.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
    }

    // Drops registered directories whose tree no longer exists or whose
    // persisted permission was revoked, together with their games. No native
    // calls: safe to run before the core library is loaded.
    fun pruneInvalid(context: Context) {
        directories.filter { dir ->
            val doc = try {
                DocumentFile.fromTreeUri(context, Uri.parse(dir.uri))
            } catch (_: Exception) {
                null
            }
            doc?.exists() != true
        }.forEach { dir ->
            remove(dir)
            GameRepository.removeByDirectory(dir.uri)
            try {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(dir.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
        }
    }
}
