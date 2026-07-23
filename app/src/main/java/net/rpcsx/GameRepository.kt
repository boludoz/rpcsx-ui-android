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
    // core new enough to include it; otherwise recovered locally from disk
    // by GameRepository.add() - see ParamSfoParser.
    var titleId: String? = null,
    // PARAM.SFO CATEGORY ("DG" disc game, "HG" HDD game, "GD" disc-game
    // update, ...). Used to rank which source should represent a title when
    // the same TITLE_ID is reported from more than one place - see
    // entryRank().
    var category: String? = null
) {
    // Called from native (sendGameInfo). All four signatures are kept so the
    // APK works against a core from any prior generation (5-arg with no
    // version/titleId/category, up to the newest 8-arg one). Keep in sync
    // with the JNI constructor lookup in android/src/rpcsx-android.cpp.
    @Keep
    constructor(path: String, name: String?, iconPath: String?, gameFlags: Int, sourceUri: String?)
        : this(path, name, iconPath, gameFlags, sourceUri, "1.00", java.util.UUID.randomUUID().toString(), null, null)

    @Keep
    constructor(path: String, name: String?, iconPath: String?, gameFlags: Int, sourceUri: String?, version: String?)
        : this(path, name, iconPath, gameFlags, sourceUri, version ?: "1.00", java.util.UUID.randomUUID().toString(), null, null)

    @Keep
    constructor(path: String, name: String?, iconPath: String?, gameFlags: Int, sourceUri: String?, version: String?, titleId: String?)
        : this(path, name, iconPath, gameFlags, sourceUri, version ?: "1.00", java.util.UUID.randomUUID().toString(), titleId, null)

    @Keep
    constructor(path: String, name: String?, iconPath: String?, gameFlags: Int, sourceUri: String?, version: String?, titleId: String?, category: String?)
        : this(path, name, iconPath, gameFlags, sourceUri, version ?: "1.00", java.util.UUID.randomUUID().toString(), titleId, category)
}

data class GameInfoStore(
    val path: String,
    val name: MutableState<String?> = mutableStateOf(null),
    val iconPath: MutableState<String?> = mutableStateOf(null),
    val gameFlags: MutableIntState = mutableIntStateOf(0),
    val sourceUri: MutableState<String?> = mutableStateOf(null),
    val version: MutableState<String?> = mutableStateOf("1.00"),
    val uuid: String = java.util.UUID.randomUUID().toString(),
    val titleId: MutableState<String?> = mutableStateOf(null),
    val category: MutableState<String?> = mutableStateOf(null)
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

// A library entry that plays directly from a raw .iso, as opposed to an
// installed copy under dev_hdd0/game or config/games (a directory path).
private fun isIsoPath(path: String) = normalizePath(path).endsWith(".iso", ignoreCase = true)

// How strongly an entry should represent its title when the same TITLE_ID
// is reported from more than one source (an installed copy, a raw ISO, a
// loose folder). Higher wins; the loser is dropped from the in-memory list
// only - this never touches, moves or deletes anything on disk. A full
// installed/registered game (any category other than a bare "GD" disc-game
// update) is best; a directly-playable .iso beats a lone "GD" update, which
// isn't standalone-bootable on its own - an ISO/HDD boot applies dev_hdd0's
// update data automatically by TITLE_ID (see fetchGameInfo's version
// lookup in rpcsx-android.cpp).
private fun entryRank(path: String, category: String?): Int = when {
    !isIsoPath(path) && category != "GD" -> 3
    isIsoPath(path) -> 2
    else -> 1
}

private fun toStore(info: GameInfo) =
    GameInfoStore(
        info.path,
        mutableStateOf(info.name),
        mutableStateOf(info.iconPath),
        mutableIntStateOf(info.gameFlags),
        mutableStateOf(info.sourceUri),
        mutableStateOf(info.version ?: "1.00"),
        info.uuid.ifEmpty { java.util.UUID.randomUUID().toString() },
        mutableStateOf(info.titleId),
        mutableStateOf(info.category)
    )

private fun toInfo(store: GameInfoStore) =
    GameInfo(
        store.path,
        store.name.value,
        store.iconPath.value,
        store.gameFlags.intValue,
        store.sourceUri.value,
        store.version.value ?: "1.00",
        store.uuid,
        store.titleId.value,
        store.category.value
    )

class GameRepository {
    private val games = mutableStateListOf<Game>()

    companion object {
        private val instance = GameRepository()

        private var needsRefresh = false
        val isRefreshing = mutableStateOf(false)
        private var isRefreshInCooldown = false

        // Single consolidated file (replaces the old one-json-file-per-uuid
        // games_db/ scheme, which left orphaned duplicate files behind any
        // time a game got registered twice - see entryRank()/add() for how
        // duplicates are now prevented instead of cleaned up after the
        // fact). Placeholder install entries (path == "$") are never
        // persisted. Lenient parsing so a games.json written by a newer
        // build (extra GameInfo fields) still loads on an older one.
        private val gamesJsonLenient = Json { ignoreUnknownKeys = true }
        private fun gamesJsonFile() = File(RPCSX.rootDirectory, "games.json")

        fun save() {
            synchronized(instance) {
                try {
                    val infos = instance.games
                        .filter { game -> game.info.path != "$" }
                        .map { game -> toInfo(game.info) }
                    gamesJsonFile().writeText(gamesJsonLenient.encodeToString(infos))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        suspend fun load() {
            withContext(Dispatchers.IO) {
                try {
                    val file = gamesJsonFile()
                    if (!file.isFile) return@withContext

                    val infos = gamesJsonLenient.decodeFromString<List<GameInfo>>(file.readText())
                    infos.forEach { info ->
                        if (info.titleId.isNullOrEmpty() && info.path != "$") {
                            info.titleId = net.rpcsx.utils.ParamSfoParser.findTitleId(info.path)
                        }
                        if (info.category.isNullOrEmpty() && info.path != "$") {
                            info.category = net.rpcsx.utils.ParamSfoParser.findCategory(info.path)
                        }
                    }

                    synchronized(instance) {
                        instance.games.clear()
                        instance.games += infos.map { info -> Game(toStore(info)) }
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

                // clear() above wipes every entry, including ones sourced from
                // user-added "Manage Directories" folders (ISO or loose game
                // folders), which live outside the two paths scanned above.
                // Those are otherwise only (re-)scanned once, at app startup
                // in MainActivity - so without this, any refresh (pull-to-
                // refresh, or a user switch via UserRepository) permanently
                // dropped them from the list until the app was fully
                // restarted. Storage access was already granted when the
                // directory was added, so no permission prompt is needed here.
                GameDirectoryRepository.directories.forEach { dir ->
                    try {
                        when (dir.kind) {
                            GameDirectoryKind.Games ->
                                RPCSX.instance.collectGameInfoFromUri(dir.uri, -1)
                            GameDirectoryKind.Iso ->
                                RPCSX.instance.collectIsoInfoFromUri(dir.uri, -1)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
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
                        if (info.category.isNullOrEmpty()) info.category = prev.category
                    }

                    // Older cores don't send titleId/category at all, and a
                    // game registered from a loose folder/ISO named after its
                    // title (e.g. "PES 2010") has neither in its path either
                    // - recover both locally from disk instead. Needed for
                    // the game card subtitle / Sony update lookup (titleId)
                    // and the cross-source ranking below (category).
                    if (info.titleId.isNullOrEmpty()) {
                        info.titleId = net.rpcsx.utils.ParamSfoParser.findTitleId(info.path)
                    }
                    if (info.category.isNullOrEmpty()) {
                        info.category = net.rpcsx.utils.ParamSfoParser.findCategory(info.path)
                    }

                    // Cross-source de-duplication by title id: the SAME game
                    // must never appear twice when it's known from more than
                    // one source (an installed copy under dev_hdd0/game,
                    // and/or a raw ISO or loose folder registered by the
                    // user). Keep only the highest-ranked copy (entryRank): a
                    // full installed/registered game beats a playable .iso,
                    // which beats a bare "GD" update (e.g. the stub an
                    // update-PKG install reports at dev_hdd0/game/<id> when
                    // the real game lives elsewhere). Ties keep the copy
                    // already listed. This only prunes the on-screen list -
                    // it never touches, moves or deletes anything on disk;
                    // the version an update installed is picked up next scan
                    // via fetchGameInfo's own dev_hdd0/game/<id> lookup.
                    val incomingTid = info.titleId?.takeIf { it.isNotBlank() }
                    if (incomingTid != null) {
                        val incomingRank = entryRank(info.path, info.category)
                        val twins = instance.games.filter { g ->
                            g.info.path != "$" &&
                                normalizePath(g.info.path) != normalizePath(info.path) &&
                                g.info.titleId.value?.takeIf { it.isNotBlank() } == incomingTid
                        }
                        if (twins.isNotEmpty()) {
                            val bestTwinRank = twins.maxOf { entryRank(it.info.path, it.info.category.value) }
                            if (incomingRank > bestTwinRank) {
                                // Incoming is the better representation - drop
                                // the weaker twins and fall through to add it.
                                twins.forEach { instance.games.remove(it) }
                            } else {
                                // An equal-or-better twin already represents
                                // this title - discard the duplicate report.
                                return@forEach
                            }
                        }
                    }

                    val existsGame = instance.games.find { x -> normalizePath(x.info.path) == normalizePath(info.path) }
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
                        existsGame.info.category.value =
                            info.category ?: existsGame.info.category.value
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
