package chat.cypher.android

import android.app.Application
import chat.cypher.android.core.MeshController
import chat.cypher.android.core.MeshRuntime
import chat.cypher.android.ui.theme.ThemePreferenceManager

class CypherApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Theme preference + persisted state + the event pipeline.
        ThemePreferenceManager.init(this)
        MeshController.init(this)
        // Pre-warm the native library so the first JNI call is fast; the
        // engine uses the persisted nickname (fresh anon#### on first run).
        MeshRuntime.start(this, MeshController.myNickname)
    }
}
