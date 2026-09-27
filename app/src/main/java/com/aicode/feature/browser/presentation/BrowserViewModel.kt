package com.aicode.feature.browser.presentation

import androidx.lifecycle.ViewModel
import com.aicode.feature.agent.domain.tool.browser.BrowserManager
import com.aicode.feature.agent.domain.tool.browser.BrowserUserAgent
import com.aicode.feature.agent.domain.tool.browser.BrowserUserAgentStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class BrowserViewModel @Inject constructor(
    val browserManager: BrowserManager,
    private val userAgentStore: BrowserUserAgentStore
) : ViewModel() {

    private val _userAgent = MutableStateFlow(userAgentStore.current())

    /** 当前 UA 档位；切换后立刻对已打开的标签重应用并重载页面。 */
    val userAgent: StateFlow<BrowserUserAgent> = _userAgent.asStateFlow()

    fun setUserAgent(value: BrowserUserAgent) {
        if (value == _userAgent.value) return
        userAgentStore.set(value)
        _userAgent.value = value
        browserManager.applyUserAgentChange()
    }
}
