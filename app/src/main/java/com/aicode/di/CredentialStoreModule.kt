package com.aicode.di

import com.aicode.core.security.CredentialStore
import com.aicode.feature.settings.data.AndroidCredentialStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 密钥轮换相关依赖的绑定。
 *
 * [CredentialStore] 的接口在 core 层、实现放在 feature 层——core 是被依赖方，
 * 不能反向 import feature 的类，所以绑定放在这里由 Hilt 在运行时接上。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CredentialStoreModule {

    @Binds
    @Singleton
    abstract fun bindCredentialStore(impl: AndroidCredentialStore): CredentialStore
}
