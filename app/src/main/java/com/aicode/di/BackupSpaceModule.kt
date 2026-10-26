package com.aicode.di

import com.aicode.feature.backup.domain.AvailableSpaceProvider
import com.aicode.feature.backup.domain.StatFsAvailableSpaceProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 空间预检的可用空间来源；单测可替成固定值，不必真去 StatFs。 */
@Module
@InstallIn(SingletonComponent::class)
object BackupSpaceModule {

    @Provides
    @Singleton
    fun provideAvailableSpaceProvider(): AvailableSpaceProvider = StatFsAvailableSpaceProvider
}
