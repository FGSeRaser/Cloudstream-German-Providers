package com.seraser

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class BurningSeriesPlugin : Plugin() {
    override fun load(context: Context) {
        // Registriert den Provider beim Laden des Plugins
        registerMainAPI(BurningSeries())
    }
}
