package com.zhangz.androidaiagent.demo.bootstrap

import android.app.Application
import android.content.Context

/**
 * 全局 Application 持有,**只服务那些必须拿 Context 才能跑的纯静态 @AiTool**(例如
 * 弹 Toast、读 assets)。SDK 自身不持 Context —— 这是接入侧的取舍:
 *
 *  - 用 Hilt / Koin 的工程,直接在工具里 `@Inject` ApplicationContext,不需要本类;
 *  - 没 DI 框架的小工程,在 Application.onCreate 里 `AppContextHolder.application = this`
 *    一行,工具里 `AppContextHolder.appContext` 取用。
 *
 * 装机前访问会抛错,确保「忘初始化」是显式失败。
 */
object AppContextHolder {

    @Volatile private var _application: Application? = null

    var application: Application
        get() = _application
            ?: error("AppContextHolder.application 未初始化,请在 Application.onCreate 里赋值")
        set(value) {
            _application = value
        }

    val appContext: Context get() = application.applicationContext
}
