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
