/* THIS FILE IS VENDORED FROM wry v0.55.1.
   Source: https://github.com/tauri-apps/wry/blob/wry-v0.55.1/src/android/kotlin/PermissionHelper.kt
   See RustWebChromeClient.kt for context. */
package com.aimonitor.app
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import java.util.ArrayList
object PermissionHelper {
  fun hasPermissions(context: Context?, permissions: Array<String>): Boolean {
    for (perm in permissions) {
      if (ActivityCompat.checkSelfPermission(
          context!!,
          perm
        ) != PackageManager.PERMISSION_GRANTED
      ) {
        return false
      }
    }
    return true
  }
  fun hasDefinedPermission(context: Context, permission: String): Boolean {
    var hasPermission = false
    val requestedPermissions = getManifestPermissions(context)
    if (!requestedPermissions.isNullOrEmpty()) {
      val requestedPermissionsList = listOf(*requestedPermissions)
      val requestedPermissionsArrayList = ArrayList(requestedPermissionsList)
      if (requestedPermissionsArrayList.contains(permission)) {
        hasPermission = true
      }
    }
    return hasPermission
  }
  fun hasDefinedPermissions(context: Context, permissions: Array<String>): Boolean {
    for (permission in permissions) {
      if (!hasDefinedPermission(context, permission)) {
        return false
      }
    }
    return true
  }
  private fun getManifestPermissions(context: Context): Array<String>? {
    var requestedPermissions: Array<String>? = null
    try {
      val pm = context.packageManager
      val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
      } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
      }
      if (packageInfo != null) {
        requestedPermissions = packageInfo.requestedPermissions
      }
    } catch (_: Exception) {
    }
    return requestedPermissions
  }
  fun getUndefinedPermissions(context: Context, neededPermissions: Array<String?>): Array<String?> {
    val undefinedPermissions = ArrayList<String?>()
    val requestedPermissions = getManifestPermissions(context)
    if (!requestedPermissions.isNullOrEmpty()) {
      val requestedPermissionsList = listOf(*requestedPermissions)
      val requestedPermissionsArrayList = ArrayList(requestedPermissionsList)
      for (permission in neededPermissions) {
        if (!requestedPermissionsArrayList.contains(permission)) {
          undefinedPermissions.add(permission)
        }
      }
      var undefinedPermissionArray = arrayOfNulls<String>(undefinedPermissions.size)
      undefinedPermissionArray = undefinedPermissions.toArray(undefinedPermissionArray)
      return undefinedPermissionArray
    }
    return neededPermissions
  }
}
