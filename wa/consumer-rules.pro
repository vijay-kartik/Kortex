# Consumer rules for space.pitchstone:tether-core.
#
# These are applied automatically to any app that depends on this artifact, so a consumer with
# minification enabled does not have to know about the SDK's internals to ship a working build.

# --- Wire / generated WAProto ---
# Wire resolves each message's ADAPTER field reflectively when decoding nested and unknown
# fields. Stripping or renaming it turns every decode into a runtime failure.
-keepclassmembers class * extends com.squareup.wire.Message {
    <fields>;
    public static ** ADAPTER;
}
-keep class com.squareup.wire.** { *; }
-keep class dev.kortex.wa.proto.gen.** { *; }

# --- libsignal ---
# The Signal protocol library reflects over its own state classes, and its native curve
# implementation is resolved by name.
-keep class org.whispersystems.libsignal.** { *; }
-keep class org.whispersystems.curve25519.** { *; }
-dontwarn org.whispersystems.**

# --- BouncyCastle ---
# Providers are looked up by class name, and BC references JVM-only APIs absent on Android.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn org.bouncycastle.**

# --- OkHttp / Okio ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# --- Room entities used by the credential + Signal stores ---
-keep class dev.kortex.wa.session.WaKvEntity { *; }
-keep class dev.kortex.wa.session.WaCredentialsEntity { *; }
