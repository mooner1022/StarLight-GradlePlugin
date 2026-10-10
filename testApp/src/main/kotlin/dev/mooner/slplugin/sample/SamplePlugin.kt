package dev.mooner.slplugin.sample

import android.util.Log
import dev.mooner.starlight.plugincore.plugin.StarlightPlugin

class SamplePlugin : StarlightPlugin() {

    override fun onEnable() {
        Log.i(TAG, "Sample plugin enabled")
    }

    override fun onDisable() {
        Log.i(TAG, "Sample plugin disabled")
    }

    private companion object {
        const val TAG = "SamplePlugin"
    }
}
