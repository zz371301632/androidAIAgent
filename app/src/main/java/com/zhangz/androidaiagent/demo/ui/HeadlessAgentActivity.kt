package com.zhangz.androidaiagent.demo.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.zhangz.androidaiagent.BuildConfig
import com.zhangz.androidaiagent.demo.bootstrap.HeadlessRunner
import com.zhangz.androidaiagent.demo.headless.HeadlessPolicy

/**
 * Headless 入口:adb 直接派 AI 任务,**不显示任何 UI**。
 *
 * 调用示例:
 * ```
 * # 默认安全(危险工具一律拒绝)
 * adb shell am start -a com.zhangz.androidaiagent.HEADLESS \
 *     -e task "现在几点"
 *
 * # 显式放行危险工具(requiresConfirmation=true 的才能跑)
 * adb shell am start -a com.zhangz.androidaiagent.HEADLESS \
 *     -e task "清空演示历史" \
 *     --ez allowDangerous true
 *
 * # 预加载 skill,省一轮 list_skills/load_skill
 * adb shell am start -a com.zhangz.androidaiagent.HEADLESS \
 *     -e task "用 toast 弹一句你好" \
 *     -e loadSkills "demo"
 * ```
 *
 * 设计:
 *  - `Theme.NoDisplay` + `onCreate` 立刻 `finish()`:Activity 视觉上不出现,
 *    但已经把任务派给 [HeadlessRunner.run] 在 ApplicationScope 跑,
 *    Activity finish 不影响协程。
 *  - 所有反馈(任务派单 / 完成 / 失败 / 拒绝)走 logcat `AiAgent_Headless` + Toast,
 *    详见 [com.zhangz.androidaiagent.demo.headless.HeadlessReporter]。
 *  - `if (!BuildConfig.DEBUG) finish()` 是 release 包兜底:headless 入口仅作调试用,
 *    真上架前应把 manifest 里这条 activity 也搬到 `app/src/debug/AndroidManifest.xml`。
 */
class HeadlessAgentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) {
            finish()
            return
        }
        try {
            dispatch(intent)
        } finally {
            finish()
        }
    }

    private fun dispatch(intent: Intent?) {
        val task = intent?.getStringExtra(HeadlessPolicy.EXTRA_TASK).orEmpty()
        val policy = HeadlessPolicy(
            allowDangerous = intent?.getBooleanExtra(HeadlessPolicy.EXTRA_ALLOW_DANGEROUS, false) ?: false,
            preloadSkillIds = HeadlessPolicy.parseSkillIds(intent?.getStringExtra(HeadlessPolicy.EXTRA_LOAD_SKILLS)),
        )
        HeadlessRunner.run(applicationContext, task, policy)
    }
}
