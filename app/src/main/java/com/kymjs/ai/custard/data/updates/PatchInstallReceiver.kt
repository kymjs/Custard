package com.kymjs.ai.custard.data.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kymjs.ai.custard.util.AppLogger
import java.io.File

class PatchInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PatchUpdateInstaller.ACTION_INSTALL_PATCH) return

        val path = intent.getStringExtra(PatchUpdateInstaller.EXTRA_APK_PATH) ?: return
        val version = intent.getStringExtra(PatchUpdateInstaller.EXTRA_PATCH_VERSION).orEmpty()
        val file = File(path)
        if (!file.exists()) {
            AppLogger.w("PatchInstallReceiver", "apk not found: $path")
            return
        }

        PatchUpdateInstaller.installApk(context, file)
    }
}
