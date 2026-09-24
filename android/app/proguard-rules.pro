# ── Room ────────────────────────────────────────────────────────────────────
# Keep all Room entity, DAO, and Database classes so Room's generated code
# can still find them at runtime after R8 renames everything else.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    abstract *;
}

# ── OkHttp ──────────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ── Kotlin coroutines / serialization ───────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# ── Compose ─────────────────────────────────────────────────────────────────
# R8 handles Compose well, but the lambda/composable wrappers need to survive.
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ── Coil image loader ────────────────────────────────────────────────────────
-keep class coil.** { *; }
-dontwarn coil.**

# ── AndroidX Biometric ───────────────────────────────────────────────────────
-keep class androidx.biometric.** { *; }

# ── WorkManager ──────────────────────────────────────────────────────────────
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ── JSON (org.json) ──────────────────────────────────────────────────────────
-keep class org.json.** { *; }

# ── Kotlin metadata (needed for reflection-based libs) ───────────────────────
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ── Suppress noisy missing-class warnings from third-party libs ──────────────
-dontwarn java.lang.invoke.**
-dontwarn javax.annotation.**
-dontwarn sun.misc.Unsafe

# ── Firebase ──────────────────────────────────────────────────────────────────
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**
