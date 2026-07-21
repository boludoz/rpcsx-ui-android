package net.rpcsx

import android.content.Context
import android.content.res.Resources.NotFoundException
import android.net.Uri
import androidx.annotation.Keep
import androidx.documentfile.provider.DocumentFile
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.concurrent.thread

enum class GameFlag {
    Locked,
    Trial
}

@Serializable
data class GameInfo @Keep constructor(
    val path: String,
    var name: String? = null,
    var iconPath: String? = null,
    var gameFlags: Int = 0,
    var sourceUri: String? = null,
    var uuid: String = java.util.UUID.randomUUID().toString()
) {
    @Keep
    constructor(path: String, name: String?, iconPath: String?, gameFlags: Int, sourceUri: String?)
        : this(path, name, iconPath, gameFlags, sourceUri, java.util.UUID.randomUUID().toString())
}

data class GameInfoStore(
    val path: String,
    val name: MutableState<String?> = mutableStateOf(null),
    val iconPath: MutableState<String?> = mutableStateOf(null),
    val gameFlags: MutableIntState = mutableIntStateOf(0),
    val sourceUri: MutableState<String?> = mutableStateOf(null),
    val uuid: String = java.util.UUID.randomUUID().toString()
)

enum class GameProgressType {
    Install,
    Compile,
    Remove,
}

data class GameProgress(val id: Long, val type: GameProgressType)

data class Game(
    val info: GameInfoStore,
    val progressList: SnapshotStateList<GameProgress> = mutableStateListOf()
) {
    fun addProgress(progress: GameProgress) {
        if (findProgress(progress.type) != null) {
            return
        }

        progressList += progress
    }

    fun findProgress(type: GameProgressType) =
        progressList.filter { elem -> elem.type == type }.ifEmpty { null }

    fun findProgress(types: Array<GameProgressType>) =
        progressList.filter { elem -> types.contains(elem.type) }.ifEmpty { null }

    fun removeProgress(type: GameProgressType) =
        progressList.removeIf { progress -> progress.type == type }

    fun hasFlag(flag: GameFlag) = (info.gameFlags.intValue and (1 shl flag.ordinal)) != 0
}

private fun toStore(info: GameInfo) =
    GameInfoStore(
        info.path,
        mutableStateOf(info.name),
        mutableStateOf(info.iconPath),
        mutableIntStateOf(info.gameFlags),
        mutableStateOf(info.sourceUri),
        info.uuid.ifEmpty { java.util.UUID.randomUUID().toString() }
    )

private fun toInfo(store: GameInfoStore) =
    GameInfo(store.path, store.name.value, store.iconPath.value, store.gameFlags.intValue, store.sourceUri.value, store.uuid)

class GameRepository {
    private val games = mutableStateListOf<Game>()

    companion object {
        private val instance = GameRepository()

        private var needsRefresh = false
        val isRefreshing = mutableStateOf(false)
        private var isRefreshInCooldown = false

        fun save() {
            try {
                File(RPCSX.rootDirectory + "games.json").writeText(Json.encodeToString(instance.games.map { game ->
                    toInfo(
                        game.info
                    )
                }.filter { info -> info.path != "$" }))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        suspend fun load() {
            withContext(Dispatchers.IO) {
                try {
                    instance.games.clear()
                    instance.games += Json.decodeFromString<Array<GameInfo>>(
                        File(RPCSX.rootDirectory + "games.json").readText()
                    ).map { info -> Game(toStore(info)) }
                } catch (_: NotFoundException) {
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        fun queueRefresh() {
            needsRefresh = true
            if (!isRefreshing.value || isRefreshInCooldown) {
                thread {
                    isRefreshing.value = true
                    do {
                        needsRefresh = false
                        refresh()
                    } while (needsRefresh)
                    isRefreshInCooldown = true
                    Thread.sleep(300)
                    if (!needsRefresh) {
                        isRefreshInCooldown = false
                        isRefreshing.value = false
                    }
                }
            }
        }

        private fun refresh() {
            clear()
            RPCSX.instance.collectGameInfo(
                RPCSX.rootDirectory + "/config/dev_hdd0/game", -1
            )
            RPCSX.instance.collectGameInfo(RPCSX.rootDirectory + "/config/games", -1)
        }
        
        @Keep
        @JvmStatic
        fun add(gameInfos: Array<GameInfo>, progressId: Long) {
            synchronized(instance) {
                if (progressId >= 0) {
                    val progressEntry =
                        instance.games.filter { game -> game.info.path == "$" }.find { game ->
                            val progress = game.findProgress(GameProgressType.Install)
                                ?.find { progress -> progress.id == progressId }
                            progress != null
                        }

                    if (progressEntry != null) {
                        instance.games.remove(progressEntry)
                    }
                }

                gameInfos.forEach { info ->
                    val existsGame = instance.games.find { x -> x.info.path == info.path }
                    if (existsGame == null) {
                        val newGame = Game(toStore(info))
                        if (progressId >= 0) {
                            newGame.addProgress(GameProgress(progressId, GameProgressType.Install))
                        }
                        instance.games.add(0, newGame)
                    } else {
                        existsGame.info.name.value = info.name ?: existsGame.info.name.value
                        existsGame.info.iconPath.value =
                            info.iconPath ?: existsGame.info.iconPath.value
                        existsGame.info.gameFlags.intValue = info.gameFlags
                        // Re-registering a game must refresh where it boots
                        // from, or an entry whose old source went away (e.g.
                        // a dead content:// URI) stays unbootable forever even
                        // after the user re-adds the ISO.
                        existsGame.info.sourceUri.value =
                            info.sourceUri ?: existsGame.info.sourceUri.value
                        // The same game can be reported by more than one scan
                        // (nested registered directories, or a rescan while an
                        // install is still pending). addProgress() throws on a
                        // duplicate type, so only attach one Install progress.
                        if (progressId >= 0 &&
                            existsGame.findProgress(GameProgressType.Install) == null
                        ) {
                            existsGame.addProgress(
                                GameProgress(
                                    progressId,
                                    GameProgressType.Install
                                )
                            )
                        }
                    }
                }
                save()
            }
        }

        fun addPreview(gameInfos: Array<GameInfo>) {
            instance.games += gameInfos.map { info -> Game(toStore(info)) }
        }

        fun onBoot(game: Game) {
            synchronized(instance) {
                if (instance.games.first() != game) {
                    instance.games.remove(game)
                    instance.games.add(0, game)
                    save()
                }
            }
        }

        fun createGameInstallEntry(progressId: Long) {
            synchronized(instance) {
                val game = Game(GameInfoStore("$"))
                game.addProgress(GameProgress(progressId, GameProgressType.Install))
                instance.games.add(0, game)
            }
        }

        fun clearProgress(progressId: Long) {
            synchronized(instance) {
                instance.games.forEach { game -> game.progressList.removeIf { progress -> progress.id == progressId } }
                instance.games.removeIf { game -> game.info.path == "$" && game.progressList.isEmpty() }
            }
        }

        fun remove(game: Game) {
            synchronized(instance) {
                instance.games -= game
                save()
            }
        }

        // Drops games whose path or sourceUri falls under `directoryPath` from
        // the list only (does not touch any files on disk). Used when a
        // user-managed game/ISO directory is removed from Manage Directories.
        fun removeByDirectory(directoryPath: String) {
            synchronized(instance) {
                val prefix = if (directoryPath.endsWith("/")) directoryPath else "$directoryPath/"
                val removed = instance.games.removeIf { game ->
                    val path = game.info.path
                    val source = game.info.sourceUri.value
                    (path.startsWith(prefix) || path == directoryPath) ||
                        (source != null && (source.startsWith(prefix) || source == directoryPath))
                }
                if (removed) {
                    save()
                }
            }
        }

        // Drops library entries whose source is gone: content:// URIs that no
        // longer open (file deleted or permission revoked) and filesystem
        // paths that no longer exist. Entries without a source are left
        // alone. Runs content-provider queries - call from a worker thread.
        fun pruneInvalid(context: Context) {
            synchronized(instance) {
                val removed = instance.games.removeIf { game ->
                    val source = game.info.sourceUri.value ?: return@removeIf false
                    if (source.isEmpty()) {
                        return@removeIf false
                    }

                    val valid = if (source.startsWith("content:")) {
                        try {
                            DocumentFile.fromSingleUri(context, Uri.parse(source))?.exists() == true
                        } catch (_: Exception) {
                            false
                        }
                    } else {
                        File(source).exists()
                    }

                    !valid
                }

                if (removed) {
                    save()
                }
            }
        }

        fun find(path: String): Game? {
            synchronized(instance) {
                return instance.games.find { game -> game.info.path == path }
            }
        }

        fun list() = instance.games

        fun clear() {
            instance.games.clear()
        }
    }
}
