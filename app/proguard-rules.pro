# R8 rules for the release build. Libraries (Room, Hilt, ObjectBox, Tink, ML Kit, MediaPipe,
# kotlinx.serialization) ship their own consumer rules; add keeps here only for what they miss.

# Keep line numbers so release stack traces can be retraced with the mapping file.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
