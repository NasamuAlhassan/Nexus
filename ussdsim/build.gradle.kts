// The USSD SIMULATOR (mission P2-01, 13 October 2026): a separate, tiny APK that replays MTN's recorded `*170#` screens as a
// real dialog with an entry box and Send / Cancel, so the agent's money walk can be exercised END TO END on the emulator —
// navigator, policy gate, PIN hand-off, volume-key PIN pad, receipt read-out — where no network answers USSD codes.
// It has its own package because an accessibility service is shown no windows of its own package. It is a test harness:
// installed by scripts/emu_setup.sh on the emulator only, started only by the debug app with `money.ussd.simulator` on.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tekyerema.ussdsim"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.tekyerema.ussdsim"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Framework widgets only — no AndroidX, so the module resolves offline from the app's own dependency cache.
    testImplementation("junit:junit:4.13.2")
}
