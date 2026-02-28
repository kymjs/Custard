package com.kymjs.ai.custard.core.tools.agent

import com.kymjs.ai.shower.IShowerService
import com.kymjs.ai.showerclient.ShowerBinderRegistry as CoreShowerBinderRegistry

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
