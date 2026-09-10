package dev.waadri.anylisten

import android.app.Application
import dev.waadri.anylisten.data.config.ConfigStore

/**
 * Manual dependency container. The graph is small enough that a DI framework would cost more
 * than it saves; everything is constructed once here and reached through [AnyListenApp].
 */
class AppContainer(application: Application) {
    val configStore: ConfigStore = ConfigStore(application)
}

class AnyListenApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
