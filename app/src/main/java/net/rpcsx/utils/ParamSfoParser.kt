package net.rpcsx.utils

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal reader for the PS3 PARAM.SFO format, used to recover the real
 * TITLE_ID (e.g. "BLES01253") for games that were registered from a loose
 * folder/ISO whose file name is just the display title (e.g. "PES 2010"),
 * which is not a valid serial for Sony's update server.
 */
object ParamSfoParser {
    private const val INDEX_ENTRY_SIZE = 16
    private const val HEADER_SIZE = 20
    private const val FMT_INT32 = 0x0404

    fun parse(file: File): Map<String, String>? {
        if (!file.isFile) return null
        return try {
            parse(file.readBytes())
        } catch (_: Exception) {
            null
        }
    }

    fun parse(bytes: ByteArray): Map<String, String>? {
        if (bytes.size < HEADER_SIZE) return null
        if (bytes[0] != 0.toByte() || bytes[1] != 'P'.code.toByte() ||
            bytes[2] != 'S'.code.toByte() || bytes[3] != 'F'.code.toByte()
        ) {
            return null
        }

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val keyTableStart = buf.getInt(8)
        val dataTableStart = buf.getInt(12)
        val entriesCount = buf.getInt(16)

        val result = LinkedHashMap<String, String>()

        for (i in 0 until entriesCount) {
            val entryOffset = HEADER_SIZE + i * INDEX_ENTRY_SIZE
            if (entryOffset + INDEX_ENTRY_SIZE > bytes.size) break

            val keyOffset = buf.getShort(entryOffset).toInt() and 0xFFFF
            val dataFmt = buf.getShort(entryOffset + 2).toInt() and 0xFFFF
            val dataLen = buf.getInt(entryOffset + 4)
            val dataOffset = buf.getInt(entryOffset + 12)

            val keyStart = keyTableStart + keyOffset
            if (keyStart < 0 || keyStart >= bytes.size) continue
            var keyEnd = keyStart
            while (keyEnd < bytes.size && bytes[keyEnd] != 0.toByte()) keyEnd++
            val key = String(bytes, keyStart, keyEnd - keyStart, Charsets.US_ASCII)

            if (dataLen < 0) continue
            val valueStart = dataTableStart + dataOffset
            if (valueStart < 0 || valueStart + dataLen > bytes.size) continue

            val value = if (dataFmt == FMT_INT32) {
                if (dataLen >= 4) buf.getInt(valueStart).toString() else "0"
            } else {
                var end = valueStart
                val limit = valueStart + dataLen
                while (end < limit && bytes[end] != 0.toByte()) end++
                String(bytes, valueStart, end - valueStart, Charsets.UTF_8)
            }

            result[key] = value
        }

        return result
    }

    /**
     * Looks for PARAM.SFO under the common layouts (flat, as used by
     * installed games under dev_hdd0/game/<ID>/, and disc-style PS3_GAME/)
     * and returns its parsed entries if found (TITLE_ID, CATEGORY, ...).
     */
    fun findGameMeta(gamePath: String): Map<String, String>? {
        val base = File(gamePath)
        // The real file is always named "PARAM.SFO" on PS3, but user storage
        // can surface it with different casing (SAF-backed folders, some
        // rebuilt dumps) - so match case-insensitively instead of assuming
        // the on-disk casing matches exactly.
        val dirs = listOf(base, File(base, "PS3_GAME"))

        for (dir in dirs) {
            val file = dir.listFiles()?.firstOrNull { it.isFile && it.name.equals("PARAM.SFO", ignoreCase = true) }
                ?: continue
            val meta = parse(file) ?: continue
            if (!meta["TITLE_ID"].isNullOrBlank()) return meta
        }
        return null
    }

    fun findTitleId(gamePath: String): String? =
        findGameMeta(gamePath)?.get("TITLE_ID")?.trim()?.takeIf { it.isNotEmpty() }

    fun findCategory(gamePath: String): String? =
        findGameMeta(gamePath)?.get("CATEGORY")?.trim()?.takeIf { it.isNotEmpty() }
}
