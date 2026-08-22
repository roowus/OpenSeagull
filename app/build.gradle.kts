plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
}

android {
    // Namespace and applicationId are deliberately the SAME here, unlike the fork.
    //
    // The fork had to keep namespace = com.openbubbles.openpigeon (its code package) while moving
    // applicationId to com.roowus.openseagull, which broke the host's registerDevExtension():
    // it rebuilds the component as applicationId + "." + lastSegment, so the service class package
    // must literally equal the applicationId. The fork needed a shim subclass to paper over that.
    // This project ships no OpenPigeon code, so it owns its whole namespace and needs no shim.
    namespace = "com.roowus.openseagull"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.roowus.openseagull"
        minSdk = 26
        targetSdk = 36
        // Dated (yyMMddNN), not sequential, and deliberately continuing the fork's numbering
        // even though this is a new project. The fork shipped the same applicationId, so a device
        // that has it installed will only accept this APK as an *update* — a lower code is refused
        // as a downgrade, and the alternative, uninstalling first, is the one operation that must
        // be avoided: uninstalling while still registered in OpenBubbles makes the host's
        // refreshCache() throw mid-loop on a package that no longer exists.
        //
        // versionName restarts at 0.1.0 because that is honestly where this codebase is.
        versionCode = 26082102
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Our resource table lives at package id 0x80, not the default 0x7f. This is load-bearing for
    // hosting, and it is the one build setting here that exists for a measured reason.
    //
    // Hosting one of OpenPigeon's Activities in our process means their code and ours drawing from
    // one AssetManager. Their aapt2 baked *integer* ids into their dex — setContentView(0x7f0b0012)
    // — so a merged table has to map their integer back to their entry. With both apps on the
    // default 0x7f it cannot: whichever APK is added last owns the id space, and the loser's ids
    // silently resolve to the winner's resources. Not an exception — a wrong picture, no stack
    // trace. GameplayFeasibilityProbe measured 58 of their 379 drawables shadowed this way.
    //
    // 0x7f is the app id and 0x01 is the framework's; everything between is nominally reserved,
    // which is what --allow-reserved-package-id is acknowledging. Reserved is not the same as
    // unavailable, and this is the mechanism every Android plugin framework uses for exactly this
    // problem. It is verified rather than assumed: with our table at 0x80 the same sweep reports
    // 0 of 379 shadowed in *both* merge orders, the app runs, and PickerRenderProbe still inflates
    // the full grid across a parcel — which is the check that matters, since RemoteViews ids are
    // resolved in OpenBubbles' process and not ours.
    //
    // Nothing may hard-code 0x7f-prefixed constants of ours. Nothing does: our ids reach the host
    // as R fields, which move with the table.
    androidResources {
        additionalParameters += listOf("--package-id", "0x80", "--allow-reserved-package-id")
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // kotlin.test's assertNotNull contracts a non-null smart cast, which the probe relies on;
    // JUnit's assertNotNull returns Unit and would leave every following line nullable.
    androidTestImplementation(kotlin("test"))
}
