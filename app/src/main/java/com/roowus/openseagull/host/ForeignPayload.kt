package com.roowus.openseagull.host

import android.net.Uri
import android.util.Log
import androidx.core.net.toUri

/**
 * Turns the `url` of a received balloon back into the key/value board the game reads.
 *
 * ## What is actually in that field
 *
 * A GamePigeon balloon carries its entire game state as a single string, shaped
 * `data:?ver=52&data=<ciphertext>`. The ciphertext is a second query string — the board — encrypted
 * by OpenPigeon's `Cryption`. So decoding is: pull `data` out of the outer string, decrypt it, and
 * parse the plaintext as a query string in its own right.
 *
 * The two-layer shape is why this is not a one-liner, and the `data:` → `data://` rewrite is why it
 * is not obvious. `Uri.parse("data:?ver=52&…")` yields an **opaque** URI, whose
 * `getQueryParameter` throws `UnsupportedOperationException` rather than returning anything. Adding
 * the `//` makes it hierarchical, and only then does the query become readable. This mirrors what
 * OpenPigeon's own session code does, for the same reason.
 *
 * ## Why the decryption is reflective and cannot be otherwise
 *
 * `Cryption` is theirs. Reimplementing it here would mean copying their algorithm into our source,
 * which is exactly what this project does not do — so it is called where it already exists, in the
 * installed APK, through [InstalledOpenPigeon].
 *
 * It is a Kotlin `object` and `decrypt` carries no `@JvmStatic`, so there is no static shortcut:
 * the only handle is the compiler-generated `INSTANCE` field, read via [staticOrNull]. Touching
 * that field runs their `<clinit>`, which constructs the singleton — intended, and the reason
 * [staticOrNull] deliberately lets an initialiser failure through instead of flattening it to
 * "absent".
 *
 * ## What a failure means to the caller
 *
 * `null` from [decode] means *this balloon cannot be opened* — their class is gone, the field is
 * gone, the string is not shaped like a payload, or their decrypt refused it. Every one of those is
 * a reason not to launch a game, and the caller must not substitute an empty board for them: an
 * empty board is what their own code hands out when a session is missing, and it opens a **new**
 * game over the top of an existing one.
 *
 * A [ForeignCallException] is not caught here. Their decrypt throwing is their code failing on a
 * payload we handed it, which is worth surfacing at the call site as itself rather than as another
 * indistinguishable `null`.
 */
internal object ForeignPayload {

    private const val TAG = "SEAGULL"

    /**
     * Their encryption object's fully-qualified name.
     *
     * A string, not a type — same reasoning as [SessionChannel]'s interface name. The dex carries
     * `com.openbubbles.openpigeon` here as data, never as a type descriptor.
     */
    private const val Cryption = "com.openbubbles.openpigeon.Cryption"

    /**
     * Decode a balloon's `url` into its board, or `null` if it cannot be decoded.
     *
     * The per-key `try` around the inner parse matches their own loop: a single malformed value
     * costs that one key rather than the whole board, because a board missing one field still opens
     * the right game while a board that is entirely absent does not open at all.
     */
    fun decode(pigeon: InstalledOpenPigeon, url: String?): Map<String, String>? {
        if (url.isNullOrEmpty()) {
            Log.w(TAG, "balloon has no url — nothing to decode")
            return null
        }
        val outer = runCatching { url.replace("data:", "data://").toUri() }.getOrNull() ?: run {
            Log.w(TAG, "balloon url is not parseable as a URI (${url.length} chars)")
            return null
        }
        val ciphertext = queryOrNull(outer, "data") ?: run {
            Log.w(TAG, "balloon url carries no 'data' parameter (${url.length} chars)")
            return null
        }

        val plaintext = decrypt(pigeon, ciphertext) ?: run {
            Log.w(TAG, "their Cryption is unreachable or refused ${ciphertext.length} chars")
            return null
        }

        val inner = runCatching { "data://$plaintext".toUri() }.getOrNull() ?: run {
            Log.w(TAG, "decrypted payload is not parseable as a query string")
            return null
        }
        val names = runCatching { inner.queryParameterNames }.getOrNull().orEmpty()
        val board = LinkedHashMap<String, String>(names.size)
        for (key in names) {
            val value = queryOrNull(inner, key)
            if (value == null) {
                Log.w(TAG, "dropping unreadable key '$key' from the board")
                continue
            }
            board[key] = value
        }
        Log.i(TAG, "decoded balloon: ${board.size} keys, game=${board["game"] ?: "?"}")
        return board
    }

    /**
     * Call their `Cryption.decrypt`, or `null` if this build does not have it.
     *
     * Three separate absences collapse to `null` here — no class, no `INSTANCE`, no method — and
     * each is logged distinctly, because they mean different things about the installed build and
     * a single "decrypt failed" line would make them impossible to tell apart in a bug report.
     */
    private fun decrypt(pigeon: InstalledOpenPigeon, ciphertext: String): String? {
        val klass = pigeon.loadClassOrNull(Cryption) ?: run {
            Log.w(TAG, "$Cryption is absent from the installed build")
            return null
        }
        // Their object has no @JvmStatic on decrypt, so the singleton is the only receiver there
        // is. Reading INSTANCE constructs it.
        val instance = klass.staticOrNull("INSTANCE") ?: run {
            Log.w(TAG, "$Cryption has no INSTANCE — not the Kotlin object we expect")
            return null
        }
        val result = klass.invokeOrNull(
            instance,
            "decrypt",
            arrayOf(String::class.java),
            arrayOf<Any?>(ciphertext),
        )
        if (result == null) {
            Log.w(TAG, "$Cryption.decrypt is absent from the installed build")
        }
        return result as? String
    }

    /**
     * `getQueryParameter` without its two throwing modes.
     *
     * It raises `UnsupportedOperationException` on an opaque URI and can raise
     * `IllegalArgumentException` on a malformed escape in the value. Both mean "this key is not
     * readable", which is a value, not an event.
     */
    private fun queryOrNull(uri: Uri, key: String): String? =
        runCatching { uri.getQueryParameter(key) }.getOrNull()
}
