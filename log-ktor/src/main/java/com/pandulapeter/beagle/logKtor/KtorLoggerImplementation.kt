package com.pandulapeter.beagle.logKtor

import com.pandulapeter.beagle.commonBase.BeagleNetworkLoggerContract
import io.ktor.client.features.logging.Logger

internal class KtorLoggerImplementation : BeagleNetworkLoggerContract {

    private var onNewLog: ((isOutgoing: Boolean, url: String, payload: String?, headers: List<String>?, duration: Long?, timestamp: Long) -> Unit)? = null
    private var clearLogs: (() -> Unit)? = null
    override val logger: Logger by lazy { NetworkInterceptor { onNewLog } }

    override fun logNetworkEvent(isOutgoing: Boolean, url: String, payload: String?, headers: List<String>?, duration: Long?, timestamp: Long) {
        onNewLog?.invoke(isOutgoing, url, payload, headers, duration, timestamp)
    }

    override fun clearNetworkLogs() {
        clearLogs?.invoke()
    }

    override fun register(onNewLog: (isOutgoing: Boolean, url: String, payload: String?, headers: List<String>?, duration: Long?, timestamp: Long) -> Unit, clearLogs: () -> Unit) {
        this.onNewLog = onNewLog
        this.clearLogs = clearLogs
    }
}