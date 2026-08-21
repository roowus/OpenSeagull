package com.roowus.openseagull

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * The service OpenBubbles binds to.
 *
 * Registration in OpenBubbles' Developer Tools takes a fully-qualified class name and rebuilds the
 * component as `applicationId + "." + lastSegment` — so this class's **package must literally equal
 * the applicationId**, `com.roowus.openseagull`. It does, because this project owns its whole
 * namespace. (The old fork could not satisfy that: its code lived under
 * `com.openbubbles.openpigeon` while its applicationId had moved, and it needed a shim subclass
 * purely to be nameable. Shipping no OpenPigeon code removes the problem at the root.)
 *
 * Register by typing exactly:
 * ```
 * com.roowus.openseagull.MadridExtensionService
 * ```
 */
class MadridExtensionService : Service() {

    companion object {
        /**
         * Held across binds so game state survives OpenBubbles rebinding.
         *
         * `StaticFieldLeak` is suppressed for the same reason OpenPigeon suppresses it: the
         * extension holds the *Service* Context, whose lifetime is the process, so there is no
         * Activity to leak. It is cleared in [onDestroy] regardless.
         */
        @SuppressLint("StaticFieldLeak")
        var extension: MadridExtension? = null
    }

    override fun onBind(intent: Intent): IBinder {
        val existing = extension ?: MadridExtension(this).also { extension = it }
        return existing
    }

    override fun onDestroy() {
        super.onDestroy()
        extension = null
    }
}
