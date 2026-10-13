package com.aicode.feature.disclaimer.presentation

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.feature.disclaimer.data.DisclaimerRepository
import kotlinx.coroutines.launch

/**
 * 首次启动免责声明门控：未同意时在最上层显示必须确认的弹窗。
 *
 * [collectAsStateWithLifecycle] 初值取 true，避免已同意的老用户冷启动时先闪一下弹窗；
 * DataStore 读取完成后若实际未同意再补弹。
 */
@Composable
fun DisclaimerGate(repository: DisclaimerRepository) {
    val accepted by repository.acceptedFlow.collectAsStateWithLifecycle(initialValue = true)
    if (!accepted) {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        DisclaimerDialog(
            mode = DisclaimerMode.Accept,
            onAccept = { scope.launch { repository.accept() } },
            onDecline = { (context as? Activity)?.finish() }
        )
    }
}
