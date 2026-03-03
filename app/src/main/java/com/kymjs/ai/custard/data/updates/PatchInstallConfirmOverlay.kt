package com.kymjs.ai.custard.data.updates

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.kymjs.ai.custard.R
import com.kymjs.ai.custard.services.ServiceLifecycleOwner
import com.kymjs.ai.custard.ui.floating.FloatingWindowTheme
import com.kymjs.ai.custard.util.AppLogger
import java.io.File
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PatchInstallConfirmContent(
    version: String,
    onInstall: () -> Unit,
    onCancel: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(80)
        visible = true
    }

    FloatingWindowTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.96f)
            ) {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .fillMaxHeight(0.45f)
                        .padding(16.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = stringResource(R.string.patch_install_confirm_title),
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        val scrollState = rememberScrollState()
                        Text(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .verticalScroll(scrollState),
                            text = stringResource(R.string.patch_install_confirm_message, version),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = onCancel,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            ) {
                                Text(
                                    stringResource(R.string.cancel),
                                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp)
                                )
                            }

                            Button(
                                onClick = onInstall,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    stringResource(R.string.install),
                                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

class PatchInstallConfirmOverlay private constructor(private val context: Context) {
    private val tag = "PatchInstallConfirmOverlay"

    companion object {
        @Volatile
        private var instance: PatchInstallConfirmOverlay? = null

        fun getInstance(context: Context): PatchInstallConfirmOverlay {
            return instance ?: synchronized(this) {
                instance ?: PatchInstallConfirmOverlay(context.applicationContext).also { instance = it }
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var lifecycleOwner: ServiceLifecycleOwner? = null

    fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun requestOverlayPermission() {
        if (hasOverlayPermission()) return

        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context,
                    context.getString(R.string.overlay_permission_grant_hint),
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Error requesting overlay permission", e)
        }
    }

    fun show(apkFile: File, version: String, onInstall: () -> Unit) {
        if (overlayView != null) return

        if (!hasOverlayPermission()) {
            requestOverlayPermission()
            return
        }

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = android.graphics.PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
        }

        overlayView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                PatchInstallConfirmContent(
                    version = version,
                    onInstall = {
                        onInstall()
                        dismiss()
                    },
                    onCancel = {
                        dismiss()
                    }
                )
            }
        }

        lifecycleOwner = ServiceLifecycleOwner().apply {
            handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            handleLifecycleEvent(Lifecycle.Event.ON_START)
            handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        overlayView?.apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        }

        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            AppLogger.e(tag, "Error adding overlay view", e)
            dismiss()
        }
    }

    fun dismiss() {
        try {
            overlayView?.let { view ->
                lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

                windowManager?.removeView(view)
                overlayView = null
                lifecycleOwner = null
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Error dismissing overlay view", e)
        }
        windowManager = null
    }
}
