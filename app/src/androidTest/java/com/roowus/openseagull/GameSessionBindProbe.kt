package com.roowus.openseagull

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.roowus.openseagull.host.InstalledOpenPigeon
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test

/**
 * Measures what a game activity hosted in *our* process would actually get from OpenPigeon's
 * session channel — the obstacle that decides the shape of the balloon-tap half.
 *
 * ## Why this is not a design question that can be reasoned out
 *
 * Their `GameSessionIPC` is what every game — native and Godot — uses to read the message it is
 * meant to display. Disassembling the installed APK settled how it finds the service, and the
 * answer was the unfavourable one:
 *
 * ```
 * const-string v1, ".IGameSession"
 * invoke-direct {v0, v1}, Landroid/content/Intent;-><init>(Ljava/lang/String;)V
 * const-string v1, "com.openbubbles.openpigeon"
 * invoke-virtual {v0, v1}, Landroid/content/Intent;->setPackage(Ljava/lang/String;)…
 * ```
 *
 * A hardcoded package id, not `context.getPackageName()`. So their code, running in our process,
 * binds to **real OpenPigeon's** `GameSessionService`. And that service is `exported="true"`, so
 * the bind is expected to *succeed* rather than fail — which is worse, because every method on it
 * keys on `MadridExtension.activeSessions[id]`, a static map living in their process. For a session
 * we created, that lookup misses and the method returns an empty value: `getCurrentMessage` → an
 * empty `Bundle`, `getSenderUUID` → `""`, the rest → a silent `return`.
 *
 * That is a failure with **no exception and no log line on our side**. A hosted board would open
 * blank and leave nothing to debug. Everything in the paragraph above is read off their source and
 * their bytecode; this file exists to confirm it happens rather than to assume it does, because a
 * design built on a misread of a silent failure mode is the expensive kind of wrong.
 *
 * ## Why the checks are reflective
 *
 * Talking to `IGameSession` normally means shipping `IGameSession.aidl` to generate a `Stub`. That
 * file would have to declare `package com.openbubbles.openpigeon`, which
 * `WireContractTest.shippedSourceDeclaresNoOpenPigeonPackage` fails the build over, and the built
 * dex is grepped for `Lcom/openbubbles/openpigeon/` in CI besides. Both gates are the point of this
 * project, not obstacles to route around.
 *
 * So the binder is driven through *their* `IGameSession$Stub`, loaded from *their* ClassLoader and
 * called reflectively. That is not a workaround for the gate — it is the same mechanism everything
 * else here uses, and it means this probe exercises the identical path production would.
 *
 * Naming their classes is legal in `src/main`'s absence: the content-free gate scopes to `src/main`,
 * and androidTest ships in a separate APK that is never released.
 *
 * ## Measured verdicts (emulator-5554, API 36, OpenPigeon 1.1.0 / 26081901)
 *
 * Both readings above are confirmed, and both in the unfavourable direction:
 *
 * - `resolveService` → `com.openbubbles.openpigeon.godot.GameSessionService exported=true`;
 *   `bindService` → `true`; `onServiceConnected` fires with an interface descriptor of
 *   `com.openbubbles.openpigeon.IGameSession`. **The bind succeeds**, so there is no loud failure
 *   to catch.
 * - `getCurrentMessage(unknown id)` → a Bundle of **0 keys**, `threw=nothing`.
 *   `getSenderUUID(unknown id)` → `''`, `threw=nothing`. A hosted board really would open blank
 *   with no exception and nothing in the log to explain it.
 * - The seam is open: `gameSession` is `final=false` and typed
 *   `com.openbubbles.openpigeon.IGameSession`, and a [java.lang.reflect.Proxy] built in *their*
 *   loader (`$Proxy5`) is assignable to it.
 *
 * ## Why the seam this file measures is not the one production uses
 *
 * The field dump reads like an invitation to swap `gameSession` after their bind lands —
 * `GameSessionIPC` holds `connection` (final) and an `onBind` `Function1`, and their callback is
 * what assigns the field, so the obvious plan is "wait for `onBind`, then overwrite". That plan is
 * **wrong**, and reading all six of their `GameSessionIPC` call sites is what settled it: Pool,
 * Golf, Crazy8, WordHunt, Knockout and Godot each assign the handle and call
 * `getCurrentMessage(sessionId)` in the **next statement**. The read is synchronous, so there is no
 * window after `onServiceConnected` in which a field could be swapped before it is used. (WordHunt's
 * `ipcReady@` label looks like a deferral and is not — it exists so early `return@ipcReady`s can
 * leave the lambda.)
 *
 * What is left is the bind itself. All six sites pass `applicationContext` to the constructor, and
 * in our process that is our own `Application` — so `SeagullApplication` overrides `bindService`,
 * answers `.IGameSession` with `host.SessionChannel`, and the call never reaches the framework.
 * The hardcoded package id above stops mattering, and their re-entrant `initGameSession`
 * (`onCreate` *and* `onNewIntent`, fresh IPC each time) is handled for free.
 *
 * The measurements below still stand and are still worth keeping: they are what prove the bind
 * must be intercepted at all, and the proxy-assignability result is what proves a proxy of ours is
 * a legal `IGameSession` in their loader.
 *
 * ```
 * adb logcat -d -s SEAGULL:I
 * ```
 */
class GameSessionBindProbe {

    private val tag = "SEAGULL"

    private fun ctx(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun pigeonOrSkip(): InstalledOpenPigeon? {
        val p = InstalledOpenPigeon.find(ctx())
        if (p == null) Log.i(tag, "SKIP: OpenPigeon is not installed on this device")
        return p
    }

    /**
     * Does binding their session service from our process succeed, and what does it hand back for a
     * session it has never heard of?
     *
     * Both halves matter and they point opposite ways. A bind that *failed* would be a loud,
     * debuggable error — the good outcome. A bind that succeeds and then answers with emptiness is
     * the silent one, and it is what their manifest (`exported="true"`) predicts.
     *
     * The session id is deliberately fabricated. That is exactly the state a hosted activity would
     * be in: our session registry would hold the id, theirs would not, and nothing anywhere would
     * say so.
     */
    @Test
    fun bindingTheirSessionServiceSucceedsAndReturnsNothing() {
        val p = pigeonOrSkip() ?: return

        val intent = Intent(".IGameSession").apply { setPackage(p.packageName) }
        // Whether the framework can even see the service is a separate question from whether the
        // bind is permitted, and resolving first separates "invisible to <queries>" from "refused".
        val resolved = ctx().packageManager.resolveService(intent, 0)
        Log.i(
            tag,
            "resolveService(.IGameSession, ${p.packageName}) -> " +
                (resolved?.serviceInfo?.let { "${it.name} exported=${it.exported}" } ?: "null"),
        )

        val latch = CountDownLatch(1)
        var bound: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                bound = service
                Log.i(tag, "onServiceConnected: $name binder=$service")
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.i(tag, "onServiceDisconnected: $name")
            }
        }

        val requested = ctx().bindService(intent, connection, Context.BIND_AUTO_CREATE)
        Log.i(tag, "bindService returned $requested (false = refused outright)")
        if (!requested) {
            Log.i(tag, "VERDICT: the bind is refused — a hosted game would fail loudly, not blankly")
            return
        }

        try {
            val connectedInTime = latch.await(BindTimeoutSeconds, TimeUnit.SECONDS)
            val binder = bound
            Log.i(
                tag,
                "connected=$connectedInTime binder=${binder != null} " +
                    "interface=${binder?.interfaceDescriptor}",
            )
            if (binder == null) {
                Log.i(tag, "VERDICT: bind accepted but never connected within ${BindTimeoutSeconds}s")
                return
            }

            // Their binder speaks their AIDL, so their Stub is what knows how to marshal a call to
            // it. Loading it from their loader and calling through reflection is the only route that
            // does not require shipping a class in their package.
            val stub = p.loadClassOrNull("com.openbubbles.openpigeon.IGameSession\$Stub")
            if (stub == null) {
                Log.i(tag, "their IGameSession\$Stub is absent — cannot drive the binder")
                return
            }
            val session = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            Log.i(tag, "asInterface -> ${session?.javaClass?.name}")
            if (session == null) return

            // The fabricated id is the whole point: this is precisely the state a session created by
            // OpenSeagull would be in from their side.
            val fabricated = "seagull-probe-session-that-does-not-exist"
            val result = runCatching {
                session.javaClass
                    .getMethod("getCurrentMessage", String::class.java)
                    .invoke(session, fabricated) as? Bundle
            }
            val thrown = result.exceptionOrNull()
            val message = result.getOrNull()
            Log.i(
                tag,
                "getCurrentMessage(unknown id) -> " +
                    "bundle=${message?.let { "${it.size()} keys" } ?: "null"} " +
                    "threw=${thrown?.let { (it.cause ?: it).javaClass.simpleName } ?: "nothing"}",
            )

            // `getSenderUUID` is read as well because it is the one that returns a *plausible* value
            // rather than an obviously-empty container. An empty string flows onward as an identity
            // and misroutes a move rather than failing where it happened.
            val uuid = runCatching {
                session.javaClass
                    .getMethod("getSenderUUID", String::class.java)
                    .invoke(session, fabricated) as? String
            }
            Log.i(
                tag,
                "getSenderUUID(unknown id) -> '${uuid.getOrNull()}' " +
                    "threw=${uuid.exceptionOrNull()?.let { (it.cause ?: it).javaClass.simpleName } ?: "nothing"}",
            )

            Log.i(
                tag,
                when {
                    thrown != null ->
                        "VERDICT: their service rejects an unknown session loudly — hosting can " +
                            "detect the miss"
                    message != null && message.isEmpty ->
                        "VERDICT: bind succeeds and answers with an empty Bundle and no error — a " +
                            "hosted game opens blank with zero diagnostics, exactly as their " +
                            "source predicts. Their IPC cannot be left pointed at their process."
                    else ->
                        "VERDICT: unexpected — got a non-empty answer for a session they never saw"
                },
            )
        } finally {
            runCatching { ctx().unbindService(connection) }
        }
    }

    /**
     * Is `GameSessionIPC`'s binder handle reachable, and can a substitute be put in it?
     *
     * This was written as the escape hatch for the test above: their `GameSessionIPC` holds
     * `private var gameSession: IGameSession?` and every method on it does `gameSession!!.<method>`,
     * so overwriting that field would send every call to us. Production ended up intercepting the
     * bind instead, for the reason in the file header — the read is synchronous at all six sites, so
     * there is no moment between the assignment and the first use.
     *
     * It is kept because its second half is load-bearing regardless of which seam is used: it is
     * what proves a [java.lang.reflect.Proxy] of ours is a legal `IGameSession` **in their loader**,
     * which `SessionChannel` depends on completely. The field half is now a canary — if
     * `gameSession` ever turns final or changes type, that is a signal their IPC has been reworked
     * and the interception assumptions deserve a re-read.
     *
     * Two things have to be true, and neither is obvious:
     *
     * - the field is still there, still that type, and not final;
     * - a [java.lang.reflect.Proxy] built over **their** `IGameSession` interface is assignable to
     *   it. `Proxy` needs a loader that can see the interface, and the proxy class it generates is
     *   defined in that loader — so this is really asking whether their loader will accept a
     *   generated class, which is not something to take on faith.
     *
     * Nothing is injected here. This measures whether the seam exists; using it is a later step.
     */
    @Test
    fun theirSessionHandleIsReplaceable() {
        val p = pigeonOrSkip() ?: return

        val ipc = p.loadClassOrNull("com.openbubbles.openpigeon.godot.GameSessionIPC")
        if (ipc == null) {
            Log.i(tag, "GameSessionIPC is absent from this build — the seam may have moved")
            return
        }

        ipc.declaredFields.forEach { f ->
            Log.i(
                tag,
                "  field ${f.name}: ${f.type.name} " +
                    "final=${Modifier.isFinal(f.modifiers)} static=${Modifier.isStatic(f.modifiers)}",
            )
        }
        ipc.declaredConstructors.forEach { c ->
            Log.i(tag, "  ctor(${c.parameterTypes.joinToString { it.name }})")
        }

        val handle = ipc.declaredFields.firstOrNull { it.name == "gameSession" }
        if (handle == null) {
            Log.i(tag, "VERDICT: no 'gameSession' field — the injection point named in the plan is gone")
            return
        }
        Log.i(
            tag,
            "handle field: ${handle.type.name} final=${Modifier.isFinal(handle.modifiers)}",
        )

        // Can a proxy of their interface be built at all? Their loader has to accept a class it did
        // not load, which is the part worth measuring rather than assuming.
        val iface = p.loadClassOrNull("com.openbubbles.openpigeon.IGameSession")
        if (iface == null) {
            Log.i(tag, "VERDICT: their IGameSession interface is absent — no proxy is possible")
            return
        }
        Log.i(tag, "IGameSession isInterface=${iface.isInterface} methods=${iface.methods.size}")
        iface.methods.forEach { m ->
            Log.i(tag, "  ${m.name}(${m.parameterTypes.joinToString { it.simpleName }}): ${m.returnType.simpleName}")
        }

        val proxy = runCatching {
            java.lang.reflect.Proxy.newProxyInstance(
                iface.classLoader,
                arrayOf(iface),
            ) { _, method, _ ->
                // Never invoked here; this only has to be a well-formed handler for the proxy class
                // to be generated. Returning a default keeps the shape honest if it ever is.
                Log.i(tag, "proxy received ${method.name}")
                when (method.returnType) {
                    Void.TYPE -> null
                    String::class.java -> ""
                    Bundle::class.java -> Bundle()
                    else -> null
                }
            }
        }
        val built = proxy.getOrNull()
        Log.i(
            tag,
            "proxy over their IGameSession -> ${built?.javaClass?.name ?: "FAILED"} " +
                "err=${proxy.exceptionOrNull()?.javaClass?.simpleName ?: "none"}",
        )

        val assignable = built != null && handle.type.isInstance(built)
        Log.i(
            tag,
            when {
                built == null ->
                    "VERDICT: no proxy can be built — the handle cannot be replaced this way"
                assignable ->
                    "VERDICT: the seam is open — a proxy of ours fits their 'gameSession' field, so " +
                        "a hosted game's session calls can be answered by OpenSeagull"
                else ->
                    "VERDICT: proxy built but is not assignable to ${handle.type.name} — the field " +
                        "would reject it"
            },
        )
    }

    private companion object {
        /**
         * Generous because a cold bind starts their process. Being wrong in the short direction
         * would report "never connected" for a bind that works, which is the one wrong answer that
         * would send the design down a different road.
         */
        const val BindTimeoutSeconds = 10L
    }
}
