package com.yacinetv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class YacineTVPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(YacineTVProvider(context))
    }
}
