package net.rpcsx.utils

import net.rpcsx.GameRepository
import net.rpcsx.RPCSX
import org.json.JSONObject
import java.io.File

/**
 * Per-game configuration overrides stored as clean local JSON files.
 *
 * Each configuration is named config_<UUID>_<TITLE_ID>.json, allowing
 * multiple separate configurations even for games sharing the same Title ID.
 *
 * Only overridden values are saved. The UI dynamically merges them with
 * the global schema retrieved from the native emulator core.
 */
object GameConfig {
    /** Title id for a game list entry path. Matches the layout used by the
     *  engine: hdd0 games live in .../game/<TITLE_ID>/, disc/ISO games in
     *  .../games/<TITLE_ID>/ (path may point at <TITLE_ID>.iso directly). */
    fun titleIdForPath(path: String): String {
        var id = path.trimEnd('/').substringAfterLast('/')
        if (id.endsWith(".iso", ignoreCase = true)) {
            id = id.dropLast(4)
        }
        return id.uppercase()
    }

    private fun getCustomConfigDir(): File {
        val dir = File(RPCSX.rootDirectory + "config/custom_configs")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getCustomConfigPath(uuid: String, titleId: String): File {
        return File(getCustomConfigDir(), "config_${uuid}_${titleId}.json")
    }

    private fun loadCustomConfig(uuid: String, titleId: String): JSONObject {
        val file = getCustomConfigPath(uuid, titleId)
        if (!file.exists()) return JSONObject()
        return try {
            JSONObject(file.readText())
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun saveCustomConfig(uuid: String, titleId: String, json: JSONObject) {
        val file = getCustomConfigPath(uuid, titleId)
        if (json.length() == 0) {
            if (file.exists()) file.delete()
        } else {
            try {
                file.writeText(json.toString(2))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** The whole settings schema with this title's overrides merged in, for
     *  the per-game advanced settings tree. */
    fun mergedSettings(uuid: String, titleId: String): JSONObject {
        val globalSchema = try {
            JSONObject(RPCSX.instance.settingsGet("", ""))
        } catch (_: Exception) {
            JSONObject()
        }
        if (uuid.isEmpty() || titleId.isEmpty()) return globalSchema
        val custom = loadCustomConfig(uuid, titleId)
        mergeCustomIntoSchema(globalSchema, custom)
        return globalSchema
    }

    private fun mergeCustomIntoSchema(schema: JSONObject, custom: JSONObject) {
        custom.keys().forEach { key ->
            val customVal = custom.get(key)
            val schemaVal = schema.optJSONObject(key)
            if (schemaVal != null) {
                if (schemaVal.has("type")) {
                    // Leaf node in schema
                    schemaVal.put("value", customVal)
                    schemaVal.put("overridden", true)
                } else if (customVal is JSONObject) {
                    // Intermediate node
                    mergeCustomIntoSchema(schemaVal, customVal)
                }
            }
        }
    }

    /** The schema node at `path` with this title's overrides merged in
     *  ("value" is the effective value, "overridden" marks per-title ones). */
    fun node(uuid: String, titleId: String, path: String): JSONObject {
        val merged = mergedSettings(uuid, titleId)
        return subtree(merged, path) ?: JSONObject()
    }

    /** True when the entry being edited is the game currently booted, so its
     *  overrides can be pushed into the live config. The core additionally
     *  gates on the emulator actually running and on the setting being
     *  dynamic, so a stale activeGame is harmless. */
    private fun isRunningGame(uuid: String): Boolean {
        val activePath = RPCSX.activeGame.value ?: return false
        return uuid.isNotEmpty() && GameRepository.find(activePath)?.info?.uuid == uuid
    }

    /** `value` as the JSON scalar the core's settings bridge expects. */
    private fun toJsonScalar(value: Any): String = when (value) {
        is Boolean, is Number -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    /** Sets one override. `value` must be a Boolean, Number or String. */
    fun set(uuid: String, titleId: String, path: String, value: Any): Boolean {
        if (uuid.isEmpty() || titleId.isEmpty()) return false
        val custom = loadCustomConfig(uuid, titleId)
        setInJson(custom, path, value)
        saveCustomConfig(uuid, titleId, custom)
        if (isRunningGame(uuid)) {
            RPCSX.instance.settingsLiveApply(path, toJsonScalar(value))
        }
        return true
    }

    private fun setInJson(root: JSONObject, path: String, value: Any) {
        val segments = path.split("@@").filter { it.isNotEmpty() }
        if (segments.isEmpty()) return
        var cur = root
        for (i in 0 until segments.size - 1) {
            var next = cur.optJSONObject(segments[i])
            if (next == null) {
                next = JSONObject()
                cur.put(segments[i], next)
            }
            cur = next
        }
        cur.put(segments.last(), value)
    }

    /** Removes one override (prunes empty parent objects). */
    fun remove(uuid: String, titleId: String, path: String) {
        if (uuid.isEmpty() || titleId.isEmpty()) return
        val custom = loadCustomConfig(uuid, titleId)
        removeFromJson(custom, path)
        saveCustomConfig(uuid, titleId, custom)
        if (isRunningGame(uuid)) {
            // Empty value = revert the live setting to its global value.
            RPCSX.instance.settingsLiveApply(path, "")
        }
    }

    private fun removeFromJson(root: JSONObject, path: String) {
        val segments = path.split("@@").filter { it.isNotEmpty() }
        if (segments.isEmpty()) return
        val chain = mutableListOf<Pair<JSONObject, String>>()
        var cur = root
        for (i in 0 until segments.size - 1) {
            val next = cur.optJSONObject(segments[i]) ?: return
            chain.add(Pair(cur, segments[i]))
            cur = next
        }
        cur.remove(segments.last())

        // Prune empty parent objects
        for (i in chain.indices.reversed()) {
            val (parent, key) = chain[i]
            val child = parent.optJSONObject(key)
            if (child != null && child.length() == 0) {
                parent.remove(key)
            }
        }
    }

    /** Deletes the per-title config entirely (back to global settings). */
    fun reset(uuid: String, titleId: String) {
        if (uuid.isEmpty() || titleId.isEmpty()) return
        val overrides = loadCustomConfig(uuid, titleId)
        val file = getCustomConfigPath(uuid, titleId)
        if (file.exists()) {
            file.delete()
        }
        if (isRunningGame(uuid)) {
            // Revert every removed override in the live config to its
            // global value.
            forEachLeafPath(overrides, "") { path ->
                RPCSX.instance.settingsLiveApply(path, "")
            }
        }
    }

    /** Invokes `action` with the "A@@B" path of every scalar leaf. */
    private fun forEachLeafPath(node: JSONObject, prefix: String, action: (String) -> Unit) {
        node.keys().forEach { key ->
            val child = node.get(key)
            val path = if (prefix.isEmpty()) key else "$prefix@@$key"
            if (child is JSONObject) {
                forEachLeafPath(child, path, action)
            } else {
                action(path)
            }
        }
    }

    /** Returns the sub-object of a settings JSON at an "A@@B" path. */
    fun subtree(root: JSONObject, path: String): JSONObject? {
        var node = root
        for (seg in path.split("@@").filter { it.isNotEmpty() }) {
            node = node.optJSONObject(seg) ?: return null
        }
        return node
    }
}
