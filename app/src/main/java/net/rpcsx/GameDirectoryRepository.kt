package net.rpcsx

import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import net.rpcsx.utils.GeneralSettings
import org.json.JSONArray

data class GameDirectory(val uri: String)

// Persists the set of user-added game library folders (SAF tree URIs) so they
// can be listed, re-scanned and removed from the "Manage directories" screen.
// The actual persistable read permission is taken/released by the caller
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
                directories.add(GameDirectory(arr.getString(i)))
            }
        } catch (_: Exception) {
        }
    }

    private fun persist() {
        val arr = JSONArray()
        directories.forEach { arr.put(it.uri) }
        GeneralSettings[KEY] = arr.toString()
    }

    fun add(uri: Uri) {
        val value = uri.toString()
        if (directories.none { it.uri == value }) {
            directories.add(GameDirectory(value))
            persist()
        }
    }

    fun remove(dir: GameDirectory) {
        if (directories.remove(dir)) {
            persist()
        }
    }
}
