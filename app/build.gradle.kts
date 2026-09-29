import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Read a dev-only default Gemini key from local.properties (gitignored). Empty if absent.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
val defaultGeminiKey: String = localProps.getProperty("GEMINI_API_KEY", "")
val defaultGroqKey: String = localProps.getProperty("GROQ_API_KEY", "")
// Extra Groq keys for rotation on rate-limit (429). Free-tier per-minute limits are the main cap on
// multi-step cloud tasks; rotating across keys multiplies the effective quota. Blank slots are ignored.
val defaultGroqKey2: String = localProps.getProperty("GROQ_API_KEY_2", "")
val defaultGroqKey3: String = localProps.getProperty("GROQ_API_KEY_3", "")
val defaultGroqKey4: String = localProps.getProperty("GROQ_API_KEY_4", "")
// Optional overrides so the same OpenAI-compatible brain slot can point at a local Ollama
// server instead of Groq's cloud endpoint (e.g. GROQ_BASE_URL=http://<mac-lan-ip>:11434/v1).
// Blank means "use the Settings.kt cloud default" — no behavior change for anyone not using these.
val defaultGroqBaseUrl: String = localProps.getProperty("GROQ_BASE_URL", "")
val defaultGroqModel: String = localProps.getProperty("GROQ_MODEL", "")
// Optional "reasoning_effort" sent to the OpenAI-compatible endpoint (blank = omit the field —
// no behavior change for Groq's gpt-oss models). See Settings.groqReasoningEffort.
val defaultGroqReasoningEffort: String = localProps.getProperty("GROQ_REASONING_EFFORT", "")
// Phase 8 hybrid ASR: online Ghanaian-English endpoint (OpenAI-compatible /audio/transcriptions).
// BLANK by default → online ASR disabled, router runs fully offline. Set ASR_ONLINE_URL (+ optional
// ASR_ONLINE_KEY, ASR_ONLINE_MODEL) in local.properties once the model is hosted. Never committed.
val defaultAsrOnlineUrl: String = localProps.getProperty("ASR_ONLINE_URL", "")
val defaultAsrOnlineKey: String = localProps.getProperty("ASR_ONLINE_KEY", "")
val defaultAsrOnlineModel: String = localProps.getProperty("ASR_ONLINE_MODEL", "")
// The Khaya / Abena speech keys were removed with their clients (2026-09-28): the UG HCI Lab ASR/TTS API is required.
// Language layer (B-18): every local.properties entry whose key starts with SPEECH_ (endpoints and keys for the UG HCI
// Lab or any REST speech/translation API) reaches the app as ONE JSON map; `${SPEECH_…}` placeholders in
// assets/config/language.json resolve against it. Nothing is committed — no SPEECH_ entries ⇒ every client stays off.
fun jsonString(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
val speechSecretsJson: String = localProps.stringPropertyNames().filter { it.startsWith("SPEECH_") }.sorted()
    .joinToString(",", "{", "}") { jsonString(it) + ":" + jsonString(localProps.getProperty(it).trim()) }

// API KEYS go into DEBUG builds only (the owner's sideloaded test phones). Anything in BuildConfig can be read back out of
// the APK by decompiling it (OWASP MASVS-STORAGE; Android "hardcoded secrets" guidance), so a RELEASE build never carries
// one: there the user's own key comes from Settings (or, later, a backend proxy holds it). Non-secret defaults (URLs,
// model names) stay in defaultConfig.
val secretBuildFields = linkedMapOf(
    "DEFAULT_GEMINI_KEY" to defaultGeminiKey,
    "DEFAULT_GROQ_KEY" to defaultGroqKey,
    "DEFAULT_GROQ_KEY_2" to defaultGroqKey2,
    "DEFAULT_GROQ_KEY_3" to defaultGroqKey3,
    "DEFAULT_GROQ_KEY_4" to defaultGroqKey4,
    "DEFAULT_ASR_ONLINE_KEY" to defaultAsrOnlineKey
)

android {
    namespace = "com.tekyerema"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tekyerema"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-phase1"
        vectorDrawables { useSupportLibrary = true }
        buildConfigField("String", "DEFAULT_GROQ_BASE_URL", "\"$defaultGroqBaseUrl\"")
        buildConfigField("String", "DEFAULT_GROQ_MODEL", "\"$defaultGroqModel\"")
        buildConfigField("String", "DEFAULT_GROQ_REASONING_EFFORT", "\"$defaultGroqReasoningEffort\"")
        buildConfigField("String", "DEFAULT_ASR_ONLINE_URL", "\"$defaultAsrOnlineUrl\"")
        buildConfigField("String", "DEFAULT_ASR_ONLINE_MODEL", "\"$defaultAsrOnlineModel\"")
        buildConfigField("String", "SPEECH_SECRETS_JSON", "\"" + speechSecretsJson.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            secretBuildFields.forEach { (name, value) -> buildConfigField("String", name, "\"$value\"") }
        }
        release {
            // No key is ever baked into a release APK (see secretBuildFields).
            secretBuildFields.keys.forEach { name -> buildConfigField("String", name, "\"\"") }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // The sherpa-onnx static-link AAR ships x86 libonnxruntime.so, and so does onnxruntime-android (wake word).
        jniLibs { pickFirsts += "lib/x86/libonnxruntime.so" }
    }
    androidResources {
        noCompress += listOf("onnx", "bin")   // don't compress on-device model assets
    }
    testOptions {
        // Voice code uses android.util.Log; return defaults (no-op) instead of throwing in JVM tests.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Redesign 2026-09-29: icon tiles in Settings / History (globe, mic, keyboard, shield…); unused icons are
    // stripped by R8 in release builds.
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // On-device OCR for the vision perception route (plan §6). Bundled model: works offline,
    // nothing leaves the device. Used only when the accessibility tree is insufficient.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // P-01/P-03/P-06/P-09 camera perception (owner approved CAMERA 2026-09-28): CameraX preview + frame analysis.
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    // On-device ASR runtime: sherpa-onnx (static-linked onnxruntime), reused from the old project. Runs the
    // proven whisper-TINY per-language fine-tunes (Ghanaian English + Twi, tuned on the owner's command
    // register) fully offline — fast on the A20 (tiny, not base). Local AAR (not on Maven).
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"))
    // Wake word ("Hey Tɛkyerɛma"): openWakeWord mel + embedding features and our trained heads, run with
    // ONNX Runtime. Same pairing with the sherpa AAR as the V2 app (the x86 pickFirst above covers the dup).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.1")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Pure-logic unit tests (grounding, sanitization, provenance, policy, verification predicates).
    // org.json is an Android stub in local unit tests; the real artifact is supplied here.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // Voice state-machine tests drive the controller's coroutines deterministically.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
