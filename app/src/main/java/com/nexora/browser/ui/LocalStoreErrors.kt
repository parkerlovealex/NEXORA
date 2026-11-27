package com.nexora.browser.ui

import android.app.Activity
import android.widget.Toast
import com.nexora.browser.data.LocalStoreReadException

internal inline fun <T> Activity.readLocalStore(read: () -> T): Result<T> = try {
    Result.success(read())
} catch (error: LocalStoreReadException) {
    Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
    Result.failure(error)
}
