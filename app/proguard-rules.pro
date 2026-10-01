# ProGuard rules for Bangla WhatsApp Translator

# Keep ML Kit Translate classes and model loaders
-keep class com.google.mlkit.nl.translate.** { *; }
-keep class com.google.android.gms.internal.mlkit_translate.** { *; }

# Keep model data classes
-keepclassmembers class * {
    @androidx.annotation.Keep <fields>;
    @androidx.annotation.Keep <methods>;
}

# Retain Parcelable and Serializable implementations
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}
