# OpenSeagull ProGuard rules.
#
# Minification is currently off (see app/build.gradle.kts), so nothing here is load-bearing yet.
# The rules are written now anyway because the two things this app does are precisely the two
# things shrinkers get wrong, and the failures are silent at build time.

# 1. The AIDL stub is reached only by the framework across a Binder. Nothing in our own code calls
#    these methods, so a shrinker sees the whole interface as dead and R8 would strip the override
#    bodies — the bind then succeeds and every call returns nothing.
-keep class com.bluebubbles.messaging.** { *; }
-keep class com.roowus.openseagull.MadridExtension { *; }
-keep class com.roowus.openseagull.MadridExtensionService { *; }

# 2. Everything this app reads out of the installed OpenPigeon is reached by *name*, at runtime,
#    through reflection. Those names live in the other APK and are unaffected by our shrinker —
#    but our own call sites pass them as string literals, so there is nothing here for R8 to see.
#    Listed for the reader's benefit: no rule can protect a name we do not own.
#    (Their classes are never on our classpath. That is the point of the architecture.)
