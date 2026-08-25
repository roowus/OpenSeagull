# OpenSeagull — a mod layer for OpenPigeon, the GamePigeon-compatible extension for Android

OpenSeagull is an [OpenBubbles] extension that adds features to [OpenPigeon] by hosting it at
runtime, rather than replacing or forking it.

**Some context, if you arrived here from the GamePigeon side of things.** [GamePigeon] is the iOS
iMessage app that people use to send 8 Ball, Sea Battle, Word Hunt and the rest as playable
balloons in a chat. It is iOS-only and closed-source. [OpenBubbles] is an Android iMessage client,
and [OpenPigeon] is its GamePigeon-compatible game extension — the piece that lets an Android
device send and receive those same games with iPhone users. OpenSeagull sits one layer above
OpenPigeon and adds to it. It is not a GamePigeon client by itself, and it is not affiliated with
GamePigeon, OpenPigeon, OpenBubbles, or Apple.

The name is a nod to **GameSeagull**, a separate Android app — likewise unaffiliated with
GamePigeon — that offers GamePigeon-style games under its own name and artwork rather than
redistributing anyone else's. Naming aside, the two projects are unrelated and share no code:
OpenSeagull is a mod layer that requires OpenPigeon, not a standalone game app.

OpenSeagull ships **no OpenPigeon code, artwork, or game data**. It reads what it needs from the
copy of OpenPigeon already installed on your device. Both apps stay installed, side by side, and
OpenPigeon is never modified.

> **Status: playable.** The architecture is proven end-to-end on-device: foreign classes load,
> games enumerate from OpenPigeon's own registry, resources and player identity resolve, the
> picker grid renders and sends real balloons, received balloons draw as boards with their own
> art and turn lines, tapping one opens the actual game in our process, moves write back into the
> conversation, and a stray back gesture no longer closes an open game. The app is still early —
> game setup screens are not built (configurable games send with defaults), and identity is
> separate from OpenPigeon proper by design.

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — how the whole machine works: hosting pipeline,
  session channel, payload format, identity, back-guard, testing.
- [docs/MAINTAINING-DOCS.md](docs/MAINTAINING-DOCS.md) — standing rule: **docs are part of the
  change.** Any commit that alters behaviour updates them in the same commit.

[OpenPigeon]: https://github.com/OpenBubbles/OpenPigeon
[GamePigeon]: https://apps.apple.com/us/app/gamepigeon/id1124197642

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

**When a tapped balloon can't be opened**, you get a dialog saying why instead of silence — either
"your installed OpenPigeon doesn't recognise this version" (sent from a newer build) or "OpenSeagull
doesn't host this game yet". OpenSeagull deliberately does not reuse OpenPigeon's own not-found
dialog for this: that one fires analytics with a hardcoded token, and nothing of OpenPigeon's
should run in OpenSeagull's process except what your own install already does.

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

**Known gap in the verification.** On the development device both apps are signed with the same
debug key, so the suite reports `SIGNATURE_MATCH` and does not exercise the cross-signature path
that every real user will be on. `createPackageContext(INCLUDE_CODE)` was separately confirmed to
work across `SIGNATURE_NO_MATCH`, which is the load-bearing part, but the full suite has not yet
run against a release-signed OpenPigeon. `reportEnvironment` prints which case it saw, so this is
visible in the output rather than assumed.

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

OpenSeagull is not affiliated with OpenPigeon, OpenBubbles, GamePigeon, or Apple.
