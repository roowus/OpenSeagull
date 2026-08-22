package com.roowus.openseagull.host

import android.os.Parcel
import android.os.Parcelable
import com.bluebubbles.messaging.MadridMessage

/**
 * Carries an object built by OpenPigeon's code into one of our own classes.
 *
 * ## Why a copy through bytes is the only way across
 *
 * Their `buildGameMessage` returns `com.bluebubbles.messaging.MadridMessage`. So does ours — both
 * are generated from the same `.aidl`. But class identity is per-ClassLoader, so those two classes
 * are unrelated types at runtime and no cast between them exists. Measured on-device:
 * `assignable theirs->ours = false`. A cast throws `ClassCastException`; there is no flag or
 * `setAccessible` that changes this.
 *
 * What the two apps *do* share is the boot ClassLoader, and [Parcelable] comes from there. So both
 * sides genuinely agree on that one interface, even though they agree on nothing else. Their
 * instance can be asked to write its fields as bytes, and our `CREATOR` can read the same bytes
 * back. No type is ever shared, so no `ClassCastException` is possible.
 *
 * ## Why this is safe against version skew
 *
 * AIDL parcelables are size-prefixed: `readFromParcel` reads the block length first and
 * bounds-checks before each field. If the user's OpenPigeon was built from a newer `.aidl` than
 * ours, the extra trailing fields are skipped rather than misread — the read truncates instead of
 * corrupting. A field we know about that they do not send comes back as its default.
 *
 * That is the property that makes this reasonable to depend on rather than merely clever.
 */
internal object ParcelBridge {

    /**
     * Copy a foreign `MadridMessage` into ours.
     *
     * [theirs] must be a `Parcelable` whose `writeToParcel` emits the AIDL layout our `CREATOR`
     * expects — in practice, the return of one of their `buildGameMessage` calls.
     *
     * `setDataPosition(0)` between the write and the read is not optional. Writing leaves the
     * cursor at the end of the buffer, and reading from there yields a message with every field
     * null **and no error at all** — a balloon that sends successfully and arrives blank. The
     * failure mode is silent, which is why it gets a line of its own rather than being folded into
     * the expression.
     */
    fun toOurs(theirs: Parcelable): MadridMessage {
        val parcel = Parcel.obtain()
        return try {
            theirs.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            MadridMessage.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}
