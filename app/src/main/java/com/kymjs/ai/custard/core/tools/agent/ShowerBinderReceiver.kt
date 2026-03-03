package com.kymjs.ai.custard.core.tools.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kymjs.ai.custard.util.AppLogger
import com.kymjs.ai.shower.IShowerService
import com.kymjs.ai.shower.ShowerBinderContainer

class ShowerBinderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SHOWER_BINDER_READY) {
            return
        }
        val container = intent.getParcelableExtra<ShowerBinderContainer>(EXTRA_BINDER_CONTAINER)
        val binder = container?.binder
        val service = binder?.let { IShowerService.Stub.asInterface(it) }
        val alive = service?.asBinder()?.isBinderAlive == true
        AppLogger.d(TAG, "onReceive: service=$service alive=$alive")
        ShowerBinderRegistry.setService(service)
    }

    companion object {
        private const val TAG = "ShowerBinderReceiver"
        const val ACTION_SHOWER_BINDER_READY = "com.kymjs.ai.custard.action.SHOWER_BINDER_READY"
        const val EXTRA_BINDER_CONTAINER = "binder_container"
    }
}
