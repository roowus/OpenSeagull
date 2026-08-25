# OpenSeagull — working notes for agents and humans

## Standing rules

1. **Docs are part of the change.** Any commit that changes behaviour must update
   [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (and `README.md` if user-visible) **in the same
   commit**. Details and triggers: [docs/MAINTAINING-DOCS.md](docs/MAINTAINING-DOCS.md).
2. **Ship no OpenPigeon code** (`src/main` may not declare an `com.openbubbles.openpigeon`
   package — a JVM test enforces this) and **no compile-time reference to their types**: all
   interop is reflective, class names appear only as strings. A subclass of one of their
   activities cannot compile here — this was tried and reverted; see BackGuard's KDoc.
3. **Push to the `fork` remote** (`github.com/roowus/OpenSeagull`), not origin.

## Verify before committing

```
./gradlew :app:compileReleaseKotlin :app:testDebugUnitTest   # JVM gate, matches CI
git push fork main                                            # then watch CI with gh run watch
```

Instrumented probes need a device with OpenPigeon installed; they skip (not fail) without it.

## Repo map

- `app/src/main/java/com/roowus/openseagull/host/` — the runtime-hosting machinery
  (ForeignCode injections, SessionChannel/Registry/Writer, catalog, identity, BackGuard).
- Root package — extension contract implementation (MadridExtension*, BalloonTapActivity,
  UnsupportedGameActivity), diagnostics screen.
- `app/src/main/java/com/roowus/openseagull/ui/` — picker RemoteViews + paging + poster bitmaps.
- `app/src/androidTest/` — ten on-device probes, each measuring one architectural claim.
- `docs/ARCHITECTURE.md` — start here for how anything works.
