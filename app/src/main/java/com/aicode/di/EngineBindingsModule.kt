package com.aicode.di

import com.aicode.feature.agent.domain.engine.EngineModule
import com.aicode.feature.agent.domain.engine.modules.CompactionModule
import com.aicode.feature.agent.domain.engine.modules.MemoryModule
import com.aicode.feature.agent.domain.engine.modules.SubAgentModule
import com.aicode.feature.agent.domain.engine.modules.TaskModule
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * 引擎模块注册：把每个 [EngineModule] 实现汇集为 Set，供
 * [com.aicode.feature.agent.domain.engine.AgentEngine] 构造注入。
 *
 * 新增一个引擎模块时，在这里追加一行 `@Binds @IntoSet` 绑定即可，
 * 引擎侧不需要任何改动。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EngineBindingsModule {

    @Binds
    @IntoSet
    abstract fun bindMemoryModule(module: MemoryModule): EngineModule

    @Binds
    @IntoSet
    abstract fun bindCompactionModule(module: CompactionModule): EngineModule

    @Binds
    @IntoSet
    abstract fun bindSubAgentModule(module: SubAgentModule): EngineModule

    @Binds
    @IntoSet
    abstract fun bindTaskModule(module: TaskModule): EngineModule
}
