package chat.cypher.android

import android.app.Application
import chat.cypher.android.core.CypherBridge
import chat.cypher.android.core.MeshController
import chat.cypher.android.core.MeshStore

class CypherApplication : Application() {
    val meshController: MeshController = MeshController

    override fun onCreate() {
        super.onCreate()
        MeshStore.attachDatabase(this)
        MeshStore.loadOrCreateSeed(this)
        CypherBridge.ensureLoaded()
    }
}
