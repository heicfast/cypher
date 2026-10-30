package chat.cypher.android

import android.app.Application
import chat.cypher.android.core.MeshRuntime
import chat.cypher.android.ui.theme.ThemePrefs

class CypherApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Theme preference (light/dark/system) for the premium skin.
        ThemePrefs.init(this)
        // Pre-warm the native library so the first JNI call is fast; the
        // engine + radio start with the foreground service once permissions
        // are granted (onboarding completes).
        MeshRuntime.start(this, "cypher")
    }
}
