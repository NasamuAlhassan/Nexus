# Keep annotations (Compose/Kotlin reflection, serialization).
-keepattributes *Annotation*

# SEC-P0-1 / SEC-P1-4 — when R8/minify is enabled for release (`isMinifyEnabled = true`), these rules
# obfuscate the embedded BuildConfig key constants AND strip every android.util.Log call, so screen
# snapshots / transcripts / MoMo details can't leak via logcat in the shipped APK. They are inert while
# minify is off, so staging them here is safe. NOTE: after enabling minify, run the app once and add any
# `-keep` needed for reflection/serialization (kotlinx, Compose, Gson-style) surfaced by the build.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# Common safe keeps for a Compose + Kotlin app once minify is on (uncomment as needed):
# -keep class com.tekyerema.**$$serializer { *; }
# -keepclassmembers class com.tekyerema.** { *** Companion; }
# -keep,allowobfuscation class kotlin.coroutines.Continuation
