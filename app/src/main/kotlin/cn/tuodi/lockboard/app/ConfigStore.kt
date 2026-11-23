package cn.tuodi.lockboard.app

import android.content.Context
import androidx.core.content.edit
import cn.tuodi.lockboard.core.config.AppConfig
import cn.tuodi.lockboard.core.config.ConfigJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persists [AppConfig] as JSON in SharedPreferences. Unreadable data falls back to defaults. */
class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("lockboard", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(load())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    private fun load(): AppConfig {
        val text = prefs.getString(KEY, null) ?: return AppConfig()
        return runCatching { ConfigJson.decode(text) }.getOrElse { AppConfig() }
    }

    @Synchronized
    fun update(transform: (AppConfig) -> AppConfig) {
        val next = transform(_config.value)
        if (next == _config.value) return
        _config.value = next
        prefs.edit { putString(KEY, ConfigJson.encode(next)) }
    }

    fun reset() = update { AppConfig() }

    private companion object {
        const val KEY = "config_json_v1"
    }
}
