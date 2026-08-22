package com.roowus.openseagull.host

import android.content.Context
import android.content.ContextWrapper

/**
 * A Context for OpenPigeon's code that answers `getApplicationContext()`.
 *
 * ## The hole this fills
 *
 * `createPackageContext(INCLUDE_CODE)` produces a Context that carries their classes and their
 * resources, and it is the right thing to hand their code — except in one respect. Its
 * `getApplicationContext()` returns **null**, because an application object is created by the
 * framework when it starts a process for a package, and nothing has ever started a process for
 * *their* package here. Their code runs in ours.
 *
 * That is not a hypothetical gap. It was measured: `PoolGame.getNewGameData` dies immediately with
 *
 * ```
 * java.lang.NullPointerException: getApplicationContext(...) must not be null
 *     at com.openbubbles.openpigeon.settings.SettingsData.init(SettingsData.kt:268)
 *     at com.openbubbles.openpigeon.settings.AvatarData.init(AvatarData.kt:42)
 *     at com.openbubbles.openpigeon.pool.PoolGame.getNewGameData(PoolGame.kt:109)
 * ```
 *
 * Their `SettingsData.init` does `appContext = context.applicationContext` and their Kotlin
 * signature declares it non-null, so the framework's null is rejected at the call boundary. Nothing
 * about this is specific to pool: any of their code that reads a setting goes through the same
 * initialiser, so every path into their game logic hits it.
 *
 * ## Why the delegate is *their* Context and not ours
 *
 * Everything except `getApplicationContext` is inherited from the wrapped Context, so their code
 * still gets their [ClassLoader], their [android.content.res.Resources], and their package name.
 * Substituting our Context would resolve their baked-in resource ids against our table — which
 * [InstalledOpenPigeon] documents does not fail loudly but returns an unrelated resource — and
 * would report our package name to code that has every right to expect its own.
 *
 * ## What this deliberately does not fix
 *
 * `getApplicationContext()` returning `this` is a small lie: a real application context outlives
 * any single component, and this one does not. It is the correct lie here because their settings
 * layer wants a long-lived Context to hold, and this wrapper is held for the life of our extension,
 * which is the longest-lived thing we have.
 *
 * It also does not give their code a writable data directory, and
 * `SendGameProbe.reportWhyTheirContextIsIncomplete` measured exactly where that lands rather than
 * leaving it to inference: the wrapper resolves storage against **their** directory
 * (`/data/user/0/com.openbubbles.openpigeon`), and a write there returns `false`.
 *
 * So their settings layer reads zero keys and runs on defaults, and any write it attempts is
 * dropped — `commit()` reports `false` and raises nothing, which their code does not check. That
 * is deliberate rather than merely tolerated: pointing the delegate at *our* directory would make
 * their writes succeed, but it would also hand their resource lookups our table and their package
 * queries our name, which fails silently and much worse. The fidelity gap this leaves — avatar and
 * per-game preferences not carrying over — is one to close by keeping our own copy, not by
 * borrowing theirs.
 */
class ForeignAppContext(base: Context) : ContextWrapper(base) {

    /**
     * Answers with itself.
     *
     * The single method that exists to be overridden. Their code stores the result and calls
     * `getSharedPreferences` on it, so returning `this` — rather than, say, our host application —
     * is what keeps their storage lookups pointed at their own Context.
     */
    override fun getApplicationContext(): Context = this
}
