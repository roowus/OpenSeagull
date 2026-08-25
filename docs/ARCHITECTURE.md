# OpenSeagull Architecture

This is the map of how OpenSeagull works. Every claim here is enforced by structure in the
code and was measured on-device; where the code carries more detail than this file, the file
wins. **This document must be updated whenever a behavioural change lands** — see
[MAINTAINING-DOCS.md](MAINTAINING-DOCS.md).

The one-sentence version: OpenSeagull is an empty APK that loads the user's installed OpenPigeon
as a runtime library — its classes onto our ClassLoader, its native libraries into our linker
namespace, its resources into our resource tables — so that OpenBubbles can be given a game
extension whose every feature is borrowed live from another app.

## 0. The three laws

Everything else follows from these. They are not conventions; each has a test or a gate:

1. **Ship no OpenPigeon code.** Nothing in `src/main` may declare an
   `com.openbubbles.openpigeon` package — `WireContractTest.shippedSourceDeclaresNoOpenPigeonPackage`
   greps for it on every push. This is the project's legal position (their asset tree may not be
   redistributed) and the reason several "obvious" designs are impossible here.
2. **No compile-time reference to their types.** A consequence of law 1: their classes load at
   runtime through `ForeignCode`, so every interop call is reflective (`Reflect.kt`) and every
   foreign class name appears only as a string. A wrapper subclass like
   `class HostedPoolActivity : PoolActivity()` cannot even compile.
3. **Class identity is per-ClassLoader; there is no casting, ever.** Their `MadridMessage` and
   ours are unrelated types at runtime despite sharing a name. No typed field ever holds a
   foreign instance; crossing back into our types goes through `ParcelBridge`.

## 1. Process layout

```
┌─────────────────────────────── OpenBubbles (host) ───────────────────────────────┐
│  Flutter UI · renders our RemoteViews balloons/picker · binds MadridExtensionService │
└───────────────▲──────────────────────────────────────────────────┬────────────────┘
                │ AIDL (IMadridExtension, IKeyboardHandle,          │ bind
                │ IMessageViewHandle, IViewUpdateCallback)          ▼
┌───────────────┴───────────── OpenSeagull (main process) ─────────────────────────┐
│ SeagullApplication ─ BackGuard · SeagullIdentity.attach · (<API 28) installDex    │
│ MadridExtensionService → MadridExtension (picker, balloon render, tap routing)    │
│ SessionRegistry ← SessionChannel (answers their GameSessionIPC bind locally)      │
│ ForeignCode / InstalledOpenPigeon / ForeignGameCatalog / ForeignPayload           │
│ HostedComponentFactory (API 28+): per-component dex+native+resources prep         │
└───────────────────────────────┬───────────────────────────────────────────────────┘
                                │ Intent(context, their.gameClass())
┌───────────────────────────────▼──────── :godot process (Godot games only) ───────┐
│ GodotGameActivity runs here, still OUR process, still reading SessionRegistry     │
└───────────────────────────────────────────────────────────────────────────────────┘
┌────────────── OpenPigeon (separate app, never modified, never entered) ───────────┐
│  read-only: base.apk mmap'd (dex, lib/<abi>/*.so), resources via addAssetPath     │
└───────────────────────────────────────────────────────────────────────────────────┘
```

Nothing is injected into OpenPigeon's process (impossible without root). Their code is loaded
into ours.

## 2. Startup — `SeagullApplication.onCreate`

Runs in **every** process we own (main and `:godot`), in this order:

1. `SeagullIdentity.attach(this)` — hands over a Context for identity minting. Must run before
   anything can ask for a sender id.
2. `BackGuard.attach(this)` — registers the lifecycle callbacks that give hosted games the
   two-step back exit ([§8](#8-backguard--two-step-exit-from-hosted-games)). Runs before the API-28
   early return because `:godot` has no other call site of ours.
3. On API < 28 only: `ForeignCode.installDex`. Above 28, `HostedComponentFactory` does this work
   per component instead (cheaper: measured 619 ms cold-start cost when done eagerly).

## 3. Hosting pipeline — from installed app to running game

Four gates, each with a named failure that used to end launches silently:

| Gate | What it does | Where | Failure it prevents |
|---|---|---|---|
| Manifest | `<activity android:name="com.openbubbles.openpigeon.…">` entries — legal because `android:name` resolves through *our* ClassLoader at launch time | `AndroidManifest.xml` | `ActivityNotFoundException` (framework resolves against the installed manifest before any code loads) |
| Dex | Append their APK to our `dexElements` by stealing elements from a throwaway `PathClassLoader` (version-tolerant — avoids `makePathElements`, whose signature churns) | `ForeignCode.installDex` | `ClassNotFoundException` inside `ActivityThread` |
| Native | Append `<apk>!/lib/<abi>` to both `nativeLibraryDirectories` *and* `nativeLibraryPathElements` (writing only the first is the classic silent no-op); then `loadLibrary` follows missing-soname linker errors, preloading dependencies by explicit in-APK path until the chain resolves | `ForeignCode.installNativePath` / `.loadLibrary` | `UnsatisfiedLinkError` in their `<clinit>` — which runs during `Class.newInstance`, i.e. before any overridable hook |
| Resources | Sweep every live `ResourcesImpl` via `ResourcesManager.mResourceImpls` and `addAssetPath` their APK into each AssetManager; verified through `Resources.getResourceName(canary)` | `ForeignCode.installResourcesEverywhere` | `Resources$NotFoundException: Resource ID #0x7f0c001d` on their `setContentView` |

Key facts that make this work:

- Our resource table lives at **package id 0x80** (`--package-id 0x80 --allow-reserved-package-id`
  in `app/build.gradle.kts`), theirs at 0x7f. Measured 0/379 id collisions either merge order;
  at the default 0x7f it was 58 — silent wrong images, no exceptions.
- `HostedComponentFactory.instantiateActivity` (manifest attr `android:appComponentFactory`,
  tools:replace against androidx's CoreComponentFactory) triggers all three injections the instant
  before the framework builds one of their activities. Idempotent and cached; a process that opens
  no game pays nothing.
- ABI choice reads their APK's actual `lib/` directories rather than trusting `SUPPORTED_ABIS`.

## 4. The extension contract — talking to OpenBubbles

`MadridExtensionService` (must live literally at `applicationId + ".MadridExtensionService"`
because the host rebuilds registered components that way) binds to `MadridExtension`, which
implements the six methods of `IMadridExtension` (AIDL copies in `src/main/aidl/com/bluebubbles/`,
Apache-2.0 from the OpenBubbles repo):

- `keyboardOpened` → paginated `RemoteViews` picker grid (`GamePicker` + `Pagination` + `Posters`).
  Posters must travel as **bitmaps**: ids would resolve against the wrong table in the host's
  process. Capped at 192 px long edge; page cost logged in KiB.
- `launchGame` (via `PickerActionReceiver`, since RemoteViews taps come back as PendingIntents)
  → compose off-thread: `getNewGameData` → `stampIdentity` → `buildGameMessage` →
  `ParcelBridge.toOurs` → `handle.addMessage`.
- `getLiveView` → draw a received balloon with their exact geometry (250 dp board area,
  46/58 dp caption lines), preview bitmap if the game draws boards else poster, win glyph from
  `BoardVerdict`, and a baked-in `setOnClickPendingIntent` pointing at `BalloonTapActivity`
  (the contract's own `didTapTemplate` delivered 1-in-12 taps against a real host).
- `didTapTemplate` → decode, register session, launch the game activity in our process.
- `messageUpdated` → replace (not merge — it's a whole new board) an already-open session.
- Tap trampoline `BalloonTapActivity`: promotes the render-time handle, registers the session
  *before* the activity's `onCreate` binds (~1.2 s race measured), starts the activity.

Wire facts pinned by `WireContractTest`: `madrid_id=1124197642` (the only routing key real iOS
balloons carry), `madrid_name="GamePigeon"` (rendered on the recipient's iPhone),
`madrid_bundle_id` = Apple's extension id.

## 5. Payload path — what's inside a balloon

A balloon's `url` is `data:?ver=N&data=<ciphertext>` where the ciphertext decrypts (through *their*
`Cryption`, reflectively — reimplementing it would copy their algorithm) to a second query string:
the board, a flat `String→String` map. `ForeignPayload.decode` handles the `data:` → `data://`
rewrite (opaque URIs throw on query access), per-key fault isolation, and logs four distinct
failure shapes.

`readBalloon` (shared by tap, render, and PendingIntent paths — one definition, so they can't
disagree about what a balloon is) decodes, resolves the game via `ForeignGameCatalog.byName`
(with their alias map: wire name `pool3` → game `pool`, produced by every 8 Ball+ balloon),
and refuses unsupported payloads with a plain-language dialog (`UnsupportedGameActivity` — ours,
not their Mixpanel-firing `GameNotFound`).

## 6. Sessions — how a hosted game reads and writes its board

Their games read boards via `GameSessionIPC` binding `.IGameSession` at a **hardcoded** package
id. Left alone, the bind succeeds out-of-process and answers every unknown session with an empty
Bundle — silent blank boards.

- `SeagullApplication.bindService` intercepts that action and answers it locally.
- `SessionChannel` is the answer: a `java.lang.reflect.Proxy` over *their*
  `IGameSession` interface class, built on **our** loader (after installDex their dex is on ours;
  the wrong loader makes their `asInterface`'s `instanceof` quietly fail and every call marshal
  into a binder that answers nothing — the crash that taught this). Dispatch is by method name +
  arity; nothing may throw into their activities.
- `SessionRegistry` is the memory behind it: open sessions, merged move deltas, lock depth
  (counted, not flagged — re-entrant opens), the host's `IMessageViewHandle` per session, and
  `pendingHandles` stashing render-time handles for balloons nobody tapped yet.
- `SessionWriter` is write-back: after each move, a single daemon thread enriches the board
  (`player1` claim + caption via their `getSubtitle`), rebuilds the message through their
  `buildGameMessage` (session non-null ⇒ move, not invitation), and calls
  `handle.updateMessage` off the game's thread (not `oneway`; blocking inline stalls their UI).
  Every way a write can fail without happening is logged — a lost move that logs nothing is
  indistinguishable from a game that never made one.
- Lock/unlock reach the host only on the depth counter's 0↔1 transitions.

## 7. Identity — why we mint our own UUID

Their `getSenderUUID(context)` reads prefs we cannot read cross-uid, so it mints a fresh random
UUID **every process** while looking perfectly healthy. Four runs, four players. Turn detection
(`isYourTurn = message["sender"] != myId`) needs an id that outlives the turn, so
`SeagullIdentity` mints once into our own prefs (`commit()`, not `apply()` — `:godot` shares the
file across processes). Three places apply it instead of theirs:

- send-side: `stampIdentity` overwrites `sender` and `player2` on new games;
- read-side: `BoardVerdict` reimplements their turn/winner/spectator rules evaluated against our id
  (delegating would produce randomly alternating "Your Move."/"Opponent's Move.");
- write-back side: `SessionWriter.enrich` claims `player1` with our id.

Cost, stated honestly: a game started in OpenPigeon proper and continued here reads as a
different player taking over. That is correct behaviour for a separate install.

## 8. BackGuard — two-step exit from hosted games

Their activities finish on the first back gesture; under gesture navigation that gesture fires
accidentally mid-game. Leaving is now two-step: first press shows "Press back again to leave",
second within 2 s exits as before.

Design constraints that shaped it (a per-game wrapper-subclass design was tried and deleted —
it violates law 2):

- `Application.ActivityLifecycleCallbacks` sees every activity built in our processes — including
  `:godot` and future games — filtered by class-name prefix, not a hand-maintained list.
- For each hosted activity, its `Window.Callback` is wrapped in a delegating shell
  (`Window.Callback by inner`) that intercepts exactly `KEYCODE_BACK` on ACTION_UP, uncanceled.
  Registered after `onCreate` returns, so Compose dispatchers etc. stay innermost and untouched.
- Plain platform API only: their activities extend `android.app.Activity`, so androidx
  `OnBackPressedDispatcher` never applied. Key events cover API 26+ on one path; if the app ever
  opts into predictive back, an `OnBackInvokedCallback` must be added alongside.
- Dialogs consume back in their own windows and bypass the guard — intended: the guard is for
  gestures aimed at the game.

## 9. Catalog & reflection safety net

`ForeignGameCatalog` prefers reading their registry (`MadridExtension.Companion.getGames()`)
so new upstream games appear without an update, falls back to two long-lived known classes, and
records which strategy ran (diagnostics distinguishes "no games" from "we failed to read"). The
picker additionally sorts alphabetically and deliberately does *not* inherit OpenPigeon's hiding
of `hunt`/`anagrams`/`wordbites`.

`Reflect.kt` keeps reflection honest: absent method ⇒ `null` (compatibility outcome); throwing
callee ⇒ `ForeignCallException` preserving the cause (never flattened into absence). Primitives
need explicit `Int::class.javaPrimitiveType!!` params — inference fails silently there.

`ForeignGame` wraps one opaque instance; every accessor nullable-by-default so a version-skewed
method degrades to a missing label, never a crash. Calls that reach their settings layer get
`ForeignAppContext` (a ContextWrapper answering `getApplicationContext()` with itself — the raw
package Context returns null there and NPEs inside `SettingsData.init`).

## 10. Testing

- **JVM (`testDebugUnitTest`, gates CI):** `WireContractTest` pins the manifest routing values and
  enforces the content-free rule; `PaginationTest` covers the paging arithmetic whose bugs are
  otherwise unreachable pages with no error.
- **Instrumented (`connectedAndroidTest`, needs device + OpenPigeon):** ten probes, each measuring
  one architectural claim — `RuntimeHostProbe` (loader isolation), `ForeignCodeProbe`,
  `GameplayFeasibilityProbe` (native chain), `HostedActivityProbe` (activity to RESUMED),
  `HostedSessionProbe` (board data actually reaches their replay code — the oracle is *their*
  log line, not our own map), `SendGameProbe` (compose + parcel bridge round-trip),
  `PickerRenderProbe`, `GameSessionBindProbe`, `ForeignIdentityProbe`, `UnsupportedGameProbe`.
  Suite skips (not fails) when OpenPigeon is absent.
- `ForeignResourcesReport` runs from `DiagnosticsActivity` in a **non-instrumented** launch
  because `am instrument --no-hidden-api-checks` lifts exactly the greylist restriction being
  tested — a measurement taken with the obstacle removed proves nothing about production.
- Read probe output: `adb logcat -d -s SEAGULL:I`.

Known verification gap (from README): dev-device signatures match, so the cross-signature path is
confirmed only for `createPackageContext(INCLUDE_CODE)` itself, not the full suite.

## 11. Known limits

- Configuration UIs unbuilt: configurable games (17 of 26) are sent with default settings, logged.
- Identity does not carry over from OpenPigeon proper (sandbox; deliberate).
- Their settings layer runs on defaults — storage writes into their directory fail (`false`),
  which their code ignores. Fix belongs in keeping our own copy, not borrowing theirs.
- Cross-version survival of payload handling is verified structurally, not against future releases.
