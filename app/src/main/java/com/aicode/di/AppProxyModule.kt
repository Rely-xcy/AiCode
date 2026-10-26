package com.aicode.di

import android.content.Context
import com.aicode.core.net.ProxyEndpoint
import com.aicode.core.net.ProxyRouteLookup
import com.aicode.core.net.ProxyScheme
import com.aicode.core.net.ProxySettings
import com.aicode.core.net.ProxySettingsSource
import com.aicode.core.net.toProxyEndpoint
import com.aicode.feature.settings.data.repository.ProviderProxyEntry
import com.aicode.feature.settings.data.repository.ProviderProxyRegistry
import com.aicode.feature.settings.data.repository.ProxySettingsRepository
import com.aicode.feature.settings.domain.model.ProxyType
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// core 侧 AppProxy 只声明要什么，代理配置的读取与 provider 分派仍由 feature 侧实现，依赖方向在这里收口。
@Module
@InstallIn(SingletonComponent::class)
object AppProxyModule {

    @Provides
    @Singleton
    fun provideProxyRouteLookup(registry: ProviderProxyRegistry): ProxyRouteLookup =
        object : ProxyRouteLookup {
            override fun findByUrl(host: String, path: String?): ProxyEndpoint? =
                registry.findByUrl(host, path)?.toEndpoint()

            override fun findByProxyHost(proxyHost: String): ProxyEndpoint? =
                registry.findByProxyHost(proxyHost)?.toEndpoint()
        }
}

// AppProxy 在 Application.attachBaseContext 里就要能读代理配置，那时 Hilt 尚未装配，
// 因此这里是可直接引用的无状态实现：每次调用同步读 SharedPreferences（与改动前的读取时机一致）。
object AppProxySettingsSource : ProxySettingsSource {
    override fun read(context: Context): ProxySettings {
        val cfg = ProxySettingsRepository.readConfig(context)
        return ProxySettings(
            enabled = cfg.enabled,
            endpoint = cfg.toProxyEndpoint(),
            noProxy = cfg.noProxy
        )
    }
}

private fun ProviderProxyEntry.toEndpoint(): ProxyEndpoint = ProxyEndpoint(
    scheme = if (type == ProxyType.SOCKS5) ProxyScheme.SOCKS5 else ProxyScheme.HTTP,
    host = host,
    port = port,
    username = username,
    password = password
)
