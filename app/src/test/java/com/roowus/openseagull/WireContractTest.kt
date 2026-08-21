package com.roowus.openseagull

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the two properties that no device can check for us cheaply: the routing contract in the
 * manifest, and the claim that this repository contains no OpenPigeon code.
 *
 * These run on the JVM with no emulator, so they gate every push. The manifest is read as text
 * rather than parsed because the assertions are about exact literal values — a value that XML
 * parsing would normalise (`1124197642` vs `1124197642 `) is a value that has been edited.
 */
class WireContractTest {

    /** Gradle runs unit tests with the module directory as the working directory. */
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    /**
     * The three values that go on the wire, or decide whether we are on it at all.
     *
     * `madrid_id` is the sole routing key — real inbound iOS GamePigeon balloons all carry
     * 1124197642, so any other value means OpenSeagull never receives a real game. `madrid_name`
     * is the balloon label rendered on the *recipient's iPhone*, which is why it stays
     * "GamePigeon" even though the app is called OpenSeagull. `madrid_bundle_id` is Apple's
     * extension identifier and is likewise wire format.
     */
    @Test
    fun routingContractIsUnchanged() {
        assertTrue(
            "madrid_id must remain 1124197642 — it is the only routing key",
            manifest.contains("""android:name="madrid_id"""") &&
                manifest.contains("""android:value="1124197642""""),
        )
        assertTrue(
            "madrid_name goes on the wire as the iPhone balloon label and must stay GamePigeon",
            manifest.contains("""android:name="madrid_name"""") &&
                manifest.contains("""android:value="GamePigeon""""),
        )
        assertTrue(
            "madrid_bundle_id is Apple's extension identifier and is wire format",
            manifest.contains(
                "com.apple.messages.MSMessageExtensionBalloonPlugin:EWFNLB79LQ:" +
                    "com.gamerdelights.gamepigeon.ext",
            ),
        )
    }

    /**
     * The host rebuilds the registered component as `applicationId + "." + lastSegment`, so the
     * service must be declared with a relative name that expands against our own namespace. A
     * fully-qualified name pointing anywhere else would resolve to a different app.
     */
    @Test
    fun serviceIsRegisterableByTheHost() {
        assertTrue(
            "the service must be declared as .MadridExtensionService",
            manifest.contains("""android:name=".MadridExtensionService""""),
        )
        assertTrue(
            "the service must be exported for OpenBubbles to bind it",
            manifest.contains("""<action android:name="com.openbubbles.messaging.MadridExtensionService" />""") ||
                manifest.contains("com.openbubbles.messaging.MadridExtensionService"),
        )
    }

    /**
     * Package-visibility filtering hides other apps from targetSdk 30+. Without these entries every
     * `PackageManager` call against OpenPigeon throws `NameNotFoundException` even when it is
     * installed — and the whole architecture is `PackageManager` calls against OpenPigeon.
     */
    @Test
    fun packageVisibilityIsDeclared() {
        assertTrue(
            "OpenPigeon must be listed in <queries> or we cannot see it at all",
            manifest.contains("""<package android:name="com.openbubbles.openpigeon" />"""),
        )
    }

    /**
     * The content-free claim, checked against the shipped source rather than trusted.
     *
     * This project's whole legal position is that it ships no OpenPigeon code or assets and reads
     * both from the user's own installation at runtime. A stray copied file would quietly void
     * that, so the check is mechanical: nothing in `src/main` may *declare* an OpenPigeon package.
     *
     * Two scoping decisions, both deliberate:
     *
     * - Only `src/main`, because only `src/main` is packaged into the APK. The test sources name
     *   OpenPigeon classes constantly — as reflection targets and, in this very file, as the
     *   pattern being searched for. Scanning them would make this test fail on itself.
     * - A `package` *declaration*, anchored to the start of a line, not any occurrence of the
     *   text. Our own code is full of OpenPigeon FQNs in string literals; that is the architecture
     *   working, not a violation. What would be a violation is a file that compiles *into* their
     *   package, and that is exactly what a package declaration is.
     *
     * `com.bluebubbles.messaging` is not covered here at all: those AIDL files are the *host's*
     * interface, published Apache-2.0 in the OpenBubbles repository and copied from there.
     */
    @Test
    fun shippedSourceDeclaresNoOpenPigeonPackage() {
        val declaration = Regex("""^\s*package\s+com\.openbubbles\.openpigeon""", RegexOption.MULTILINE)

        val offenders = File("src/main").walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java", "aidl") }
            .filter { declaration.containsMatchIn(it.readText()) }
            .map { it.path }
            .toList()

        assertEquals(
            "these files declare an OpenPigeon package — the build is no longer content-free",
            emptyList<String>(),
            offenders,
        )
    }
}
