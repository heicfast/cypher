package chat.cypher.android

import android.app.Application
import chat.cypher.android.core.MeshController

class CypherApplication : Application() {
    lateinit var meshController: MeshController
        private set

    override fun onCreate() {
        super.onCreate()
        meshController = MeshController(applicationContext)
    }
}
