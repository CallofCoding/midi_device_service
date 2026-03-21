package com.pianopilot.midi_device_service

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.util.Log

private const val TAG                    = "BlePermissionHelper"
private const val REQUEST_CODE_BLE       = 0xBF42  // arbitrary unique request code

/**
 * Handles runtime Bluetooth permissions required on Android 12+ (API 31+).
 *
 * On Android < 12, BLUETOOTH is a normal permission (auto-granted).
 * On Android 12+, BLUETOOTH_SCAN and BLUETOOTH_CONNECT are runtime permissions.
 */
internal class BlePermissionHelper(private val context: Context) {

    var activity: Activity? = null

    // Callback waiting for permission result
    private var pendingCallback: ((String) -> Unit)? = null

    // ── Check ─────────────────────────────────────────────────────────────────────

    fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT) &&
            hasPermission(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            hasPermission(Manifest.permission.BLUETOOTH)
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // ── Request ───────────────────────────────────────────────────────────────────

    /**
     * Requests BLE runtime permissions.
     * [callback] is invoked with one of: "granted", "denied", "permanentlyDenied".
     */
    fun requestPermissions(callback: (String) -> Unit) {
        if (hasPermissions()) {
            callback("granted")
            return
        }

        val act = activity
        if (act == null) {
            Log.w(TAG, "No activity available to request permissions")
            callback("denied")
            return
        }

        pendingCallback = callback

        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.BLUETOOTH)
        }

        ActivityCompat.requestPermissions(act, permissions, REQUEST_CODE_BLE)
    }

    // ── Result handler ────────────────────────────────────────────────────────────

    /**
     * Forward this from the plugin's [onRequestPermissionsResult].
     * Returns true if this helper handled the result.
     */
    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ): Boolean {
        if (requestCode != REQUEST_CODE_BLE) return false

        val callback = pendingCallback ?: return true
        pendingCallback = null

        if (grantResults.isEmpty()) {
            callback("denied")
            return true
        }

        val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (allGranted) {
            callback("granted")
            return true
        }

        // Check if permanently denied (user checked "Don't ask again")
        val act = activity
        val permanentlyDenied = act != null && permissions.any { perm ->
            !ActivityCompat.shouldShowRequestPermissionRationale(act, perm) &&
            ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
        }

        callback(if (permanentlyDenied) "permanentlyDenied" else "denied")
        return true
    }
}
