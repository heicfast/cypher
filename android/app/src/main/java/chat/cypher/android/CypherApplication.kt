package chat.cypher.android

import android.app.Application
import chat.cypher.android.core.MeshRuntime

class CypherApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The engine + radio start with the foreground service (from the
        // main activity once permissions are granted); pre-warm the native
        // library so the first JNI call is fast.
        MeshRuntime.start(this, "cypher")
    }
}
