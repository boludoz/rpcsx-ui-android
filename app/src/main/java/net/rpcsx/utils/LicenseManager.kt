package net.rpcsx.utils

import net.rpcsx.GameRepository
import net.rpcsx.RPCSX
import net.rpcsx.UserRepository
import java.io.File

enum class LicenseType { Rap, Edat }

data class LicenseEntry(
    val file: File,
    val contentId: String,
    val type: LicenseType,
    val titleId: String?,
    val gameName: String?
)

/**
 * Lists the RAP/EDAT license files installed for the active user
 * (dev_hdd0/home/<user>/exdata/), matching each file's embedded TITLE_ID
 * (a CONTENT_ID looks like "UP0001-BLES01253_00-0000000000000000") back to
 * an installed game so they can be reviewed and removed individually.
 * Neither RPCS3's Qt UI nor this app had a screen for this before -
 * licenses were only ever bulk-installed via a file picker, never browsed.
 */
object LicenseManager {
    private val titleIdRegex = Regex("[A-Z]{4}\\d{5}")

    private fun exdataDir(): File =
        File(RPCSX.rootDirectory + "config/dev_hdd0/home/" + UserRepository.activeUser.value + "/exdata")

    fun list(): List<LicenseEntry> {
        val files = exdataDir().listFiles() ?: return emptyList()
        val games = GameRepository.list()

        return files.mapNotNull { file ->
            val type = when {
                file.name.endsWith(".rap", ignoreCase = true) -> LicenseType.Rap
                file.name.endsWith(".edat", ignoreCase = true) -> LicenseType.Edat
                else -> return@mapNotNull null
            }

            val contentId = file.name.substringBeforeLast(".")
            val titleId = titleIdRegex.find(contentId)?.value
            val game = titleId?.let { tid -> games.find { it.info.titleId.value == tid } }

            LicenseEntry(file, contentId, type, titleId, game?.info?.name?.value)
        }.sortedWith(compareBy({ it.gameName == null }, { it.gameName ?: it.contentId }))
    }

    fun delete(entry: LicenseEntry): Boolean {
        return try {
            entry.file.delete()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
