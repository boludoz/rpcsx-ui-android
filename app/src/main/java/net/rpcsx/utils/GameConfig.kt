package net.rpcsx.utils

import net.rpcsx.RPCSX
import org.json.JSONObject
import java.io.File

/**
 * Per-game configuration overrides.
 *
 * RPCS3 applies a per-title custom config at boot when a file named
 * `config/custom_configs/config_<TITLE_ID>.yml` exists (the emulator boots
 * games with cfg_mode::custom). The file is parsed by yaml-cpp, and since
 * JSON is a valid subset of YAML we store the overrides as JSON — giving a
 * per-game JSON file the engine consumes as-is, without any core changes.
 *
 * Only the overridden nodes are written; everything else falls back to the
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

    fun configFile(titleId: String): File =
        File(RPCSX.rootDirectory + "config/custom_configs/config_$titleId.yml")

    fun exists(titleId: String): Boolean = titleId.isNotEmpty() && configFile(titleId).exists()

    fun load(titleId: String): JSONObject {
        if (!exists(titleId)) return JSONObject()
        return try {
            JSONObject(configFile(titleId).readText())
        } catch (_: Exception) {
            // Not a JSON custom config (possibly hand-written YAML): leave it
            // alone and treat as no overrides rather than clobbering it.
            JSONObject()
        }
    }

    private fun save(titleId: String, cfg: JSONObject) {
        val file = configFile(titleId)
        if (cfg.length() == 0) {
            file.delete()
            return
        }
        file.parentFile?.mkdirs()
        file.writeText(cfg.toString(2))
    }

    fun get(titleId: String, path: String): Any? {
        var node: Any? = load(titleId)
        for (seg in path.split("@@").filter { it.isNotEmpty() }) {
            node = (node as? JSONObject)?.opt(seg) ?: return null
        }
        return node
    }

    /** Sets one override. `value` must be a Boolean, Number or String. */
    fun set(titleId: String, path: String, value: Any): Boolean {
        if (titleId.isEmpty()) return false
        val segments = path.split("@@").filter { it.isNotEmpty() }
        if (segments.isEmpty()) return false

        val root = load(titleId)
        var node = root
        for (seg in segments.dropLast(1)) {
            val child = node.optJSONObject(seg) ?: JSONObject().also { node.put(seg, it) }
            node = child
        }
        node.put(segments.last(), value)
        save(titleId, root)
        return true
    }

    /** Removes one override, pruning empty parents. */
    fun remove(titleId: String, path: String) {
        val segments = path.split("@@").filter { it.isNotEmpty() }
        if (segments.isEmpty()) return

        val root = load(titleId)
        val parents = ArrayList<Pair<JSONObject, String>>()
        var node = root
        for (seg in segments.dropLast(1)) {
            parents.add(node to seg)
            node = node.optJSONObject(seg) ?: return
        }
        node.remove(segments.last())
        // Prune now-empty parent objects so an empty file gets deleted.
        for ((parent, key) in parents.asReversed()) {
            val child = parent.optJSONObject(key)
            if (child != null && child.length() == 0) parent.remove(key) else break
        }
        save(titleId, root)
    }

    /** Deletes the per-title config entirely (back to global settings). */
    fun reset(titleId: String) {
        configFile(titleId).delete()
    }

    /** Accepts the JSON scalar strings the settings UI produces for
     *  settingsSet ("true", "150", "\"Vulkan\"") and stores a typed value. */
    fun setScalarJson(titleId: String, path: String, jsonScalar: String): Boolean {
        val trimmed = jsonScalar.trim()
        val value: Any = when {
            trimmed == "true" -> true
            trimmed == "false" -> false
            trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length >= 2 ->
                trimmed.substring(1, trimmed.length - 1)
                    .replace("\\\"", "\"").replace("\\\\", "\\")
            else -> trimmed.toLongOrNull() ?: trimmed.toDoubleOrNull() ?: trimmed
        }
        return set(titleId, path, value)
    }

    /**
     * Deep-copies the global settings schema JSON (from settingsGet) and
     * replaces each leaf "value" with this title's override when present, so
     * the existing settings composables render per-game effective values.
     */
    fun mergedSettings(titleId: String, globalRoot: JSONObject): JSONObject {
        val copy = JSONObject(globalRoot.toString())
        applyOverrides(copy, load(titleId))
        return copy
    }

    private fun applyOverrides(schema: JSONObject, overrides: JSONObject) {
        overrides.keys().forEach { key ->
            val schemaChild = schema.optJSONObject(key) ?: return@forEach
            val overrideChild = overrides.opt(key)
            if (overrideChild is JSONObject && !schemaChild.has("type")) {
                applyOverrides(schemaChild, overrideChild)
            } else if (overrideChild != null && schemaChild.has("type")) {
                // cfg_to_json stores bool values as booleans and everything
                // else (int/uint/enum/string) as strings.
                if (schemaChild.optString("type") == "bool") {
                    schemaChild.put("value", overrideChild == true || overrideChild == "true")
                } else {
                    schemaChild.put("value", overrideChild.toString())
                }
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
