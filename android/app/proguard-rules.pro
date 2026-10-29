# Cypher proguard rules (mirrors the bitchat-android keep set, trimmed).
# JNI bridge: keep everything the native side touches by name.
-keep class chat.cypher.android.core.CypherBridge { *; }
-keep class chat.cypher.android.core.MeshRuntime { *; }

# Compose.
-dontwarn androidx.compose.**

# Kotlin metadata for the JNI-adjacent data classes.
-keepclassmembers class chat.cypher.android.core.** { *; }
