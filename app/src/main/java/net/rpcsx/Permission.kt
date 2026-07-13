package net.rpcsx

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

enum class Permission(val id: Int, val key: String) {
    @SuppressLint("InlinedApi")
    PostNotifications(100, Manifest.permission.POST_NOTIFICATIONS);

    fun checkPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, key) == PackageManager.PERMISSION_GRANTED

    fun requestPermission(activity: Activity) {
        if (!checkPermission(activity)) {
            ActivityCompat.requestPermissions(activity, arrayOf(key), id)
        }
    }
}

// MANAGE_EXTERNAL_STORAGE ("All files access") is a special access, not a
// runtime permission: it can't be requested via ActivityCompat, it is toggled
// by the user in a system settings screen. Needed to register game/ISO
// folders in place (real filesystem path) instead of copying them.
object StorageAccess {
    fun isGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun requestAccess(context: Context) {
        if (isGranted()) return

        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
            }
        }
    }
}
