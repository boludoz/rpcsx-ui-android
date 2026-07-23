package net.rpcsx.utils

import net.rpcsx.GameRepository
import net.rpcsx.RPCSX
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Result of trying to apply the official RPCS3 community config for a game. */
sealed class CommunityConfigResult {
    /** A community config was found and saved as this game's custom config. */
    object Applied : CommunityConfigResult()

    /** The database has no recommended config for this game. */
    object NotFound : CommunityConfigResult()

    data class Error(val message: String) : CommunityConfigResult()
}

/** Result of fetching (but not yet applying) the community config, for preview. */
sealed class CommunityConfigFetch {
    /** The recommended config YAML, so the UI can preview it before applying. */
    data class Found(val yaml: String) : CommunityConfigFetch()
    object NotFound : CommunityConfigFetch()
    data class Error(val message: String) : CommunityConfigFetch()
}

/**
 * Per-game custom configuration. The core owns the actual YAML
 * (config/custom_configs/config_<serial>.yml) and applies it at boot via
 * cfg_mode::custom, keyed purely by the game's TITLE_ID - so two sources of
 * the same game (an installed copy, an ISO, a loose folder) share one
 * config, and it survives a re-registration that would have minted a new
 * uuid under the old scheme. This merges the raw overrides the core returns
 * onto the global schema (settingsGet) client-side, same split GameConfig.kt
 * used before - only the storage backend and the key changed.
 */
object PerGameConfigRepository {
    private val client = OkHttpClient()

    // The official RPCS3 config database (same source the desktop app uses).
    private const val CONFIG_DB_URL = "https://api.rpcs3.net/config/?api=v1"

    fun hasCustomConfig(serial: String): Boolean =
        serial.isNotEmpty() && runCatching { RPCSX.instance.customConfigExists(serial) }.getOrDefault(false)

    /** Deletes the per-title config entirely (back to global settings). */
    fun deleteCustomConfig(serial: String): Boolean {
        if (serial.isEmpty()) return false
        val overrides = getOverrides(serial)
        val deleted = runCatching { RPCSX.instance.customConfigDelete(serial) }.getOrDefault(false)
        if (deleted && isRunningGame(serial)) {
            // Revert every removed override in the live config to its global value.
            forEachLeafPath(overrides, "") { path -> RPCSX.instance.settingsLiveApply(path, "") }
        }
        return deleted
    }

    private fun getOverrides(serial: String): JSONObject {
        if (serial.isEmpty()) return JSONObject()
        return try {
            JSONObject(RPCSX.instance.customConfigGetOverrides(serial))
        } catch (_: Exception) {
            JSONObject()
        }
    }

    /** The whole settings schema with this title's overrides merged in, for
     *  the per-game advanced settings tree. */
    fun mergedSettings(serial: String): JSONObject {
        val globalSchema = try {
            JSONObject(RPCSX.instance.settingsGet("", ""))
        } catch (_: Exception) {
            JSONObject()
        }
        if (serial.isEmpty()) return globalSchema
        mergeOverridesIntoSchema(globalSchema, getOverrides(serial))
        return globalSchema
    }

    private fun mergeOverridesIntoSchema(schema: JSONObject, overrides: JSONObject) {
        overrides.keys().forEach { key ->
            val overrideVal = overrides.get(key)
            val schemaVal = schema.optJSONObject(key)
            if (schemaVal != null) {
                if (schemaVal.has("type")) {
                    // Leaf node in schema. The core always returns overrides
                    // as plain strings (see customConfigGetOverrides) - the
                    // UI already reads "value" through optString, so no
                    // further type coercion is needed here.
                    schemaVal.put("value", overrideVal)
                    schemaVal.put("overridden", true)
                } else if (overrideVal is JSONObject) {
                    // Intermediate node
                    mergeOverridesIntoSchema(schemaVal, overrideVal)
                }
            }
        }
    }

    /** The schema node at `path` with this title's overrides merged in
     *  ("value" is the effective value, "overridden" marks per-title ones). */
    fun node(serial: String, path: String): JSONObject =
        subtree(mergedSettings(serial), path) ?: JSONObject()

    /** Returns the sub-object of a settings JSON at an "A@@B" path. */
    fun subtree(root: JSONObject, path: String): JSONObject? {
        var node = root
        for (seg in path.split("@@").filter { it.isNotEmpty() }) {
            node = node.optJSONObject(seg) ?: return null
        }
        return node
    }

    /** True when the entry being edited is the game currently booted, so its
     *  overrides can be pushed into the live config. */
    private fun isRunningGame(serial: String): Boolean {
        if (serial.isEmpty()) return false
        val activePath = RPCSX.activeGame.value ?: return false
        return GameRepository.find(activePath)?.info?.titleId?.value == serial
    }

    /** `value` as the JSON scalar the core's settings bridge expects. */
    private fun toJsonScalar(value: Any): String = when (value) {
        is Boolean, is Number -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    /** Sets one override. `value` must be a Boolean, Number or String. */
    fun set(serial: String, path: String, value: Any): Boolean {
        if (serial.isEmpty()) return false
        if (!RPCSX.instance.customConfigSet(serial, path, toJsonScalar(value))) return false
        if (isRunningGame(serial)) {
            RPCSX.instance.settingsLiveApply(path, toJsonScalar(value))
        }
        return true
    }

    /** Removes one override (reverts to the global value). */
    fun remove(serial: String, path: String) {
        if (serial.isEmpty()) return
        RPCSX.instance.customConfigRemove(serial, path)
        if (isRunningGame(serial)) {
            // Empty value = revert the live setting to its global value.
            RPCSX.instance.settingsLiveApply(path, "")
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

    /**
     * Download the official RPCS3 config database and return this game's
     * recommended config YAML (without applying it) so the UI can preview the
     * changes before the user commits.
     */
    fun fetchCommunityConfig(serial: String): CommunityConfigFetch {
        if (serial.isEmpty()) return CommunityConfigFetch.Error("Unknown game serial")
        return try {
            val request = Request.Builder().url(CONFIG_DB_URL)
                .header("User-Agent", "rpcsx").build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return CommunityConfigFetch.Error("HTTP ${resp.code}")

                val root = JSONObject(resp.body?.string().orEmpty())
                when (val rc = root.optInt("return_code", -255)) {
                    in 0..Int.MAX_VALUE -> Unit
                    -2 -> return CommunityConfigFetch.Error("Server in maintenance mode")
                    else -> return CommunityConfigFetch.Error("Server error (code $rc)")
                }

                val games = root.optJSONObject("games")
                    ?: return CommunityConfigFetch.Error("Malformed database")
                val game = games.optJSONObject(serial) ?: return CommunityConfigFetch.NotFound
                val yaml = game.optString("config")
                if (yaml.isEmpty()) return CommunityConfigFetch.NotFound
                CommunityConfigFetch.Found(yaml)
            }
        } catch (e: Throwable) {
            CommunityConfigFetch.Error(e.message ?: "Download failed")
        }
    }

    /** Save a config YAML as the game's custom config (core validates the schema). */
    fun importConfig(serial: String, yaml: String): Boolean {
        if (serial.isEmpty()) return false
        return RPCSX.instance.customConfigImportYaml(serial, yaml)
    }

    /** Fetch + apply in one step (kept for callers that don't preview first). */
    fun applyCommunityConfig(serial: String): CommunityConfigResult =
        when (val f = fetchCommunityConfig(serial)) {
            is CommunityConfigFetch.Found ->
                if (importConfig(serial, f.yaml)) CommunityConfigResult.Applied
                else CommunityConfigResult.Error("Config rejected by emulator")
            is CommunityConfigFetch.NotFound -> CommunityConfigResult.NotFound
            is CommunityConfigFetch.Error -> CommunityConfigResult.Error(f.message)
        }
}
