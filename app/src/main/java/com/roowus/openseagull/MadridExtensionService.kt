package com.roowus.openseagull

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

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

    /**
     * Logged because a successful bind is otherwise invisible from outside the process.
     *
     * Registering in Developer Tools produces no user-visible confirmation, and a bind leaves no
     * lasting trace in `dumpsys activity services` — the host unbinds as soon as it has what it
     * needs, so polling for a live ServiceRecord reports "nothing" for both "never registered" and
     * "registered and working". This line is the difference between those two, and it is why the
     * tag is greppable: `adb logcat -s SEAGULL:I`.
     */
    override fun onBind(intent: Intent): IBinder {
        val reused = extension != null
        val existing = extension ?: MadridExtension(this).also { extension = it }
        Log.i("SEAGULL", "onBind from OpenBubbles — action=${intent.action} reusedExtension=$reused")
        return existing
    }

    override fun onDestroy() {
        super.onDestroy()
        extension?.release()
        extension = null
    }
}
