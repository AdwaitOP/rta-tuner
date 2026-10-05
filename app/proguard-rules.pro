# =============================================================================
# RTA Tuner — ProGuard / R8 Rules
# =============================================================================
# NOTE: This project uses the built-in Android debug keystore for signing
# release builds. A production keystore is a separate, deliberate step
# that has NOT been configured here.
# =============================================================================


# ---------------------------------------------------------------------------
# JTransforms (FFT library — uses reflection internally)
# ---------------------------------------------------------------------------
-keep class edu.emory.mathcs.jtransforms.** { *; }
-dontwarn edu.emory.mathcs.jtransforms.**


# ---------------------------------------------------------------------------
# Room — Entities, DAOs, TypeConverters, and Relation models
#
# R8 is aggressive about renaming/removing fields on data classes used as
# Room entities. Room generates implementation code at compile time via KSP,
# but the generated *_Impl classes still reference entity fields by name at
# runtime through reflection for @TypeConverters, @Embedded, and @Relation.
# ---------------------------------------------------------------------------

# Keep all Room entity and DAO classes (fields + methods)
-keep class com.rtatuner.app.core.database.** { *; }

# Keep the TypeConverter explicitly — R8 can strip @TypeConverter methods
# if the class is only referenced via annotation, not direct call sites
-keepclassmembers class com.rtatuner.app.core.database.BandsConverter {
    public ** fromFloatArray(float[]);
    public ** toFloatArray(java.lang.String);
}

# Keep SessionWithSnapshots: uses @Embedded + @Relation which Room resolves
# at runtime via reflection on field names
-keep class com.rtatuner.app.core.database.SessionWithSnapshots { *; }
-keepclassmembers class com.rtatuner.app.core.database.SessionWithSnapshots {
    <fields>;
}

# Keep all Room-generated *_Impl classes (KSP generates these; R8 may see
# them as dead code if they're only reached through abstract class refs)
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class **_Impl { *; }
-keep class **Dao_Impl { *; }

# Suppress warnings from Room's internal reflection utilities
-dontwarn androidx.room.**


# ---------------------------------------------------------------------------
# Kotlin coroutines
# ---------------------------------------------------------------------------
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**


# ---------------------------------------------------------------------------
# Kotlin — keep metadata for reflection used by Kotlin stdlib and coroutines
# ---------------------------------------------------------------------------
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable
-keepattributes Exceptions
-keepattributes InnerClasses,EnclosingMethod


# ---------------------------------------------------------------------------
# Kotlin data classes — prevent R8 from removing component/copy functions
# that Compose and coroutines may call indirectly
# ---------------------------------------------------------------------------
-keepclassmembers class com.rtatuner.app.** {
    ** component*();
    ** copy(...);
}


# ---------------------------------------------------------------------------
# Compose — tooling annotations, preview, and stable/immutable markers
# Compose compiler handles most of this, but keep annotations for runtime
# ---------------------------------------------------------------------------
-keep @interface androidx.compose.runtime.Stable
-keep @interface androidx.compose.runtime.Immutable
-dontwarn androidx.compose.**


# ---------------------------------------------------------------------------
# Android lifecycle / ViewModel
# ---------------------------------------------------------------------------
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}


# ---------------------------------------------------------------------------
# Enum classes — R8 can optimize away name()/values() if unused
# ---------------------------------------------------------------------------
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}


# ---------------------------------------------------------------------------
# Parcelable (Android serialization)
# ---------------------------------------------------------------------------
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}


# ---------------------------------------------------------------------------
# General Android framework keep rules (complement proguard-android-optimize.txt)
# ---------------------------------------------------------------------------
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
