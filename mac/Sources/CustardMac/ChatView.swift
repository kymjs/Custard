import SwiftUI

@MainActor
final class ChatViewModel: ObservableObject {
    @Published var messages: [ChatMessage] = []
    @Published private(set) var displayItems: [ChatDisplayItem] = []
    @Published var inputText = ""
    @Published var isSending = false
    @Published var agentStatus = ""
    @Published var isDebugPaused = false
    @Published var isAwaitingPlanConfirmation = false

    private let maxAdbExecutions = 12
    private let maxConsecutiveGetScreen = 4
    private let maxConsecutiveUiCaptureFailures = 1
    private static let screenRequestMarker = "[REQUEST_SCREEN]"
    private static let userInputMarker = "[NEEDS_USER_INPUT]"
    private static let confirmedStartMessage = "已确认，开始执行。"
    private static let displayContentLimit = 4000
    private static let maxEmptySuccessRejections = 2
    private static let progressiveToolNames: Set<String> = [
        LLMTools.tapScreenName,
        LLMTools.typeTextName,
        LLMTools.openAppName,
        LLMTools.pressHomeName,
        LLMTools.pressBackName,
        LLMTools.writeClipboardName
    ]
    private weak var connection: ConnectionManager?
    private var taskState: TaskState?
    private var consecutiveUiCaptureFailures = 0
    private let experienceStore = TaskExperienceStore()
    private let tapCoordinateStore = TapCoordinateStore()
    private var currentTaskGoal = ""
    private var currentTaskCategory = ""
    private var currentExperienceContext: String?
    private var currentTapCoordinateContext: String?
    private var lastLocateResult: LastLocateResult?
    private var lastUiTreeSummary: String?
    private var lastForegroundPackageName: String?
    private var pendingTapExperience: PendingTapExperience?
    private var allSubtasksExplicitlySucceeded = true
    private var taskSessionStartIndex: Int?
    private var debugStepArmed = false
    private var debugDecisionContinuation: CheckedContinuation<Bool, Never>?
    private var agentTask: Task<Void, Never>?
    private var stopRequested = false

    private struct TaskState {
        var tasks: [String]
        var currentIndex: Int
        var waitingForClarification: Bool
        var waitingForPlanConfirmation: Bool
    }

    var canSendEmpty: Bool {
        isAwaitingPlanConfirmation || (taskState?.waitingForClarification == true)
    }

    func rebuildDisplayItems(showThinking: Bool) {
        let lastPreprocessId = messages.last(where: \.isPreprocessResult)?.id
        displayItems = messages.compactMap { message in
            guard message.shouldDisplayInChat(showThinking: showThinking) else { return nil }
            let raw = Self.rawDisplayContent(for: message, showThinking: showThinking)
            return ChatDisplayItem(
                id: message.id,
                message: message,
                displayContent: Self.truncateForDisplay(raw),
                showPlanConfirm: isAwaitingPlanConfirmation
                    && message.isPreprocessResult
                    && message.id == lastPreprocessId
            )
        }
    }

    private static func rawDisplayContent(for message: ChatMessage, showThinking: Bool) -> String {
        if message.isAdbResult {
            if let range = message.content.range(of: "\n\n") {
                return String(message.content[range.upperBound...])
            }
        }
        if message.role == .assistant && !message.isError {
            return message.displayText(showThinking: showThinking)
        }
        return message.content
    }

    private static func truncateForDisplay(_ text: String) -> String {
        guard text.count > displayContentLimit else { return text }
        let end = text.index(text.startIndex, offsetBy: displayContentLimit)
        return String(text[..<end]) + "\n…(已截断)"
    }

    private func appendChatMessage(_ message: ChatMessage) {
        messages.append(message)
        rebuildDisplayItems(showThinking: AppPreferences.showThinkingContentEnabled)
    }

    private func stripHistoricalScreenshotImages(keepingMessageId: UUID?) {
        guard messages.contains(where: { $0.imageBase64 != nil && $0.id != keepingMessageId }) else {
            return
        }
        messages = messages.map { message in
            guard message.imageBase64 != nil, message.id != keepingMessageId else { return message }
            return message.replacingImageBase64(nil)
        }
    }

    private func setAwaitingPlanConfirmation(_ value: Bool) {
        guard isAwaitingPlanConfirmation != value else { return }
        isAwaitingPlanConfirmation = value
        rebuildDisplayItems(showThinking: AppPreferences.showThinkingContentEnabled)
    }

    func configure(connection: ConnectionManager) {
        self.connection = connection
    }

    func send() {
        guard !isSending else { return }
        let text = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty {
            if isAwaitingPlanConfirmation {
                confirmPlan()
            } else if taskState?.waitingForClarification == true {
                resumeWithoutUserText()
            }
            return
        }

        clearPlanConfirmationState()
        inputText = ""
        appendChatMessage(ChatMessage(role: .user, content: text))
        startAgentTask {
            await self.runAgentLoop()
        }
    }

    func confirmPlan() {
        guard !isSending else { return }
        guard isAwaitingPlanConfirmation,
              let state = taskState,
              !state.tasks.isEmpty else { return }

        clearPlanConfirmationState()
        appendChatMessage(
            ChatMessage(
                role: .assistant,
                content: Self.confirmedStartMessage,
                isConclusion: true
            )
        )
        startAgentTask {
            await self.executeConfirmedTasks()
        }
    }

    private func resumeWithoutUserText() {
        guard !isSending else { return }
        guard taskState?.waitingForClarification == true else { return }
        startAgentTask {
            await self.runAgentLoop()
        }
    }

    private func clearPlanConfirmationState() {
        setAwaitingPlanConfirmation(false)
        if var state = taskState {
            state.waitingForPlanConfirmation = false
            taskState = state
        }
    }

    private func startAgentTask(_ work: @escaping @MainActor () async -> Void) {
        isSending = true
        agentStatus = "准备中..."
        consecutiveUiCaptureFailures = 0
        lastUiTreeSummary = nil
        lastForegroundPackageName = nil
        stopRequested = false
        Logger.chat("start agent task showThinking=\(AppPreferences.showThinkingContentEnabled)")

        agentTask = Task {
            defer {
                if stopRequested {
                    appendChatMessage(
                        ChatMessage(
                            role: .assistant,
                            content: "任务已终止",
                            isConclusion: true
                        )
                    )
                    stopRequested = false
                }
                isSending = false
                agentStatus = ""
                agentTask = nil
                Logger.chat("agent loop finished")
            }
            await work()
        }
    }

    func stop() {
        guard isSending else { return }
        stopRequested = true
        taskState = nil
        setAwaitingPlanConfirmation(false)
        if isDebugPaused {
            debugDecisionContinuation?.resume(returning: false)
            debugDecisionContinuation = nil
            isDebugPaused = false
        }
        agentTask?.cancel()
    }

    func continueDebugging() {
        debugDecisionContinuation?.resume(returning: true)
        debugDecisionContinuation = nil
        isDebugPaused = false
    }

    func stopDebugging() {
        stop()
    }

    private func waitForDebugDecision() async -> Bool {
        guard AppPreferences.stepDebugEnabled else { return true }
        isDebugPaused = true
        agentStatus = "断点调试中，等待确认..."
        return await withCheckedContinuation { continuation in
            debugDecisionContinuation = continuation
        }
    }

    private func setAgentStatus(_ status: String) {
        agentStatus = status
        Logger.chat("status: \(status)")
    }

    private func appendAssistantMessage(from result: LLMChatResult) {
        let storedContent: String
        if !result.content.isEmpty {
            storedContent = result.content
        } else if let reasoning = result.reasoning, !reasoning.isEmpty {
            storedContent = reasoning
        } else {
            storedContent = ""
        }

        let display = ChatMessage(
            role: .assistant,
            content: storedContent,
            reasoning: result.reasoning
        ).displayText(showThinking: AppPreferences.showThinkingContentEnabled)

        Logger.chat(
            "append assistant storedLen=\(storedContent.count) reasoningLen=\(result.reasoning?.count ?? 0) "
            + "displayLen=\(display.count) showThinking=\(AppPreferences.showThinkingContentEnabled)"
        )
        if display.isEmpty && !storedContent.isEmpty {
            Logger.chat("WARN display empty, stored preview: \(Self.preview(storedContent))")
        }

        appendChatMessage(
            ChatMessage(
                role: .assistant,
                content: storedContent,
                reasoning: result.reasoning,
                reasoningDetailsJSON: result.reasoningDetailsJSON,
                isConclusion: result.taskStatus != nil || (!result.content.isEmpty && !result.hasToolCalls)
            )
        )
    }

    private static func preview(_ text: String, limit: Int = 200) -> String {
        if text.count <= limit { return text.replacingOccurrences(of: "\n", with: "\\n") }
        let end = text.index(text.startIndex, offsetBy: limit)
        return String(text[..<end]).replacingOccurrences(of: "\n", with: "\\n") + "..."
    }

    private func appendToolResult(name: String, content: String, toolCallId: String) {
        Logger.chat("tool result name=\(name) id=\(toolCallId) len=\(content.count):\n\(content)")
        appendChatMessage(
            ChatMessage(
                role: .tool,
                content: content,
                isToolResult: true,
                toolCallId: toolCallId
            )
        )
    }

    private func runAgentLoop() async {
        debugStepArmed = false
        let isContinuation = taskState != nil && !currentTaskGoal.isEmpty
        if !isContinuation {
            currentTaskGoal = messages.last(where: { $0.role == .user && !$0.isScreenContext })?.content ?? ""
            currentTaskCategory = ""
            currentExperienceContext = nil
            currentTapCoordinateContext = nil
            lastLocateResult = nil
            pendingTapExperience = nil
            taskSessionStartIndex = max(messages.count - 1, 0)
        }
        allSubtasksExplicitlySucceeded = true
        if !currentTaskGoal.isEmpty && currentTaskCategory.isEmpty {
            do {
                setAgentStatus("正在检索历史经验...")
                let classification = try await LLMService.classifyTask(currentTaskGoal)
                currentTaskCategory = classification.category
                let experiences = experienceStore.matching(category: classification.category)
                if !experiences.isEmpty {
                    currentExperienceContext = experiences.map { experience in
                        """
                        类别：\(experience.category)
                        目标：\(experience.goal)
                        操作步骤：
                        \(experience.steps.enumerated().map { "\($0.offset + 1). \($0.element)" }.joined(separator: "\n"))
                        """
                    }.joined(separator: "\n\n")
                }
            } catch is CancellationError {
                return
            } catch {
                Logger.error(error, context: "task experience lookup")
            }
        }

        if Task.isCancelled || stopRequested { return }

        await invokeGetScreen(
            includeScreenshot: AppPreferences.operationImageEnabled,
            source: "任务预处理"
        )

        let stateDescription: String?
        if let taskState, !taskState.tasks.isEmpty {
            stateDescription = """
            已有任务：\(taskState.tasks.enumerated().map { "\($0.offset + 1). \($0.element)" }.joined(separator: "\n"))
            当前任务序号：\(taskState.currentIndex + 1)
            当前任务状态：\(taskState.waitingForPlanConfirmation ? "等待用户确认任务拆分" : (taskState.waitingForClarification ? "等待用户补充信息" : "执行中断后等待恢复"))
            \(currentExperienceContext.map { "\n历史成功经验：\n\($0)" } ?? "")
            \(currentTapCoordinateContext.map { "\n已知点击坐标经验：\n\($0)" } ?? "")
            """
        } else {
            let parts = [
                currentExperienceContext.map { "历史成功经验：\n\($0)" },
                currentTapCoordinateContext.map { "已知点击坐标经验：\n\($0)" }
            ].compactMap { $0 }
            stateDescription = parts.isEmpty ? nil : parts.joined(separator: "\n\n")
        }

        if Task.isCancelled || stopRequested { return }

        let preprocessResult: LLMPreprocessResult
        do {
            setAgentStatus("正在预处理任务...")
            preprocessResult = try await LLMService.preprocess(
                messages: messages,
                stateDescription: stateDescription
            )
        } catch is CancellationError {
            return
        } catch {
            Logger.error(error, context: "task preprocessing")
            let fallback = messages.last(where: { $0.role == .user && !$0.isScreenContext })?.content ?? ""
            preprocessResult = LLMPreprocessResult(
                action: .execute,
                displayText: "任务预处理失败，将按当前任务继续执行。",
                tasks: fallback.isEmpty ? ["根据当前对话继续完成任务"] : [fallback],
                rawAssistantText: ""
            )
        }

        if Task.isCancelled || stopRequested { return }

        appendChatMessage(
            ChatMessage(
                role: .assistant,
                content: preprocessResult.displayText,
                apiReplayContent: preprocessResult.rawAssistantText.isEmpty ? nil : preprocessResult.rawAssistantText,
                isPreprocessResult: true,
                isConclusion: true
            )
        )
        if preprocessResult.action == .clarify {
            taskState = TaskState(
                tasks: taskState?.tasks ?? [],
                currentIndex: taskState?.currentIndex ?? 0,
                waitingForClarification: true,
                waitingForPlanConfirmation: false
            )
            setAwaitingPlanConfirmation(false)
            return
        }

        taskState = TaskState(
            tasks: preprocessResult.tasks,
            currentIndex: 0,
            waitingForClarification: false,
            waitingForPlanConfirmation: true
        )
        setAwaitingPlanConfirmation(true)
        setAgentStatus("等待确认任务拆分...")
    }

    private func executeConfirmedTasks() async {
        guard let tasks = taskState?.tasks, !tasks.isEmpty else {
            taskState = nil
            setAwaitingPlanConfirmation(false)
            return
        }
        allSubtasksExplicitlySucceeded = true
        for index in tasks.indices {
            if Task.isCancelled || stopRequested { return }
            taskState?.currentIndex = index
            taskState?.waitingForClarification = false
            taskState?.waitingForPlanConfirmation = false
            let shouldContinue = await runSingleTask(tasks[index], taskIndex: index, allTasks: tasks)
            if !shouldContinue {
                return
            }
        }
        if allSubtasksExplicitlySucceeded {
            await saveSuccessfulExperience()
        }
        taskState = nil
        setAwaitingPlanConfirmation(false)
    }

    private func buildSubtaskContext(task: String, taskIndex: Int, allTasks: [String]) -> String {
        let completed: String
        if taskIndex == 0 {
            completed = "无"
        } else {
            completed = allTasks.prefix(taskIndex).enumerated()
                .map { "\($0.offset + 1). \($0.element)" }
                .joined(separator: "\n")
        }
        return """
        当前子任务序号：\(taskIndex + 1)/\(allTasks.count)
        当前子任务：\(task)
        此前已完成子任务：
        \(completed)

        硬性规则：
        - 只完成当前子任务，不得执行后续子任务
        - 不得用「上一子任务已完成」作为本子任务 success 理由
        - 无推进性操作（点击/输入/开应用/按键/写剪贴板/ADB）时不得返回 status=success
        """
    }

    private func countProgressiveToolCalls(in result: LLMChatResult) -> Int {
        guard let calls = result.toolCalls else { return 0 }
        return calls.filter { Self.progressiveToolNames.contains($0.name) }.count
    }

    /// Returns false when the subtask must pause for the user (rejection limit reached).
    private func rejectEmptyProgression(
        task: String,
        reason: String,
        emptySuccessRejections: inout Int
    ) -> Bool {
        emptySuccessRejections += 1
        Logger.chat(
            "reject empty progression reason=\(reason) "
            + "attempt=\(emptySuccessRejections)/\(Self.maxEmptySuccessRejections) task=\(task)"
        )
        if emptySuccessRejections > Self.maxEmptySuccessRejections {
            appendChatMessage(
                ChatMessage(
                    role: .assistant,
                    content: "当前子任务未实际执行操作，已暂停。请补充说明或输入「继续」后重试。",
                    isError: true,
                    isConclusion: true
                )
            )
            taskState?.waitingForClarification = true
            allSubtasksExplicitlySucceeded = false
            return false
        }
        appendChatMessage(
            ChatMessage(
                role: .user,
                content: """
                系统反馈：当前子任务「\(task)」本轮未检测到任何推进性操作（点击/输入/开应用/按键/写剪贴板/ADB）。
                不得把「上一步已完成」当作本步 success。请继续调用工具或输出 ```adb ...``` 完成本子任务；\
                若信息不足请返回 {"status":"needs_user_input","message":"..."}；\
                若无法完成请返回 {"status":"failed","message":"..."}。
                """,
                isAdbResult: true
            )
        )
        return true
    }

    private func runSingleTask(_ task: String, taskIndex: Int, allTasks: [String]) async -> Bool {
        let maxLLMTurns = AppPreferences.maxLLMTurns
        var llmTurn = 0
        var adbExecutions = 0
        var screenRequestRounds = 0
        var consecutiveGetScreen = 0
        var progressiveActions = 0
        var emptySuccessRejections = 0
        let subtaskContext = buildSubtaskContext(task: task, taskIndex: taskIndex, allTasks: allTasks)

        while llmTurn < maxLLMTurns {
            if Task.isCancelled || stopRequested { return false }
            llmTurn += 1
            do {
                setAgentStatus("正在请求大模型（第 \(llmTurn) 轮）...")
                let llmStart = Date()
                let extras = [
                    currentExperienceContext.map { "可参考的历史成功经验：\n\($0)" },
                    currentTapCoordinateContext.map { "已知点击坐标经验：\n\($0)" }
                ].compactMap { $0 }.joined(separator: "\n\n")
                let taskWithExperience = extras.isEmpty
                    ? subtaskContext
                    : "\(subtaskContext)\n\n\(extras)"
                let result = try await LLMService.chat(messages: messages, taskContext: taskWithExperience)
                Logger.chat(
                    "LLM turn \(llmTurn)/\(maxLLMTurns) done in \(String(format: "%.2f", Date().timeIntervalSince(llmStart)))s "
                    + "contentLen=\(result.content.count) reasoningLen=\(result.reasoning?.count ?? 0) "
                    + "toolCalls=\(result.toolCalls?.count ?? 0) adbExecutions=\(adbExecutions) "
                    + "progressiveActions=\(progressiveActions)"
                )

                if result.hasToolCalls {
                    let onlyGetScreen = result.toolCalls?.allSatisfy { $0.name == LLMTools.getScreenName } ?? false
                    if onlyGetScreen {
                        consecutiveGetScreen += 1
                        if consecutiveGetScreen > maxConsecutiveGetScreen {
                            Logger.chat("consecutive get_screen limit reached (\(maxConsecutiveGetScreen))")
                            appendChatMessage(
                                ChatMessage(
                                    role: .user,
                                    content: """
                                    已连续调用 get_screen \(consecutiveGetScreen) 次。请根据已有截图和 UI 信息直接输出 ```adb ...``` 命令执行操作，不要再次调用 get_screen，除非刚刚执行了会改变界面的 adb 命令。
                                    """,
                                    isAdbResult: true
                                )
                            )
                            consecutiveGetScreen = 0
                            continue
                        }
                    } else {
                        consecutiveGetScreen = 0
                    }

                    progressiveActions += countProgressiveToolCalls(in: result)
                    let shouldPauseAfterAction = debugStepArmed
                    await handleToolCalls(
                        from: result,
                        onlyFirst: AppPreferences.stepDebugEnabled
                    )
                    debugStepArmed = AppPreferences.stepDebugEnabled
                    if shouldPauseAfterAction {
                        let shouldContinue = await waitForDebugDecision()
                        if !shouldContinue {
                            return false
                        }
                    }
                    continue
                }

                consecutiveGetScreen = 0
                if result.taskStatus == .needsUserInput {
                    appendAssistantMessage(from: result)
                    taskState?.waitingForClarification = true
                    return false
                }
                if result.taskStatus == .failed {
                    appendAssistantMessage(from: result)
                    allSubtasksExplicitlySucceeded = false
                    return false
                }
                if result.taskStatus == .success {
                    if progressiveActions == 0 {
                        appendAssistantMessage(from: result)
                        if !rejectEmptyProgression(
                            task: task,
                            reason: "success_without_progressive_action",
                            emptySuccessRejections: &emptySuccessRejections
                        ) {
                            return false
                        }
                        continue
                    }
                    appendAssistantMessage(from: result)
                    return true
                }
                let executionText = result.textForExecution
                if executionText.contains(Self.userInputMarker) {
                    let cleanedContent = result.content
                        .replacingOccurrences(of: Self.userInputMarker, with: "")
                        .trimmingCharacters(in: .whitespacesAndNewlines)
                    let cleanedReasoning = result.reasoning?
                        .replacingOccurrences(of: Self.userInputMarker, with: "")
                        .trimmingCharacters(in: .whitespacesAndNewlines)
                    appendChatMessage(
                        ChatMessage(
                            role: .assistant,
                            content: cleanedContent.isEmpty ? (cleanedReasoning ?? "") : cleanedContent,
                            reasoning: cleanedReasoning
                        )
                    )
                    taskState?.waitingForClarification = true
                    allSubtasksExplicitlySucceeded = false
                    return false
                }

                if executionText.contains(Self.screenRequestMarker) {
                    screenRequestRounds += 1
                    Logger.chat("REQUEST_SCREEN detected round=\(screenRequestRounds)")
                    appendAssistantMessage(from: result)

                    if !AppPreferences.isToolEnabled(id: LLMTools.getScreenName) {
                        appendChatMessage(
                            ChatMessage(
                                role: .user,
                                content: CustardToolRegistry.disabledToolResult(for: LLMTools.getScreenName),
                                isScreenContext: true
                            )
                        )
                    continue
                    }

                    if screenRequestRounds > maxConsecutiveGetScreen {
                        appendChatMessage(
                            ChatMessage(
                                role: .assistant,
                                content: "已多次请求屏幕刷新，请根据已有信息继续操作。",
                                isError: true
                            )
                        )
                        continue
                    }

                    await invokeGetScreen(includeScreenshot: true, source: "REQUEST_SCREEN")
                    continue
                }

                appendAssistantMessage(from: result)
                allSubtasksExplicitlySucceeded = false

                setAgentStatus("正在解析命令...")
                let commands = await executeAdbCommands(from: executionText)
                Logger.chat("extracted \(commands.count) adb command(s)")
                guard !commands.isEmpty else {
                    Logger.chat("empty end without status/adb progressiveActions=\(progressiveActions)")
                    if !rejectEmptyProgression(
                        task: task,
                        reason: "no_status_no_adb",
                        emptySuccessRejections: &emptySuccessRejections
                    ) {
                        return false
                    }
                    continue
                }

                progressiveActions += 1
                adbExecutions += 1
                if adbExecutions > maxAdbExecutions {
                    Logger.chat("adb execution limit reached (\(maxAdbExecutions))")
                    appendChatMessage(
                        ChatMessage(
                            role: .assistant,
                            content: "已达到最大 ADB 执行次数（\(maxAdbExecutions) 次），请继续输入指令或手动操作。",
                            isError: true
                        )
                    )
                    return false
                }

                setAgentStatus("正在执行 ADB 命令（第 \(adbExecutions) 次）...")
                let connectionRef = self.connection
                AdbManager.logDiagnostics(context: "before-execute")
                let results = await AdbManager.executeCommandsAsync(commands, connection: connectionRef)

                let formatted = AdbManager.formatExecutionResults(results)
                Logger.chat("adb execution result len=\(formatted.count):\n\(formatted)")
                let feedback = """
                以下是 ADB 命令执行结果，请根据结果继续完成任务。若需查看当前界面请调用 get_screen；若任务已完成，请直接告知用户结果，不要再输出 adb 命令。

                \(formatted)
                """
                appendChatMessage(
                    ChatMessage(
                        role: .user,
                        content: feedback,
                        isAdbResult: true
                    )
                )
            } catch is CancellationError {
                return false
            } catch {
                Logger.error(error, context: "agent loop turn \(llmTurn)")
                appendChatMessage(
                    ChatMessage(
                        role: .assistant,
                        content: error.localizedDescription,
                        isError: true
                    )
                )
                return false
            }
        }

        Logger.chat("agent loop exhausted after \(maxLLMTurns) LLM turns")
        appendChatMessage(
            ChatMessage(
                role: .assistant,
                content: "已达到最大推理轮次（\(maxLLMTurns) 次），任务可能尚未完成。请继续输入指令。",
                isError: true
            )
        )
        allSubtasksExplicitlySucceeded = false
        taskState?.waitingForClarification = true
        return false
    }

    private func saveSuccessfulExperience() async {
        guard !currentTaskGoal.isEmpty,
              !currentTaskCategory.isEmpty else { return }
        do {
            setAgentStatus("正在总结成功经验...")
            let summary = try await LLMService.summarizeSuccessfulTask(
                goal: currentTaskGoal,
                category: currentTaskCategory,
                messages: Array(messages.dropFirst(taskSessionStartIndex ?? 0))
            )
            experienceStore.save(summary: summary)
            Logger.chat("saved task experience category=\(summary.category) steps=\(summary.steps.count)")
            taskSessionStartIndex = nil
        } catch {
            Logger.error(error, context: "task experience summary")
        }
    }

    private func handleToolCalls(from result: LLMChatResult, onlyFirst: Bool = false) async {
        guard let toolCalls = result.toolCalls else { return }
        let callsToExecute = onlyFirst ? Array(toolCalls.prefix(1)) : toolCalls

        appendChatMessage(
            ChatMessage(
                role: .assistant,
                content: result.content,
                reasoning: result.reasoning,
                reasoningDetailsJSON: result.reasoningDetailsJSON,
                toolCalls: callsToExecute
            )
        )

        for call in callsToExecute {
            guard AppPreferences.isToolEnabled(id: call.name) else {
                appendToolResult(
                    name: call.name,
                    content: CustardToolRegistry.disabledToolResult(for: call.name),
                    toolCallId: call.id
                )
                continue
            }

            switch call.name {
            case LLMTools.getScreenName:
                let args = LLMTools.parseGetScreenArguments(call.argumentsJSON)
                let includeScreenshot = args.includeScreenshot ?? AppPreferences.operationImageEnabled
                await invokeGetScreen(includeScreenshot: includeScreenshot, toolCallId: call.id)
            case LLMTools.pressHomeName:
                await invokePressHome(toolCallId: call.id)
            case LLMTools.pressBackName:
                await invokePressBack(toolCallId: call.id)
            case LLMTools.listInstalledAppsName:
                await invokeListInstalledApps(toolCallId: call.id)
            case LLMTools.openAppName:
                let args = LLMTools.parseOpenAppArguments(call.argumentsJSON)
                await invokeOpenApp(packageOrName: args.packageOrName, toolCallId: call.id)
            case LLMTools.tapScreenName:
                let args = LLMTools.parseTapScreenArguments(call.argumentsJSON)
                await invokeTapScreen(
                    x: args.x,
                    y: args.y,
                    action: args.action,
                    toolCallId: call.id
                )
            case LLMTools.readClipboardName:
                await invokeReadClipboard(toolCallId: call.id)
            case LLMTools.writeClipboardName:
                let args = LLMTools.parseWriteClipboardArguments(call.argumentsJSON)
                await invokeWriteClipboard(text: args.text, toolCallId: call.id)
            case LLMTools.typeTextName:
                let args = LLMTools.parseWriteClipboardArguments(call.argumentsJSON)
                await invokeTypeText(text: args.text, toolCallId: call.id)
            case LLMTools.locateControlName:
                let args = LLMTools.parseLocateControlArguments(call.argumentsJSON)
                await invokeLocateControl(
                    imagePath: args.imagePath,
                    description: args.description,
                    toolCallId: call.id
                )
            default:
                Logger.chat("unknown tool: \(call.name)")
                appendToolResult(
                    name: call.name,
                    content: "未知工具: \(call.name)",
                    toolCallId: call.id
                )
            }
        }
    }

    private func invokePressHome(toolCallId: String) async {
        setAgentStatus("正在按下 Home 键...")
        let result = await PhoneControlTool.pressHome(connection: connection)
        appendToolResult(name: LLMTools.pressHomeName, content: result, toolCallId: toolCallId)
    }

    private func invokePressBack(toolCallId: String) async {
        setAgentStatus("正在按下 Back 键...")
        let result = await PhoneControlTool.pressBack(connection: connection)
        appendToolResult(name: LLMTools.pressBackName, content: result, toolCallId: toolCallId)
    }

    private func invokeListInstalledApps(toolCallId: String) async {
        setAgentStatus("正在获取已安装应用...")
        let result = await InstalledAppTool.listInstalledAppsText()
        appendToolResult(name: LLMTools.listInstalledAppsName, content: result, toolCallId: toolCallId)
    }

    private func invokeOpenApp(packageOrName: String, toolCallId: String) async {
        setAgentStatus("正在打开应用...")
        let result = await InstalledAppTool.openApp(packageOrName: packageOrName)
        appendToolResult(name: LLMTools.openAppName, content: result, toolCallId: toolCallId)
    }

    private func invokeTapScreen(
        x: Double?,
        y: Double?,
        action: String?,
        toolCallId: String
    ) async {
        guard let x, let y else {
            appendToolResult(
                name: LLMTools.tapScreenName,
                content: "请提供 x 和 y（屏幕像素坐标）。",
                toolCallId: toolCallId
            )
            return
        }

        guard let tapAction = ScreenTapAction.parse(action) else {
            appendToolResult(
                name: LLMTools.tapScreenName,
                content: "无效的 action。请使用 tap（单击）、double_tap（双击）或 long_press（长按）。",
                toolCallId: toolCallId
            )
            return
        }

        setAgentStatus("正在\(tapAction.displayName)屏幕像素坐标 (\(x), \(y))...")
        let uiTreeResult = await UiTreeResolver.resolve(
            connection: connection,
            cachedTree: lastUiTreeSummary,
            preferAdbFallback: false,
            accessibilityTimeoutSeconds: 3,
            retryAccessibility: false
        )
        let beforeUi = TapCoordinateMatcher.truncateUi(uiTreeResult.text ?? "")
        let packageName = lastForegroundPackageName ?? ""
        let screenWidth = connection?.deviceInfo?.width ?? 0
        let screenHeight = connection?.deviceInfo?.height ?? 0
        let result = await ScreenTapTool.tapAtPixel(
            x: x,
            y: y,
            action: tapAction,
            connection: connection
        )
        if ScreenTapTool.isSuccessMessage(result), screenWidth > 0, screenHeight > 0 {
            maybeRecordPendingTap(
                tapX: x,
                tapY: y,
                packageName: packageName,
                screenWidth: screenWidth,
                screenHeight: screenHeight,
                beforeUiSummary: beforeUi
            )
        }
        appendToolResult(name: LLMTools.tapScreenName, content: result, toolCallId: toolCallId)
    }

    private func invokeReadClipboard(toolCallId: String) async {
        setAgentStatus("正在读取手机剪贴板...")
        let result = await ClipboardTool.readClipboardText(connection: connection)
        appendToolResult(name: LLMTools.readClipboardName, content: result, toolCallId: toolCallId)
    }

    private func invokeWriteClipboard(text: String, toolCallId: String) async {
        setAgentStatus("正在写入手机剪贴板...")
        let result = ClipboardTool.writeClipboardText(text: text, connection: connection)
        appendToolResult(name: LLMTools.writeClipboardName, content: result, toolCallId: toolCallId)
    }

    private func invokeTypeText(text: String, toolCallId: String) async {
        setAgentStatus("正在输入文本...")
        let result = await KeyboardInputTool.typeTextJSON(text, connection: connection)
        appendToolResult(name: LLMTools.typeTextName, content: result, toolCallId: toolCallId)
    }

    private var shouldSkipUiTreeCollection: Bool {
        consecutiveUiCaptureFailures >= maxConsecutiveUiCaptureFailures
    }

    private func invokeLocateControl(
        imagePath: String,
        description: String,
        toolCallId: String
    ) async {
        setAgentStatus("正在定位控件...")
        var screenWidth: Int?
        var screenHeight: Int?
        if let connection, let info = connection.deviceInfo, info.width > 0, info.height > 0 {
            screenWidth = info.width
            screenHeight = info.height
        }
        let uiTreeText: String?
        let uiTreeSource: String
        if shouldSkipUiTreeCollection {
            uiTreeText = nil
            uiTreeSource = "skipped"
            Logger.chat("locate_control: ui_tree skipped after failure threshold")
        } else {
            let uiTreeResult = await UiTreeResolver.resolve(
                connection: connection,
                cachedTree: lastUiTreeSummary
            )
            uiTreeText = uiTreeResult.text
            uiTreeSource = uiTreeResult.source
        }
        Logger.chat(
            "locate_control invoke image=\(imagePath) descLen=\(description.count) "
            + "uiTreeLen=\(uiTreeText?.count ?? 0) uiTreeSource=\(uiTreeSource) "
            + "screen=\(screenWidth.map(String.init) ?? "?")x\(screenHeight.map(String.init) ?? "?")"
        )
        let result = await LocateControlTool.locate(
            imagePath: imagePath,
            description: description,
            uiTreeText: uiTreeText,
            screenWidth: screenWidth,
            screenHeight: screenHeight
        )
        onLocateControlResult(description: description, content: result)
        appendToolResult(name: LLMTools.locateControlName, content: result, toolCallId: toolCallId)
    }

    private func invokeGetScreen(
        includeScreenshot: Bool,
        toolCallId: String? = nil,
        source: String = "get_screen"
    ) async {
        guard let connection else {
            let errorText = "屏幕共享未连接，无法获取屏幕。"
            if let toolCallId {
                appendToolResult(
                    name: LLMTools.getScreenName,
                    content: errorText,
                    toolCallId: toolCallId
                )
            } else {
                appendChatMessage(
                    ChatMessage(role: .user, content: errorText, isScreenContext: true)
                )
            }
            return
        }

        setAgentStatus("正在获取屏幕（\(source)）...")
        let collectUiSources = consecutiveUiCaptureFailures < maxConsecutiveUiCaptureFailures
        let payload = await ScreenCaptureTool.capture(
            connection: connection,
            includeScreenshot: includeScreenshot,
            collectUiSources: collectUiSources
        )

        if collectUiSources {
            if payload.hasSuccessfulUiSource {
                if consecutiveUiCaptureFailures > 0 {
                    Logger.chat(
                        "get_screen UI source recovered after "
                        + "\(consecutiveUiCaptureFailures) failure(s)"
                    )
                }
                consecutiveUiCaptureFailures = 0
            } else {
                consecutiveUiCaptureFailures += 1
                Logger.chat(
                    "get_screen all Activity/UI sources failed "
                    + "consecutive=\(consecutiveUiCaptureFailures)/\(maxConsecutiveUiCaptureFailures)"
                )
            }
        } else {
            Logger.chat("get_screen UI source collection skipped after failure threshold")
        }

        if let uiTreeSummary = payload.uiTreeSummary, !uiTreeSummary.isEmpty {
            lastUiTreeSummary = uiTreeSummary
        }
        if let packageName = payload.packageName, !packageName.isEmpty {
            lastForegroundPackageName = packageName
        }

        let description = await enrichScreenWithTapCoordinates(payload)

        if let toolCallId {
            appendToolResult(
                name: LLMTools.getScreenName,
                content: description,
                toolCallId: toolCallId
            )
            if let base64 = payload.imageBase64 {
                let screenshotMessage = ChatMessage(
                    role: .user,
                    content: "【get_screen 截图】",
                    isScreenContext: true,
                    imageBase64: base64
                )
                messages.append(screenshotMessage)
                stripHistoricalScreenshotImages(keepingMessageId: screenshotMessage.id)
                rebuildDisplayItems(showThinking: AppPreferences.showThinkingContentEnabled)
            }
        } else {
            Logger.chat(
                "screen context (\(source)) len=\(description.count):\n\(description)"
            )
            let screenMessage = ChatMessage(
                role: .user,
                content: description,
                isScreenContext: true,
                imageBase64: payload.imageBase64
            )
            messages.append(screenMessage)
            if payload.imageBase64 != nil {
                stripHistoricalScreenshotImages(keepingMessageId: screenMessage.id)
            }
            rebuildDisplayItems(showThinking: AppPreferences.showThinkingContentEnabled)
        }
    }

    private func onLocateControlResult(description: String, content: String) {
        guard let coords = TapCoordinateMatcher.parseLocateCoordinates(content) else {
            lastLocateResult = nil
            return
        }
        let label = description.trimmingCharacters(in: .whitespacesAndNewlines)
        if let pending = pendingTapExperience,
           pending.controlLabel.compare(label, options: .caseInsensitive) == .orderedSame {
            Logger.chat("discard pending tap experience: same control re-located label=\(label)")
            pendingTapExperience = nil
        }
        lastLocateResult = LastLocateResult(description: label, x: coords.0, y: coords.1)
    }

    private func maybeRecordPendingTap(
        tapX: Double,
        tapY: Double,
        packageName: String,
        screenWidth: Int,
        screenHeight: Int,
        beforeUiSummary: String
    ) {
        guard let locate = lastLocateResult else { return }
        guard TapCoordinateMatcher.withinTolerance(x1: locate.x, y1: locate.y, x2: tapX, y2: tapY) else {
            return
        }
        pendingTapExperience = PendingTapExperience(
            controlLabel: locate.description,
            x: tapX,
            y: tapY,
            screenWidth: screenWidth,
            screenHeight: screenHeight,
            packageName: packageName,
            beforeUiSummary: beforeUiSummary
        )
        Logger.chat(
            "pending tap experience control=\(locate.description) "
            + "px=(\(tapX),\(tapY)) package=\(packageName)"
        )
    }

    private func enrichScreenWithTapCoordinates(_ payload: ScreenCapturePayload) async -> String {
        var description = payload.description
        let afterUi = TapCoordinateMatcher.truncateUi(payload.uiTreeSummary ?? payload.description)
        if let pending = pendingTapExperience {
            pendingTapExperience = nil
            do {
                setAgentStatus("正在校验点击是否命中目标...")
                let verification = try await LLMService.verifyTapHit(
                    controlLabel: pending.controlLabel,
                    packageName: pending.packageName.isEmpty
                        ? (payload.packageName ?? "")
                        : pending.packageName,
                    beforeUiSummary: pending.beforeUiSummary,
                    afterUiSummary: afterUi
                )
                if verification.hit,
                   !verification.pageLabel.isEmpty {
                    tapCoordinateStore.save(
                        summary: TapCoordinateSummary(
                            packageName: verification.packageName.isEmpty
                                ? pending.packageName
                                : verification.packageName,
                            pageLabel: verification.pageLabel,
                            controlLabel: verification.controlLabel,
                            x: Int(pending.x.rounded()),
                            y: Int(pending.y.rounded()),
                            screenWidth: pending.screenWidth,
                            screenHeight: pending.screenHeight
                        )
                    )
                    Logger.chat(
                        "saved tap coordinate experience package=\(verification.packageName) "
                        + "page=\(verification.pageLabel) control=\(verification.controlLabel) "
                        + "px=(\(Int(pending.x.rounded())),\(Int(pending.y.rounded())))"
                    )
                } else {
                    Logger.chat("tap hit verification failed control=\(pending.controlLabel)")
                }
            } catch {
                Logger.error(error, context: "tap hit verification")
            }
        }

        if let packageName = payload.packageName, !packageName.isEmpty {
            do {
                let pageLabel = try await LLMService.classifyPageLabel(
                    packageName: packageName,
                    uiSummary: afterUi
                )
                let matches = tapCoordinateStore.matching(packageName: packageName, pageLabel: pageLabel)
                if matches.isEmpty {
                    currentTapCoordinateContext = nil
                } else {
                    let context = """
                    应用：\(packageName)
                    页面：\(pageLabel)
                    \(tapCoordinateStore.formatForPrompt(matches))
                    """
                    currentTapCoordinateContext = context
                    description += "\n\n已知点击坐标经验：\n\(context)"
                }
            } catch {
                Logger.error(error, context: "tap coordinate lookup")
                currentTapCoordinateContext = nil
            }
        } else {
            currentTapCoordinateContext = nil
        }
        return description
    }

    private func executeAdbCommands(from reply: String) async -> [String] {
        await Task.detached(priority: .utility) {
            AdbCommandParser.extractCommands(from: reply)
        }.value
    }
}

struct ChatDisplayItem: Identifiable, Equatable {
    let id: UUID
    let message: ChatMessage
    let displayContent: String
    let showPlanConfirm: Bool
}

struct ChatView: View {
    @Environment(\.custardPalette) private var palette
    @ObservedObject var viewModel: ChatViewModel
    @AppStorage(AppPreferences.showThinkingContentUserDefaultsKey)
    private var showThinkingContentEnabled = true
    @State private var stickToBottom = true

    var body: some View {
        VStack(spacing: 0) {
            messageList
            Divider()
                .overlay(palette.divider)
            inputBar
        }
        .background(palette.surface)
        .onChange(of: showThinkingContentEnabled) { newValue in
            viewModel.rebuildDisplayItems(showThinking: newValue)
        }
        .onAppear {
            viewModel.rebuildDisplayItems(showThinking: showThinkingContentEnabled)
        }
    }

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    if viewModel.displayItems.isEmpty {
                        Text("输入任务描述，大模型将帮你规划如何操控手机。")
                            .font(.subheadline)
                            .foregroundStyle(palette.secondaryText)
                            .padding(.top, 8)
                    }

                    ForEach(viewModel.displayItems) { item in
                        ChatBubble(
                            item: item,
                            onConfirmPlan: { viewModel.confirmPlan() }
                        )
                        .id(item.id)
                    }

                    if viewModel.isSending {
                        HStack(spacing: 8) {
                            ProgressView()
                                .controlSize(.small)
                            Text(viewModel.agentStatus.isEmpty ? "正在思考..." : viewModel.agentStatus)
                                .font(.caption)
                                .foregroundStyle(palette.secondaryText)
                        }
                        .padding(.vertical, 4)
                        .id("loading")
                    }

                    if viewModel.isDebugPaused {
                        HStack(spacing: 8) {
                            Button("继续执行") {
                                viewModel.continueDebugging()
                            }
                            .buttonStyle(.borderedProminent)
                            Button("停止任务") {
                                viewModel.stopDebugging()
                            }
                            .buttonStyle(.bordered)
                        }
                        .padding(.vertical, 4)
                    }

                    Color.clear
                        .frame(height: 1)
                        .id("chat-bottom")
                }
                .padding(12)
                .background(
                    ChatScrollStickTracker(stickToBottom: $stickToBottom)
                        .frame(width: 0, height: 0)
                )
            }
            .onChange(of: viewModel.displayItems.count) { _ in
                if stickToBottom {
                    scrollToBottom(proxy: proxy)
                }
            }
            .onChange(of: viewModel.isSending) { isSending in
                if isSending {
                    stickToBottom = true
                    scrollToBottom(proxy: proxy)
                }
            }
        }
    }

    private var inputBar: some View {
        HStack(alignment: .bottom, spacing: 8) {
            TextField("输入任务...", text: $viewModel.inputText, axis: .vertical)
                .textFieldStyle(.plain)
                .lineLimit(1...5)
                .padding(10)
                .background(palette.background)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .stroke(palette.divider, lineWidth: 1)
                )
                .onSubmit {
                    stickToBottom = true
                    viewModel.send()
                }

            Button {
                if viewModel.isDebugPaused {
                    viewModel.continueDebugging()
                } else if viewModel.isSending {
                    viewModel.stop()
                } else {
                    stickToBottom = true
                    viewModel.send()
                }
            } label: {
                Image(systemName: {
                    if viewModel.isDebugPaused { return "play.fill" }
                    if viewModel.isSending { return "stop.fill" }
                    return "paperplane.fill"
                }())
                .font(.body.weight(.semibold))
                .frame(width: 36, height: 36)
            }
            .buttonStyle(.borderedProminent)
            .tint(viewModel.isSending && !viewModel.isDebugPaused ? palette.error : palette.primary)
            .disabled(
                viewModel.isDebugPaused || viewModel.isSending
                    ? false
                    : (viewModel.inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                        && !viewModel.canSendEmpty)
            )
        }
        .padding(12)
        .background(palette.surface)
    }

    private func scrollToBottom(proxy: ScrollViewProxy) {
        DispatchQueue.main.async {
            proxy.scrollTo("chat-bottom", anchor: .bottom)
        }
    }
}

/// Observes the enclosing NSScrollView to decide whether the user is still pinned near the bottom.
private struct ChatScrollStickTracker: NSViewRepresentable {
    @Binding var stickToBottom: Bool
    var nearBottomThreshold: CGFloat = 80

    func makeCoordinator() -> Coordinator {
        Coordinator(stickToBottom: $stickToBottom, threshold: nearBottomThreshold)
    }

    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        DispatchQueue.main.async {
            context.coordinator.attach(to: view)
        }
        return view
    }

    func updateNSView(_ nsView: NSView, context: Context) {
        context.coordinator.stickToBottom = $stickToBottom
        DispatchQueue.main.async {
            context.coordinator.attach(to: nsView)
        }
    }

    final class Coordinator {
        var stickToBottom: Binding<Bool>
        let threshold: CGFloat
        private var observation: NSKeyValueObservation?

        init(stickToBottom: Binding<Bool>, threshold: CGFloat) {
            self.stickToBottom = stickToBottom
            self.threshold = threshold
        }

        func attach(to view: NSView) {
            guard let scrollView = view.enclosingScrollView else { return }
            observation?.invalidate()
            observation = scrollView.contentView.observe(\.bounds, options: [.new]) { [weak self] contentView, _ in
                guard let self, let documentView = scrollView.documentView else { return }
                let distance = documentView.bounds.height - contentView.bounds.maxY
                let nearBottom = distance <= self.threshold
                DispatchQueue.main.async {
                    if self.stickToBottom.wrappedValue != nearBottom {
                        self.stickToBottom.wrappedValue = nearBottom
                    }
                }
            }
        }
    }
}

private struct ChatBubble: View {
    @Environment(\.custardPalette) private var palette
    let item: ChatDisplayItem
    var onConfirmPlan: (() -> Void)? = nil

    private var message: ChatMessage { item.message }

    private var isUser: Bool {
        message.role == .user && !message.isAdbResult && !message.isScreenContext
    }

    private var isSystemContext: Bool {
        message.isAdbResult || message.isScreenContext || message.isToolResult
    }

    var body: some View {
        HStack {
            if isUser { Spacer(minLength: 40) }

            VStack(alignment: isUser ? .trailing : .leading, spacing: 4) {
                Text(senderLabel)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(palette.secondaryText)

                Text(item.displayContent)
                    .font(messageFont)
                    .foregroundStyle(textColor)
                    .modifier(ChatBubbleTextSelection(enabled: !isSystemContext))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(bubbleColor)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                if item.showPlanConfirm {
                    Button("确认执行") {
                        onConfirmPlan?()
                    }
                    .buttonStyle(.borderedProminent)
                    .padding(.top, 4)
                }
            }

            if !isUser && !isSystemContext {
                Spacer(minLength: 40)
            }
        }
    }

    private var senderLabel: String {
        if message.isToolResult || message.role == .tool { return "工具" }
        if message.isScreenContext { return "屏幕信息" }
        if message.isAdbResult { return "ADB 执行" }
        return isUser ? "你" : "助手"
    }

    private var messageFont: Font {
        if isSystemContext || message.role == .tool {
            return .system(.caption, design: .monospaced)
        }
        return .body
    }

    private var bubbleColor: Color {
        if message.isError {
            return palette.error.opacity(0.15)
        }
        if message.isToolResult || message.role == .tool {
            return palette.tertiary.opacity(0.2)
        }
        if message.isAdbResult {
            return palette.tertiary.opacity(0.25)
        }
        if message.isScreenContext {
            return palette.primaryContainer.opacity(0.45)
        }
        return isUser ? palette.primaryContainer : palette.background
    }

    private var textColor: Color {
        if message.isError {
            return palette.error
        }
        if message.isAdbResult {
            return palette.onSurface
        }
        return isUser ? palette.onPrimaryContainer : palette.onSurface
    }
}

private struct ChatBubbleTextSelection: ViewModifier {
    let enabled: Bool

    func body(content: Content) -> some View {
        if enabled {
            content.textSelection(.enabled)
        } else {
            content
        }
    }
}
