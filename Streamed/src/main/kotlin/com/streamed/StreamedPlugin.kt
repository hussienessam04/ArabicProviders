package com.streamed

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StreamedPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(StreamedProvider())
    }
}