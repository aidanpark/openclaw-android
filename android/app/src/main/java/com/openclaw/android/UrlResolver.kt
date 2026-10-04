package com.openclaw.android

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.URL

/**
 * Resolves download URLs with BuildConfig hardcoded fallback + config.json override (§2.9).
 *
 * Priority: cached config.json → remote config.json (5s timeout) → BuildConfig constants
 */
class UrlResolver(
    private val context: Context,
) {
    companion object {
        private const val CONFIG_FETCH_TIMEOUT_MS = 5_000L
    }

    private val configFile =
        File(
            context.filesDir,
            "usr/share/openclaw-app/config.json",
        )
    private val gson = Gson()

    suspend fun getBootstrapUrl(): String {
        val config = loadConfig()
        return config?.bootstrap?.url ?: BuildConfig.BOOTSTRAP_URL
    }

    suspend fun getWwwUrl(): String {
        val config = loadConfig()
        return config?.www?.url ?: BuildConfig.WWW_URL
    }

    suspend fun getWwwComponent(): ComponentConfig {
        val config = loadConfig()
        return config?.www ?: ComponentConfig(
            url = BuildConfig.WWW_URL,
            version = BuildConfig.WWW_VERSION,
            sha256 = BuildConfig.WWW_SHA256,
        )
    }

    private suspend fun loadConfig(): RemoteConfig? {
        // 1. Local cache
        if (configFile.exists()) {
            return try {
                gson.fromJson(configFile.readText(), RemoteConfig::class.java)
            } catch (_: Exception) {
                null
            }
        }

        // 2. Remote fetch (5s timeout)
        return try {
            withTimeout(CONFIG_FETCH_TIMEOUT_MS) {
                val json = URL(BuildConfig.CONFIG_URL).readText()
                configFile.parentFile?.mkdirs()
                configFile.writeText(json)
                gson.fromJson(json, RemoteConfig::class.java)
            }
        } catch (_: Exception) {
            null // BuildConfig fallback
        }
    }

    // --- Config data classes ---

    data class RemoteConfig(
        val version: Int?,
        val bootstrap: ComponentConfig?,
        val www: ComponentConfig?,
        val platforms: List<PlatformConfig>?,
        val features: Map<String, Boolean>?,
    )

    data class ComponentConfig(
        val url: String,
        val version: String?,
        @SerializedName("sha256") val sha256: String?,
    )

    data class PlatformConfig(
        val id: String,
        val name: String,
        val icon: String?,
        val description: String?,
    )
}
