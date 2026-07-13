package net.rpcsx.utils

import net.rpcsx.RPCSX
import org.json.JSONObject

/**
 * Per-game configuration overrides.
 *
 * RPCS3 applies a per-title custom config at boot when a file named
 * `config/custom_configs/config_<TITLE_ID>.yml` exists (the emulator boots
 * games with cfg_mode::custom). The file is real YAML, written by the core
 * itself (yaml-cpp + Emulator::SaveSettings) exactly like the desktop
 * settings dialog - this class is only a thin bridge to those natives.
 *
 * Only the overridden nodes are stored; everything else falls back to the
 * global config at boot. Setting paths use the same "A@@B@@C" notation as
 * the settings screens (e.g. "Video@@Resolution Scale").
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
        return id
    }

    /** The schema node at `path` with this title's overrides merged in
     *  ("value" is the effective value, "overridden" marks per-title ones). */
    fun node(titleId: String, path: String): JSONObject {
        return try {
            JSONObject(RPCSX.instance.settingsGet(titleId, path))
        } catch (_: Exception) {
            JSONObject()
        }
    }

    /** The override value as a string when the setting is overridden for
     *  this title, otherwise null. */
    fun get(titleId: String, path: String): String? {
        val n = node(titleId, path)
        return if (n.optBoolean("overridden", false)) n.optString("value") else null
    }

    /** Sets one override. `value` must be a Boolean, Number or String. */
    fun set(titleId: String, path: String, value: Any): Boolean {
        val jsonScalar = when (value) {
            is Boolean, is Number -> value.toString()
            else -> JSONObject.quote(value.toString())
        }
        return setScalarJson(titleId, path, jsonScalar)
    }

    /** Accepts the JSON scalar strings the settings UI produces for
     *  settingsSet ("true", "150", "\"Vulkan\""). */
    fun setScalarJson(titleId: String, path: String, jsonScalar: String): Boolean {
        if (titleId.isEmpty()) return false
        return RPCSX.instance.settingsSet(titleId, path, jsonScalar)
    }

    /** Removes one override (the core prunes empty parents and deletes the
     *  file when the last override goes away). */
    fun remove(titleId: String, path: String) {
        if (titleId.isEmpty()) return
        RPCSX.instance.settingsRemove(titleId, path)
    }

    /** Deletes the per-title config entirely (back to global settings). */
    fun reset(titleId: String) {
        if (titleId.isEmpty()) return
        RPCSX.instance.settingsRemove(titleId, "")
    }

    /** The whole settings schema with this title's overrides merged in, for
     *  the per-game advanced settings tree. */
    fun mergedSettings(titleId: String): JSONObject = node(titleId, "")

    /** Returns the sub-object of a settings JSON at an "A@@B" path. */
    fun subtree(root: JSONObject, path: String): JSONObject? {
        var node = root
        for (seg in path.split("@@").filter { it.isNotEmpty() }) {
            node = node.optJSONObject(seg) ?: return null
        }
        return node
    }
}
