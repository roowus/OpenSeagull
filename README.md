# OpenSeagull

An OpenBubbles extension that adds features to [OpenPigeon] by hosting it at runtime, rather than
replacing or forking it.

OpenSeagull ships **no OpenPigeon code, artwork, or game data**. It reads what it needs from the
copy of OpenPigeon already installed on your device. Both apps stay installed, side by side, and
OpenPigeon is never modified.

> **Status: early.** The runtime-hosting architecture is proven on-device — foreign classes load,
> their games enumerate, their resources and player identity resolve correctly. Gameplay is not
> wired up yet. Today the app is a diagnostics screen and a registerable extension.

[OpenPigeon]: https://github.com/OpenBubbles/OpenPigeon

## Why this exists

OpenPigeon is a GamePigeon-compatible extension for OpenBubbles. Modding it normally means forking
it — but a fork carries its whole asset tree, which its license does not permit anyone to
redistribute, and installing a fork **uninstalls** the original, because Android allows one APK per
package id.

OpenSeagull takes the other path. It is a separate app with its own package id that treats the
installed OpenPigeon as a library:

```
                    ┌──────────────┐
   OpenBubbles ────▶│  OpenSeagull │  ships: extension + mods, no content
                    └───────┬──────┘
                            │ createPackageContext(INCLUDE_CODE)
                            ▼
                    ┌──────────────┐
                    │  OpenPigeon  │  your install; untouched, still works
                    └──────────────┘
```

Nothing is injected into OpenPigeon's process — that is impossible without root. Instead, their
code is loaded into *ours*.

## Requirements

- Android 8.0 (API 26) or newer
- [OpenBubbles] installed
- [OpenPigeon] installed — OpenSeagull does nothing without it

[OpenBubbles]: https://github.com/OpenBubbles/OpenBubbles

## Install

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open **OpenSeagull** from the launcher. The diagnostics screen states what it can see: whether
OpenPigeon was found, how many games it read, and by which strategy.

## Register with OpenBubbles

1. OpenBubbles → **Settings** → **Troubleshoot** → **Developer Tools**
2. Add a service, typed exactly:
   ```
   com.roowus.openseagull.MadridExtensionService
   ```
3. In a conversation, tap **+**. The GamePigeon slot now shows **OpenSeagull**.

**To switch back:** *Clear services*, then force-quit OpenBubbles. Clearing alone is not enough —
the host keeps a live registry map that only a restart rebuilds. OpenPigeon reclaims the slot and
OpenSeagull stays installed, so you can flip between them.

**Clear services *before* uninstalling OpenSeagull.** Uninstalling while still registered makes the
host's `refreshCache()` throw mid-loop on a package that no longer exists, leaving its cached
extension list stale.

### Why the slot is shared

Real inbound GamePigeon balloons from iOS all carry app id **1124197642**. Routing is by that
integer and nothing else, so whichever extension holds that slot receives real games — a sibling
extension with a new id would simply never get one. OpenSeagull therefore claims the same id, which
is what makes the handoff reversible rather than additive.

For the same reason the on-the-wire name stays `GamePigeon`: it is the label rendered on the
*recipient's* iPhone. Only the picker, which shows the app label, says OpenSeagull.

## How it works

Three facts about Android shape the entire design. Each was measured on-device, and each is
enforced by structure rather than by remembering it.

**1. Foreign code loads, but its classes are not your classes.**
`createPackageContext(CONTEXT_INCLUDE_CODE or CONTEXT_IGNORE_SECURITY)` returns a working
ClassLoader for another app even when the signatures differ. But class identity is per-ClassLoader,
so casting their instance to a same-named type of ours produces the memorable

```
ClassCastException: com.openbubbles.openpigeon.pool.Drand48
    cannot be cast to com.openbubbles.openpigeon.pool.Drand48
```

Every call into their code is therefore reflective, funnelled through `host/Reflect.kt`. No typed
field ever holds a foreign instance.

**2. Resource ids are per-APK, and using the wrong table fails silently.**
`madrid_icon` is `0x7f070106` in their APK and `0x7f070108` in ours. Resolving their id against our
`Resources` does not throw — it returns an unrelated drawable. `InstalledOpenPigeon.drawable()` is
the only convenient way to resolve an id in this codebase, and it always pairs the id with *their*
resource table.

**3. Identity follows the Context you pass.**
Their `getSenderUUID(context)` mints and caches a UUID into the prefs of whichever Context it
receives. Hand it ours and the player silently gets a second identity. `ForeignGame.senderUuid()`
passes their package Context, and an instrumented test asserts the two UUIDs differ.

Game discovery prefers reading OpenPigeon's own registry, so new games appear without an OpenSeagull
update, and falls back to known class names. Which path ran is reported as `strategy` — so "no
games" is always distinguishable from "we failed to read the list".

## Tests

```
./gradlew testDebugUnitTest        # JVM: wire contract + content-free check
./gradlew connectedAndroidTest     # device: asserts the architecture against your OpenPigeon
```

The instrumented suite skips rather than fails when OpenPigeon is absent — that is a valid device
state, just not one that can test anything. Read its descriptive output with:

```
adb logcat -d -s SEAGULL:I
```

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

OpenSeagull is not affiliated with OpenPigeon, OpenBubbles, GamePigeon, or Apple.
