package net.rpcsx

import android.content.Context
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
    var version: String? = "1.00",
    var uuid: String = java.util.UUID.randomUUID().toString(),
    // Real PARAM.SFO TITLE_ID (e.g. "BLES01253"). Sent directly by a native
    // core new enough to include it (7-arg constructor below); otherwise
    // recovered locally from disk by GameRepository.add() - see
    // ParamSfoParser.
    var titleId: String? = null
) {
    // Called from native (sendGameInfo). All three signatures are kept so
    // the APK works against an old core (5-arg, no version/titleId), a
    // mid-generation one (6-arg, with version) and a new one (7-arg, with
    // titleId). Keep in sync with the JNI constructor lookup in
    // android/src/rpcsx-android.cpp.
    @Keep
    constructor(path: String, name: String?, iconPath: String?, gameFlags: Int, sourceUri: String?)
        : this(path, name, iconPath, gameFlags, sourceUri, "1.00", java.util.UUID.randomUUID().toString(), null)

    @Keep
    constructor(path: String, name: String?, iconPath: String?, gameFlags: Int, sourceUri: String?, version: String?)
        : this(path, name, iconPath, gameFlags, sourceUri, version ?: "1.00", java.util.UUID.randomUUID().toString(), null)

    @Keep
    constructor(path: String, name: String?, iconPath: String?, gameFlags: Int, sourceUri: String?, version: String?, titleId: String?)
        : this(path, name, iconPath, gameFlags, sourceUri, version ?: "1.00", java.util.UUID.randomUUID().toString(), titleId)
}

data class GameInfoStore(
    val path: String,
    val name: MutableState<String?> = mutableStateOf(null),
    val iconPath: MutableState<String?> = mutableStateOf(null),
    val gameFlags: MutableIntState = mutableIntStateOf(0),
    val sourceUri: MutableState<String?> = mutableStateOf(null),
    val version: MutableState<String?> = mutableStateOf("1.00"),
    val uuid: String = java.util.UUID.randomUUID().toString(),
    val titleId: MutableState<String?> = mutableStateOf(null)
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

// Identity key for matching a GameInfo to an already-registered Game by
// path. Native reports a game's path slightly differently between a plain
// directory scan and a post-install callback (e.g. a trailing slash), which
// made an update-PKG install fail this lookup on exact string equality and
// register the same game a second time instead of updating it in place.
private fun normalizePath(path: String) = path.trimEnd('/')

// True only for the exact directory the native core uses to install/patch a
// given TITLE_ID (dev_hdd0/game/<TITLE_ID>/) - never for a user's own,
// independently chosen folder or ISO path, even if it happens to carry the
// same titleId. Keeps the titleId merge fallback in add() from ever folding
// two deliberately separate library entries (e.g. a folder copy and an ISO
// copy of the same game) into one.
private fun isStandardInstallStubPath(path: String, titleId: String) =
    normalizePath(path) == normalizePath(RPCSX.rootDirectory + "config/dev_hdd0/game/" + titleId)

private fun toStore(info: GameInfo) =
    GameInfoStore(
        info.path,
        mutableStateOf(info.name),
        mutableStateOf(info.iconPath),
        mutableIntStateOf(info.gameFlags),
        mutableStateOf(info.sourceUri),
        mutableStateOf(info.version ?: "1.00"),
        info.uuid.ifEmpty { java.util.UUID.randomUUID().toString() },
        mutableStateOf(info.titleId)
    )

private fun toInfo(store: GameInfoStore) =
    GameInfo(store.path, store.name.value, store.iconPath.value, store.gameFlags.intValue, store.sourceUri.value, store.version.value ?: "1.00", store.uuid, store.titleId.value)

class GameRepository {
    private val games = mutableStateListOf<Game>()

    companion object {
        private val instance = GameRepository()

        private var needsRefresh = false
        val isRefreshing = mutableStateOf(false)
        private var isRefreshInCooldown = false

        // One JSON file per game, keyed by uuid, under games_db/. Placeholder
        // install entries (path == "$") are never persisted.
        private val gamesDbDir get() = File(RPCSX.rootDirectory, "games_db")

        private fun gameFile(info: GameInfo) = File(gamesDbDir, "${info.uuid}.json")

        fun save() {
            synchronized(instance) {
                try {
                    val dir = gamesDbDir
                    dir.mkdirs()

                    val keep = HashSet<String>()
                    instance.games
                        .filter { game -> game.info.path != "$" }
                        .forEach { game ->
                            val info = toInfo(game.info)
                            val file = gameFile(info)
                            file.writeText(Json.encodeToString(info))
                            keep.add(file.name)
                        }

                    // Drop files for games no longer in the list.
                    dir.listFiles()?.forEach { file ->
                        if (file.name.endsWith(".json") && file.name !in keep) {
                            file.delete()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        suspend fun load() {
            withContext(Dispatchers.IO) {
                try {
                    val dir = gamesDbDir
                    val files = dir.listFiles()?.filter { it.name.endsWith(".json") }.orEmpty()
                    val loaded = files.mapNotNull { file ->
                        try {
                            val info = Json.decodeFromString<GameInfo>(file.readText())
                            if (info.titleId.isNullOrEmpty() && info.path != "$") {
                                info.titleId = net.rpcsx.utils.ParamSfoParser.findTitleId(info.path)
                            }
                            info to file.lastModified()
                        } catch (e: Exception) {
                            e.printStackTrace()
                            null
                        }
                    }

                    // An update/patch PKG is installed by the native core
                    // into the standard dev_hdd0/game/<TITLE_ID>/ directory
                    // even when the playable game itself was registered from
                    // an unrelated ISO or loose folder. Before the native
                    // core started sending titleId directly (see
                    // GameInfo.titleId / rpcsx-android.cpp), that path
                    // mismatch could register the same game twice under
                    // different uuids. Collapse any such leftover duplicates
                    // here so an already-corrupted games_db self-heals on
                    // next launch - but only the core's own install stub
                    // (isStandardInstallStubPath) gets folded away. Two
                    // independently registered copies of the same title (a
                    // folder AND an ISO, say) share a titleId too and must
                    // stay as two separate entries.
                    val deduped = loaded
                        .groupBy { (info, _) ->
                            when {
                                info.path == "$" -> "uuid:${info.uuid}"
                                !info.titleId.isNullOrEmpty() -> "tid:${info.titleId}"
                                else -> "path:${normalizePath(info.path)}"
                            }
                        }
                        .flatMap { (_, group) ->
                            if (group.size == 1) {
                                return@flatMap listOf(group[0].first)
                            }

                            val (stubs, real) = group.partition { (info, _) ->
                                isStandardInstallStubPath(info.path, info.titleId ?: "")
                            }

                            if (real.isEmpty()) {
                                // Every duplicate is the core's own stub (e.g.
                                // repeated updates before this fix) - collapse
                                // them like any other path duplicate.
                                val base = group.minByOrNull { it.second }!!.first
                                val newest = group.maxByOrNull { it.first.version ?: "" }!!.first
                                listOf(base.apply {
                                    version = newest.version ?: version
                                    name = newest.name ?: name
                                    iconPath = newest.iconPath ?: iconPath
                                })
                            } else if (stubs.isEmpty()) {
                                // No stub involved - genuinely separate copies.
                                real.map { it.first }
                            } else {
                                // Fold the leftover stub's newer version/name/
                                // icon into the oldest real (user-registered)
                                // entry and drop the stub; keep any other real
                                // entries untouched.
                                val base = real.minByOrNull { it.second }!!.first
                                val newest = group.maxByOrNull { it.first.version ?: "" }!!.first
                                val merged = base.apply {
                                    version = newest.version ?: version
                                    name = newest.name ?: name
                                    iconPath = newest.iconPath ?: iconPath
                                }
                                listOf(merged) + real.filter { it.first !== base }.map { it.first }
                            }
                        }

                    synchronized(instance) {
                        instance.games.clear()
                        instance.games += deduped.map { info -> Game(toStore(info)) }
                    }
                    if (deduped.size != loaded.size) {
                        save()
                    }
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

        // Metadata that only our DB knows (the native scan cannot reproduce
        // it): sourceUri, version and the stable uuid. Populated for the
        // duration of a refresh so add() can restore it onto rescanned games,
        // keyed by path. Otherwise clear()+rescan would drop the ISO source of
        // every disc game and leave them unbootable ("nothing to boot").
        private var preservedMetadata: Map<String, GameInfo> = emptyMap()

        private fun refresh() {
            preservedMetadata = synchronized(instance) {
                instance.games
                    .filter { game -> game.info.path != "$" }
                    .associate { game -> normalizePath(game.info.path) to toInfo(game.info) }
            }
            try {
                clear()
                RPCSX.instance.collectGameInfo(
                    RPCSX.rootDirectory + "/config/dev_hdd0/game", -1
                )
                RPCSX.instance.collectGameInfo(RPCSX.rootDirectory + "/config/games", -1)
            } finally {
                preservedMetadata = emptyMap()
            }
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
                    // Restore DB-only metadata (sourceUri/version/uuid) that a
                    // native rescan strips, so refresh() never loses the ISO
                    // source that a disc game needs to boot.
                    preservedMetadata[normalizePath(info.path)]?.let { prev ->
                        if (info.sourceUri == null) info.sourceUri = prev.sourceUri
                        if (info.version == null) info.version = prev.version
                        if (prev.uuid.isNotEmpty()) info.uuid = prev.uuid
                        if (info.titleId.isNullOrEmpty()) info.titleId = prev.titleId
                    }

                    // The native scan never reports the real PARAM.SFO
                    // TITLE_ID, only the display name - so a game registered
                    // from a loose folder/ISO named after its title (e.g.
                    // "PES 2010") would otherwise show that name where the
                    // serial is expected (game card subtitle, Sony update
                    // lookup). Recover it locally from disk instead.
                    if (info.titleId.isNullOrEmpty()) {
                        info.titleId = net.rpcsx.utils.ParamSfoParser.findTitleId(info.path)
                    }

                    // Path match first. An update/patch PKG is installed by
                    // the native core into the standard
                    // dev_hdd0/game/<TITLE_ID>/ directory regardless of where
                    // the game itself was registered from (e.g. a loose ISO
                    // or folder elsewhere), so that report needs a titleId
                    // fallback or it reads as a brand new game and produces a
                    // duplicate entry (see normalizePath doc comment).
                    //
                    // The fallback is deliberately restricted to that one
                    // standard stub path rather than "any game sharing this
                    // titleId" - two independently registered copies of the
                    // same game (e.g. a folder AND an ISO of the same title)
                    // must stay as two separate library entries; only the
                    // core's own patch-install stub should ever get folded
                    // into an existing entry.
                    val existsGame = instance.games.find { x -> normalizePath(x.info.path) == normalizePath(info.path) }
                        ?: info.titleId?.takeIf { it.isNotEmpty() && isStandardInstallStubPath(info.path, it) }
                            ?.let { tid -> instance.games.find { x -> x.info.titleId.value == tid } }
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
                        // A newer PARAM.SFO (e.g. from an update PKG install)
                        // must overwrite the displayed version, or the game
                        // card keeps showing the version it was first
                        // installed with forever.
                        existsGame.info.version.value =
                            info.version ?: existsGame.info.version.value
                        existsGame.info.titleId.value =
                            info.titleId ?: existsGame.info.titleId.value
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
                return instance.games.find { game -> normalizePath(game.info.path) == normalizePath(path) }
            }
        }

        fun list() = instance.games

        fun clear() {
            instance.games.clear()
        }
    }
}
