# TODO: 主动触发 (Proactive Agent)

改变「用户问 -> AI 答」的被动模式，让 Agent 能够根据系统事件主动关心用户。

## 1. 核心技术选型
- **EventBus / Observer**: 监听 App 内部的重要业务事件（如：支付失败、停留过久、进入特定页面）。
- **HeadlessRunner**: 复用现有的静默运行机制，在后台先思考是否需要介入。
- **UI Overlay / Floating Bubbles**: 当 AI 决定介入时，弹出一个微小的气泡或 Snackbar 询问用户。

## 2. 具体实现步骤

### 2.1 事件中心 (AgentEventCenter)
- [ ] 定义 `AgentTrigger`:
    - `condition`: 事件类型或特定页面。
    - `prompt`: 给 AI 的上下文（例如："用户在支付页卡了3分钟，余额充足但未点击支付"）。
- [ ] 注册监听器：在 `ActivityLifecycleCallbacks` 或业务关键点埋点。

### 2.2 后台决策流 (Pre-think)
- [ ] 当触发 condition 时，后台静默启动 `AgentLoop`:
    - System Prompt: "你是一个贴心的助手。现在发生了[事件]，请判断是否需要主动提供帮助。如果不需要，请回复 'IGNORE'；如果需要，请简短说明你能帮什么。"
    - 如果回复不是 'IGNORE'，则触发 UI 提醒。

### 2.3 主动交互 UI
- [ ] 实现 `ProactiveHintView`:
    - 一个悬浮的小气泡或底部卡片。
    - 点击后直接打开 `AgentChatActivity` 并带入 AI 刚才的「主动开场白」。

## 3. 验收场景
- **结账辅助**: 用户在购物车页面停留很久且反复切换优惠券，AI 弹出：「需要我帮你计算最省钱的组合吗？」
- **异常恢复**: App 发生 API 报错或业务失败，AI 弹出：「刚才的订单似乎失败了，是因为 XX 吗？我可以帮你尝试重新提交。」
