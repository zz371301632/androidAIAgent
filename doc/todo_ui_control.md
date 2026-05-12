# TODO: UI 操作能力 (UI Control)

让 Agent 能够「看见」当前屏幕内容并进行点击、输入等操作，真正实现「AI 操作员」的功能。

## 1. 核心技术选型
- **AccessibilityService**: Android 原生能力，用于获取视图树（Node Hierarchy）和执行手势。
- **XML/JSON Layout Tree**: 将复杂的 AccessibilityNodeInfo 树简化为 LLM 可理解的文本格式。

## 2. 具体实现步骤

### 2.1 基础服务搭建
- [ ] 创建 `UiAutomatorService` (继承 `AccessibilityService`):
  - 处理服务开启引导（跳转系统设置）。
  - 提供全局静态单例或通过 Context 访问。
- [ ] 实现 `NodeTreeDumper`:
  - 递归遍历 `rootInActiveWindow`。
  - **过滤逻辑**: 仅保留可点击、可滑动、有文字、有描述的节点。
  - **简化输出**: 生成类似 `[ID:1, Text: "提交", Type: "Button", Clickable: true]` 的列表。

### 2.2 定义 AI 工具 (@AiTool)
在 `aiagent/lib_ai_agent_sdk` 或新模块中定义：
- [ ] `dump_screen()`:
  - 返回当前页面的简化节点树。
  - 附带基本信息：Activity 名称、屏幕尺寸。
- [ ] `click_element(node_id)`:
  - 根据 ID 找到对应节点，执行 `ACTION_CLICK`。
  - **安全**: 必须在 `confirmDangerous` 范围内。
- [ ] `input_text(node_id, text)`:
  - 执行 `ACTION_SET_TEXT` 或 `ACTION_FOCUS` 后模拟输入。
- [ ] `scroll_screen(direction)`:
  - 执行全局滑动动作。

### 2.3 视觉辅助 (进阶)
- [ ] `take_screenshot()`:
  - 截图并转换成 Base64/本地路径，供支持多模态的 LLM 辅助分析。

## 3. 验收场景
- 「帮我把当前页面设置里的深色模式打开。」
- 「在淘宝/京东里搜索 XX 并找一个最便宜的告诉我。」
