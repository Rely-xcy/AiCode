package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import com.aicode.core.security.KeyRotationService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * 密钥状态页的 ViewModel：只负责发起轮换。
 *
 * 轮换要读写全部凭据，是磁盘与数据库操作，不能在主线程做；由 [KeyRotationService] 内部保证，
 * 这里只把调用暴露给界面。
 */
@HiltViewModel
class KeyStatusViewModel @Inject constructor(
    private val rotationService: KeyRotationService
) : ViewModel() {

    /** 手动轮换：生成新密钥并把全部凭据换到新密钥上。 */
    suspend fun rotate(): KeyRotationService.Result = rotationService.rotate()
}
