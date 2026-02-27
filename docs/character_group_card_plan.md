# 群组角色卡（Group Card）规划

## 1. 现状定位（当前角色卡配置在哪里）

### 1.1 角色卡数据与存储
- `app/src/main/java/com/ai/assistance/operit/data/model/CharacterCard.kt`
  - 当前角色卡结构：`id/name/description/characterSetting/openingStatement/otherContent/attachedTagIds/advancedCustomPrompt/marks/...`
- `app/src/main/java/com/ai/assistance/operit/data/preferences/CharacterCardManager.kt`
  - 角色卡存储在 DataStore `character_cards`
  - Key 形如 `character_card_${id}_xxx`
  - 活跃角色卡：`active_character_card_id`
  - 提示词拼接入口：`combinePrompts(characterCardId, additionalTagIds)`

### 1.2 提示词管理界面
- `app/src/main/java/com/ai/assistance/operit/ui/features/settings/screens/ModelPromptsSettingsScreen.kt`
  - 当前 Tab 只有两页：角色卡 + 标签
  - 角色卡新增/编辑/删除/复制/导入导出都在这里

### 1.3 发送链路（你要的“自动多角色发言”必须改这里）
- `MessageCoordinationDelegate.sendUserMessage(...)`
  -> `MessageProcessingDelegate.sendUserMessage(...)`
  -> `AIMessageManager.sendMessage(...)`
  -> `EnhancedAIService.sendMessage(...)`
  -> `ConversationService.prepareConversationHistory(...)`

### 1.4 可复用能力（对“群组”很有用）
- 已支持“代发标签”`<proxy_sender name="..."/>`：
  - 构建：`AIMessageManager.buildUserMessageContent(...)`
  - 解析显示：`BubbleUserMessageComposable.kt`
- AI消息已持久化角色名 `roleName`：
  - `ChatMessage.kt` / `MessageEntity.kt`
- 已有“角色卡主题绑定”和“Waifu配置绑定”：
  - `UserPreferencesManager` 的 `character_card_theme_*`
  - `WaifuPreferences` 的 `character_card_waifu_*`

## 2. 目标能力拆解

新增一种“群组角色卡（Group Card）”，要求：
1. 在提示词管理页新增独立 Tab：`群组`
2. 群组可包含多个角色卡成员
3. 群组配置支持：
   - 增删成员
   - 拖动发言顺序
   - 发言概率
   - 随机性调节
   - 发言间隔
4. 选中群组后，用户发一句，系统自动按群组策略轮流发言
5. 关键：后续角色发言时，必须把前面“用户 + 其他AI”的发言拼到新一轮用户输入里，并标注发言人
6. 群组享受和角色卡相同的主题绑定能力

## 3. 数据结构设计（新增）

建议新增模型：
- `CharacterGroupCard`
  - `id/name/description`
  - `members: List<GroupMemberConfig>`
  - `enabled`
  - `roundsPerUserMessage`（一次用户输入触发几轮）
  - `globalMinIntervalMs/globalMaxIntervalMs`
  - `randomness`（0~1）
  - `createdAt/updatedAt`
- `GroupMemberConfig`
  - `characterCardId`
  - `orderIndex`
  - `speakProbability`（0~1）
  - `randomnessBias`（可选）
  - `minIntervalMs/maxIntervalMs`（成员级覆盖）
  - `enabled`

存储建议：
- 新建 `CharacterGroupCardManager`，DataStore：`character_groups`
- key 风格与角色卡一致，避免和聊天分组 `group` 字段冲突（命名统一叫 `character_group_*`）

## 4. UI 改造规划

## 4.1 提示词管理页新增第三个 Tab
- 文件：`ModelPromptsSettingsScreen.kt`
- 现有 `currentTab` 从 2 页扩展为 3 页：
  - `0 角色卡`
  - `1 标签`
  - `2 群组`

## 4.2 新增群组管理界面
- `GroupCardTab`（列表）
  - 新建/编辑/删除/复制群组
  - 显示成员数、启用状态
- `GroupCardDialog`（编辑器）
  - 成员选择（从现有角色卡池）
  - 拖动排序（若先做 MVP，可先上下移动按钮，后续再上拖拽）
  - 概率、随机性、间隔参数输入

## 4.3 聊天界面选择器扩展
- `CharacterSelectorPanel.kt` / `ChatScreenHeader.kt`
- 支持选择“角色卡或群组”
- 选中群组后，Header 显示群组名（可加标记“群组”）

## 5. 对话调度方案（核心）

新增 `GroupConversationOrchestrator`（建议放 `services/core`）。

触发时机：
- 在 `MessageCoordinationDelegate.sendMessageInternal(...)` 里检测当前是否激活群组
- 若激活群组，进入“群组模式流程”；否则走原流程

群组模式流程（建议）：
1. 用户原始消息先入库（保留可见）
2. 生成本轮“候选发言成员序列”
   - 基于顺序 + 概率 + 随机性筛选
3. 逐个成员执行发送（串行，等待上一个成员完成）
4. 每个成员发送前构造“拼接上下文用户输入”，格式示例：
   - `用户: ...`
   - `AI(角色A): ...`
   - `AI(角色B): ...`
   - `请你以 角色C 身份继续发言`
5. 调用现有发送接口时指定 `roleCardIdOverride = 当前成员角色卡ID`
6. 成员间按配置延时（发言间隔）

关键实现点：
- 当前 `AIMessageManager.getMemoryFromMessages(...)` 不带 `roleName`，所以“标注发言人拼接”必须在群组编排器里额外构造消息文本，不能只依赖现有记忆提取。

## 6. 主题绑定能力复用方案

为了让群组也享受主题绑定，建议把现有“角色卡主题绑定”抽象成“实体主题绑定”：
- `UserPreferencesManager`
  - 从 `character_card_theme_${id}_*` 扩展为可支持 `character_group_theme_${id}_*`
- `WaifuPreferences`
  - 从 `character_card_waifu_${id}_*` 扩展为可支持 `character_group_waifu_${id}_*`

然后在 `CharacterGroupCardManager.setActiveGroup(...)` 时：
- `switchToGroupTheme(groupId)`
- `switchToGroupWaifuSettings(groupId)`

## 7. 聊天绑定与持久化（建议）

当前聊天只绑定 `characterCardName`（`ChatEntity.characterCardName`）。

建议新增：
- `ChatEntity.characterGroupId: String?`
- `ChatHistory.characterGroupId: String?`
- Room 迁移：数据库版本 +1，`ALTER TABLE chats ADD COLUMN characterGroupId TEXT`

这样切换会话时可自动恢复群组模式，而不是仅全局临时状态。

## 8. 分阶段落地

### Phase 1（MVP）
1. 新增群组模型 + Manager + DataStore
2. 提示词管理页新增“群组”Tab（先做可编辑，不做拖拽）
3. 聊天支持选择群组
4. 编排器实现串行自动发言 + 发言间隔
5. 实现“拼接前序 AI/用户内容并标注发言人”

### Phase 2（增强）
1. 成员拖拽排序
2. 概率和随机性策略精细化
3. 聊天级群组绑定（DB 字段 + 自动恢复）
4. 群组主题/Waifu绑定完整接入

## 9. 风险与注意点

1. 命名冲突风险：
   - 项目已有“聊天分组 group”，新能力必须统一命名为 `character_group`，避免歧义
2. 历史消息膨胀：
   - 多轮自动发言会快速增长上下文，需配合总结阈值与轮次数限制
3. 并发风险：
   - 群组发言必须串行等待前一个角色完成，防止流式输出重叠
4. 兼容风险：
   - 已发布版本需走向前兼容（新增字段 + 保持旧流程可用）

## 10. 实施前需你确认（按你的仓库规则）

这次属于“方案扩展/升级”。请先确认：
1. 当前线上是否已发布这个版本？
2. 如果已发布，我按“向前兼容迁移”执行；
3. 如果未发布，可按“彻底重构清理旧方案”执行。

