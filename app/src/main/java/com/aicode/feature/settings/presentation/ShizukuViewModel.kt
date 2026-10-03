package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.domain.shizuku.ShizukuManager
import com.aicode.feature.agent.domain.shizuku.ShizukuPeerInfo
import com.aicode.feature.agent.domain.shizuku.ShizukuState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Shizuku 执行后端的设置页状态与操作入口。 */
@HiltViewModel
class ShizukuViewModel @Inject constructor(
    private val shizukuManager: ShizukuManager
) : ViewModel() {

    val state: StateFlow<ShizukuState> = shizukuManager.state

    /**
     * 当前连到的服务端身份（adb shell / root），供设置页展示「当前连的是谁」。
     *
     * 身份只能从服务端读，未就绪时 [ShizukuManager.peerInfo] 直接返回 null，所以这条流只跟着
     * [state] 变化重读——就绪且身份不变时不重复问 binder。读的是 binder 调用，放 IO 上做。
     */
    val peerInfo: StateFlow<ShizukuPeerInfo?> = shizukuManager.state
        .map { shizukuManager.peerInfo() }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 重新探测状态（如从 Shizuku 应用返回后）。 */
    fun refresh() = shizukuManager.refreshState()

    fun requestPermission() = shizukuManager.requestPermission()

    fun openShizukuApp() = shizukuManager.openShizukuApp()
}
