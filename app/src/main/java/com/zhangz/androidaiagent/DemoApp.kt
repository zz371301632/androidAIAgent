package com.zhangz.androidaiagent

import android.app.Application
import android.content.Context

/**
 * Demo Application:仅做一件事 —— 把 applicationContext 暴露给纯静态的工具对象
 * (例如 [com.zhangz.androidaiagent.demo.tools.DemoTools.showToast] 里需要 Context
 * 才能弹 Toast)。
 *
 * 真实业务一般已经有自己的全局 Context 持有方案,可以直接复用,不用照抄这套。
 */
class DemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.application = this
    }
}

object AppContextHolder {
    @Volatile
    lateinit var application: Application
        internal set

    val context: Context get() = application.applicationContext
}
