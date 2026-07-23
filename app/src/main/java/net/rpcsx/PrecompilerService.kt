package net.rpcsx

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import java.io.File
import kotlin.concurrent.thread

enum class PrecompilerServiceAction {
    InstallFirmware,
    Install
}

class PrecompilerService : Service() {
    companion object {
        fun start(context: Context, action: PrecompilerServiceAction, uri: Uri?) {
            val intent = Intent(context, PrecompilerService::class.java)
            intent.putExtra("action", action.ordinal)
            intent.putExtra("uri", uri)

            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun start(context: Context, action: PrecompilerServiceAction, batch: ArrayList<Uri>) {
            if (batch.isEmpty()) {
                return
            }

            if (batch.size == 1) {
                start(context, action, batch[0])
                return
            }

            val intent = Intent(context, PrecompilerService::class.java)
            intent.putExtra("action", action.ordinal)
            intent.putExtra("batch", batch)

            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
    }

    fun install(isFw: Boolean, uri: Uri, installProgress: Long): Boolean {
        // On API 30+ a SAF content:// read is proxied through the
        // scoped-storage FUSE daemon, which adds real overhead on top of
        // plain file I/O - noticeable on a multi-GB PUP or a big PKG. When
        // "All files access" is granted, resolveTreeUriToPath (already used
        // for registering game/ISO folders in place, see StorageAccess/
        // GameDirectoryRepository) can usually turn the picked document back
        // into its real path on primary storage or Downloads; open that
        // directly instead and skip FUSE entirely. Anything it can't resolve
        // (cloud providers, SD cards without access, etc.) falls back to the
        // SAF fd exactly as before.
        val directPfd = if (StorageAccess.isGranted()) {
            try {
                RPCSX.instance.resolveTreeUriToPath(uri.toString())
                    ?.let(::File)
                    ?.takeIf { it.canRead() }
                    ?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
            } catch (e: Exception) {
                Log.w("PrecompilerService", "Direct file open failed, falling back to SAF", e)
                null
            }
        } else null

        val assetDescriptor = if (directPfd == null) {
            try {
                contentResolver.openAssetFileDescriptor(uri, "r")
            } catch (e: Throwable) {
                Log.e("PrecompilerService", "openAssetFileDescriptor failed", e)
                reportFailure(installProgress, e.message ?: "Cannot open file")
                return false
            }
        } else null

        val fd = directPfd?.fd ?: assetDescriptor?.parcelFileDescriptor?.fd

        if (fd == null) {
            try { assetDescriptor?.close() } catch (e: Exception) { e.printStackTrace() }
            reportFailure(installProgress, "Cannot open file descriptor")
            return false
        }

        try {
            val installResult =
                if (isFw)
                    RPCSX.instance.installFw(fd, installProgress)
                else
                    RPCSX.instance.install(fd, installProgress, uri.toString())

            if (!installResult) {
                reportFailure(installProgress, "Installation failed")
            }

            return installResult
        } catch (e: Throwable) {
            // Any Throwable across the JNI boundary here would otherwise kill
            // the whole process. Report failure to the UI and return.
            Log.e("PrecompilerService", "Native install threw", e)
            reportFailure(installProgress, e.message ?: "Native install failed")
            return false
        } finally {
            try {
                directPfd?.close()
                assetDescriptor?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun reportFailure(installProgress: Long, message: String) {
        try {
            ProgressRepository.onProgressEvent(installProgress, -1, 0, message)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val batch = intent?.let { IntentCompat.getParcelableArrayListExtra(it, "batch", Uri::class.java) }
        val uri = intent?.let { IntentCompat.getParcelableExtra(it, "uri", Uri::class.java) }
        val action = intent?.getIntExtra("action", 0)
        val isFwInstall = action == PrecompilerServiceAction.InstallFirmware.ordinal

        if (uri == null && batch == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val installProgress =
            ProgressRepository.create(
                this,
                if (isFwInstall) getString(R.string.firmware_installation) else getString(R.string.package_installation)
            ) { entry ->
                if (entry.isFinished()) {
                    if (isFwInstall) {
                        FirmwareRepository.progressChannel.value = null
                    }

                    stopSelf(startId)
                }
            }

        if (isFwInstall) {
            FirmwareRepository.progressChannel.value = installProgress
        } else {
            // Show a "processing" placeholder card in the game grid while the
            // file (ISO/PKG) is installed. The placeholder is keyed to this
            // progress and removed once it finishes (ProgressRepository.cancel
            // -> GameRepository.clearProgress); the real game is added by the
            // native collectGameInfo call inside install().
            GameRepository.createGameInstallEntry(installProgress)
        }

        try {
            ServiceCompat.startForeground(
                this,
                installProgress.toInt(),
                NotificationCompat.Builder(this, "rpcsx-progress").build(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        thread {
            var installResult = false
            try {
                if (uri != null) {
                    installResult = install(isFwInstall, uri, installProgress)
                } else batch?.forEach { uri ->
                    // FIXME: create child progress
                    if (install(isFwInstall, uri, installProgress)) {
                        installResult = true
                    }
                }
            } catch (e: Throwable) {
                // Uncaught throwable on this service thread would otherwise
                // kill the whole process even if the native install already
                // succeeded (looks like a crash right at the end of the PUP
                // install).
                Log.e("PrecompilerService", "Install thread threw", e)
                reportFailure(installProgress, e.message ?: "Install failed")
            } finally {
                if (!installResult) {
                    stopSelf(startId)
                }
            }
        }

        return START_STICKY
    }
}