package com.kymjs.ai.custard.ui.common.displays

import android.content.Context
import android.util.AttributeSet
import com.kymjs.ai.showerclient.ui.ShowerSurfaceView as CoreShowerSurfaceView

/**
 * SurfaceView used inside the virtual display overlay to render the Shower video stream.
 */
class ShowerSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : CoreShowerSurfaceView(context, attrs)
