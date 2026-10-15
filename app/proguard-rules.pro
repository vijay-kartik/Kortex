# R8 rules for the release build. Libraries (Room, Hilt, ObjectBox, Tink, ML Kit, MediaPipe,
# kotlinx.serialization) ship their own consumer rules; add keeps here only for what they miss.

# Keep line numbers so release stack traces can be retraced with the mapping file.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Backstop for AndroidLogger's level gate: strip verbose/debug/info Logcat calls from release,
# since agent lines at those levels carry user queries, tool payloads and LLM replies.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
