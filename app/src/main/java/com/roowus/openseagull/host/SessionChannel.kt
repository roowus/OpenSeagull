package com.roowus.openseagull.host

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.util.Log
import java.lang.reflect.Proxy

/**
 * An answer to OpenPigeon's session interface, built at runtime out of *their* interface class.
 *
 * ## The problem this solves
 *
 * Every game they ship reads its board through `GameSessionIPC`, which binds `.IGameSession` and
 * calls `getCurrentMessage(sessionId)`. `GameSessionBindProbe` measured where that lands and what
 * it costs: the intent carries a **hardcoded** `com.openbubbles.openpigeon` package id — read off
 * their dex, not inferred — so the bind leaves our process, succeeds (their service is
 * `exported="true"`), and then answers an id it has never seen with a Bundle of **0 keys**,
 * throwing nothing. A hosted board would open blank with no exception and no log line.
 *
 * So the session channel has to be answered here. [SeagullApplication] intercepts the bind; this
 * file is what it hands back.
 *
 * ## Why this is a [Proxy] and not a class
 *
 * `IGameSession` lives in their APK and is loaded through their ClassLoader, so it does not exist
 * at our compile time and cannot be named in our source — see [InstalledOpenPigeon] on per-loader
 * class identity. A [Proxy] built over the interface object works because [Proxy] only needs a
 * loader that can see the interface; the probe confirmed on-device that one built in *their* loader
 * is assignable to their `gameSession` field.
 *
 * ## Why no marshalling happens
 *
 * `IGameSession.Stub.asInterface(binder)` first asks `binder.queryLocalInterface(DESCRIPTOR)`, and
 * returns whatever comes back if it is an `IGameSession`. [binder] is a plain [Binder] with our
 * proxy attached under their descriptor, so their `asInterface` takes that branch and calls our
 * proxy **directly** — same process, same thread, no Parcel, no transaction. That matters for two
 * reasons beyond speed: their `IGameSession` methods are none of them `oneway`, so their code is
 * written expecting a blocking call and gets one; and an exception thrown here would surface inside
 * their activity rather than as a `RemoteException`, which is why nothing below is allowed to throw.
 *
 * ## Fail-soft is deliberate, and it is theirs
 *
 * An unknown session gets an empty Bundle rather than an error, because that is exactly what their
 * own service returns and their games already have a branch for it — most log
 * `"$id does not exist!"` and `finish()`. Being louder here would be a different contract than the
 * one their code was written against. Every fail-soft answer is logged, so the silence is ours to
 * see even though it is invisible to them.
 */
class SessionChannel private constructor(
    private val descriptor: String,
    private val iface: Class<*>,
) {

    /**
     * The binder handed to their `ServiceConnection`.
     *
     * `attachInterface` is what makes `queryLocalInterface` return our proxy: [Binder] stores the
     * owner and descriptor and hands the owner back on an exact descriptor match. The proxy
     * qualifies as the owner because every AIDL interface extends [IInterface], which comes from
     * the boot ClassLoader and is therefore the same type in both apps.
     */
    val binder: IBinder = Binder()

    private val session: Any = Proxy.newProxyInstance(
        iface.classLoader,
        arrayOf(iface),
    ) { _, method, args -> dispatch(method.name, args ?: emptyArray(), method.returnType) }

    init {
        (binder as Binder).attachInterface(session as IInterface, descriptor)
    }

    /**
     * Route one of their calls to [SessionRegistry].
     *
     * Dispatch is by name because the parameter types cannot be written down here — `Bundle` and
     * `String` are shared, but the two callback interfaces are theirs. Arity is checked alongside
     * the name so a signature change in a future OpenPigeon shows up as an unhandled call in the
     * log rather than as a wrong-argument crash inside their activity.
     */
    private fun dispatch(name: String, args: Array<Any?>, returnType: Class<*>): Any? {
        try {
            when {
                name == "getCurrentMessage" && args.size == 1 -> {
                    val id = args[0] as? String ?: return Bundle()
                    val message = SessionRegistry.message(id)
                    if (message.isEmpty()) {
                        // Their branch for this is `finish()`. Saying so here is the difference
                        // between a diagnosable miss and a board that closes for no stated reason.
                        Log.w(
                            TAG,
                            "getCurrentMessage(${id.take(8)}…) — no such session; their game will " +
                                "log \"does not exist\" and finish. Open sessions: " +
                                SessionRegistry.ids().joinToString { it.take(8) },
                        )
                    }
                    return message.toBundle()
                }

                name == "updateSession" && args.size == 3 -> {
                    val delta = (args[0] as? Bundle)?.toStringMap() ?: emptyMap()
                    val id = args[1] as? String ?: ""
                    val merged = SessionRegistry.update(id, delta)
                    // Their callback is invoked whether or not the update landed. It is a "you may
                    // continue" signal, not an acknowledgement, and their game hangs waiting for it
                    // if a dropped update skips it.
                    invokeQuietly(args[2], "onFinished")
                    if (merged != null) notify(id, merged)
                    return null
                }

                name == "setSuppressNotifications" && args.size == 2 -> {
                    SessionRegistry.setSuppressed(args[0] as? String ?: "", args[1] == true)
                    return null
                }

                name == "lockMsgHandle" && args.size == 1 -> {
                    SessionRegistry.lock(args[0] as? String ?: "")
                    return null
                }

                name == "unlockMsgHandle" && args.size == 1 -> {
                    SessionRegistry.unlock(args[0] as? String ?: "")
                    return null
                }

                name == "getSenderUUID" && args.size == 1 -> {
                    val id = args[0] as? String ?: return ""
                    val uuid = SessionRegistry.find(id)?.senderUuid ?: ""
                    if (uuid.isEmpty()) {
                        // Not a harmless blank. Their `isYourTurn` is `message["sender"] != myId`,
                        // so an empty id is unequal to every sender and the board reports "your
                        // turn" unconditionally. Measured out-of-process, their own service returns
                        // "" here too — which is why this failure has never announced itself.
                        Log.w(
                            TAG,
                            "getSenderUUID(${id.take(8)}…) is empty — turn detection will read " +
                                "\"your turn\" for every move until an identity is minted",
                        )
                    }
                    return uuid
                }

                name == "registerCallback" && args.size == 2 -> {
                    val id = args[0] as? String ?: return null
                    SessionRegistry.find(id)?.listener = args[1]
                    return null
                }

                name == "asBinder" && args.isEmpty() -> return binder

                // Proxy routes Object's methods through the handler too, and a game that logs the
                // interface would otherwise get a NullPointerException out of `toString`.
                name == "toString" && args.isEmpty() -> return "SessionChannel($descriptor)"
                name == "hashCode" && args.isEmpty() -> return System.identityHashCode(session)
                name == "equals" && args.size == 1 -> return args[0] === session
            }
        } catch (t: Throwable) {
            // Nothing may escape into their activity: this is a local call, so a throw here lands
            // as a raw exception where their code expects at worst a RemoteException, and Crazy8 and
            // Pool do not catch around the session read at all.
            Log.e(TAG, "session call '$name' failed — answering with a default", t)
            return defaultFor(returnType)
        }

        Log.w(TAG, "unhandled session call '$name'/${args.size} — their build may have moved")
        return defaultFor(returnType)
    }

    /**
     * Tell a registered listener the board changed.
     *
     * Reflective for the same reason everything else here is: `IMessageUpdatedCallback` is their
     * class. Failures are logged rather than propagated because this runs *after* their
     * `onFinished`, so their move has already been accepted and throwing now would fail a call that
     * has already succeeded.
     */
    private fun notify(id: String, message: Map<String, String>) {
        val listener = SessionRegistry.find(id)?.listener ?: return
        invokeQuietly(listener, "onMessageUpdated", Bundle::class.java, message.toBundle())
    }

    private fun invokeQuietly(
        target: Any?,
        method: String,
        paramType: Class<*>? = null,
        arg: Any? = null,
    ) {
        if (target == null) return
        try {
            if (paramType == null) {
                target.javaClass.getMethod(method).invoke(target)
            } else {
                target.javaClass.getMethod(method, paramType).invoke(target, arg)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "callback '$method' failed on ${target.javaClass.name}", t)
        }
    }

    /**
     * What to answer when we cannot answer properly.
     *
     * Mirrors their service's own empty-ish returns rather than null, because their code
     * dereferences these — `getCurrentMessage(...).toStringMap()` on a null Bundle is a
     * NullPointerException inside their activity, thrown by us.
     */
    private fun defaultFor(returnType: Class<*>): Any? = when (returnType) {
        Void.TYPE -> null
        Bundle::class.java -> Bundle()
        String::class.java -> ""
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        else -> null
    }

    /**
     * Their `GameSessionIPC.toStringMap()`, reproduced exactly.
     *
     * Their version is `keySet().mapNotNull { key -> getString(key)?.let { key to it } }`, so a
     * non-String extra is dropped without comment. Matching that here keeps the two directions
     * symmetric; putting a non-String in would produce a key their side silently loses.
     */
    private fun Bundle.toStringMap(): Map<String, String> =
        keySet().mapNotNull { key -> getString(key)?.let { key to it } }.toMap()

    private fun Map<String, String>.toBundle(): Bundle =
        Bundle().apply { forEach { (k, v) -> putString(k, v) } }

    companion object {
        private const val TAG = "SEAGULL"

        /**
         * Their interface's fully-qualified name.
         *
         * A string, not a type: naming it as a type would need the class at compile time, which is
         * the whole thing this project does not do. The dex carries `com.openbubbles.openpigeon` as
         * data here, never as a `Lcom/openbubbles/openpigeon/…;` type descriptor, which is what the
         * content-free gate looks for.
         */
        private const val IGameSession = "com.openbubbles.openpigeon.IGameSession"

        /**
         * Build a channel from the installed OpenPigeon, or `null` if its interface is not there.
         *
         * Null is a real state, not an error: OpenPigeon can be uninstalled while we run, and a
         * future build could move the interface. The caller falls back to letting the bind go where
         * it was addressed, which fails soft the way it does today rather than crashing.
         */
        fun of(pigeon: InstalledOpenPigeon): SessionChannel? {
            val iface = pigeon.loadClassOrNull(IGameSession) ?: run {
                Log.w(TAG, "$IGameSession is absent — cannot answer the session channel")
                return null
            }
            if (!iface.isInterface) {
                Log.w(TAG, "$IGameSession is not an interface — refusing to proxy it")
                return null
            }
            // Read the descriptor off their Stub rather than assuming it equals the class name.
            // It does in every AIDL build, but `asInterface` compares it as an exact string and a
            // mismatch would send their call down the marshalling path against a Binder with no
            // onTransact — which answers, silently, with nothing.
            val descriptor = descriptorOf(pigeon) ?: IGameSession
            return try {
                SessionChannel(descriptor, iface)
            } catch (t: Throwable) {
                Log.w(TAG, "could not build a session channel over $IGameSession", t)
                null
            }
        }

        private fun descriptorOf(pigeon: InstalledOpenPigeon): String? = try {
            pigeon.loadClassOrNull("$IGameSession\$Stub")
                ?.getDeclaredField("DESCRIPTOR")
                ?.apply { isAccessible = true }
                ?.get(null) as? String
        } catch (_: Throwable) {
            null
        }
    }
}
