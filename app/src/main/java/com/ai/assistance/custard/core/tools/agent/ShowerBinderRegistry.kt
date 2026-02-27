package com.ai.assistance.custard.core.tools.agent

import com.ai.assistance.shower.IShowerService
import com.ai.assistance.showerclient.ShowerBinderRegistry as CoreShowerBinderRegistry

/**
 * App-level facade over the shared Shower client binder registry.
 */
object ShowerBinderRegistry {

    fun setService(newService: IShowerService?) {
        CoreShowerBinderRegistry.setService(newService)
    }

    fun getService(): IShowerService? = CoreShowerBinderRegistry.getService()

    fun hasAliveService(): Boolean = CoreShowerBinderRegistry.hasAliveService()
}
