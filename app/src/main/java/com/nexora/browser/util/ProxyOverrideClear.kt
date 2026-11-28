package com.nexora.browser.util

/** The caller submits on the main thread and dispatches the WebView callback on the same thread. */
internal object ProxyOverrideClear {
    fun request(submit: (Runnable) -> Unit, complete: (Result<Unit>) -> Unit) {
        var submitted = false
        var acknowledged = false
        try {
            submit(Runnable {
                if (!acknowledged) {
                    acknowledged = true
                    if (submitted) complete(Result.success(Unit))
                }
            })
        } catch (error: Exception) {
            complete(Result.failure(error))
            return
        }
        submitted = true
        if (acknowledged) complete(Result.success(Unit))
    }
}
