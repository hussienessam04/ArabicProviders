package com.footybite

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class FootybitePlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FootybiteProvider(context))
    }
}
