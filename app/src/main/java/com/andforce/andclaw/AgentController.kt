package com.andforce.andclaw

import android.accessibilityservice.AccessibilityService
import android.app.DownloadManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import com.andforce.andclaw.model.AgentUiState
import com.andforce.andclaw.model.AiAction
import com.andforce.andclaw.model.ApiConfig
import com.andforce.andclaw.model.ChatMessage
import com.afwsamples.testdpc.common.Util
import com.andforce.andclaw.db.ChatMessageDao
import com.andforce.andclaw.db.ChatMessageEntity
import com.andforce.andclaw.plan.AgentPlan
import com.andforce.andclaw.plan.PlanListItem
import com.andforce.andclaw.plan.PlanManager
import com.andforce.andclaw.plan.StepType
import com.andforce.andclaw.plan.StepVerification
import com.google.gson.Gson
import com.andforce.andclaw.bridge.RemoteOutboundHelper
import com.base.services.BridgeStatus
import com.base.services.FeishuInboundMessage
import com.base.services.IAiConfigService
import com.base.services.IRemoteBridgeService
import com.base.services.IRemoteChannelConfigService
import com.base.services.ITgBridgeService
import com.base.services.RemoteChannel
import com.base.services.RemoteIncomingMessage
import com.base.services.RemoteSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object AgentController : ITgBridgeService, IAiConfigService {

    private const val TAG = "AgentController"
    private const val PREFS_NAME = "agent_config"
    private const val DEFAULT_PROVIDER = "momoai"
    private lateinit var appContext: Context
    private lateinit var remoteBridge: IRemoteBridgeService
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val gson = Gson()

    private val channelConfig: IRemoteChannelConfigService
        get() = remoteBridge as IRemoteChannelConfigService

    /** 远程任务上下文；本地 [startAgent] 会置空，避免误向远程回传。 */
    private var _activeRemoteSession: RemoteSession? = null
    val activeRemoteSession: RemoteSession?
        get() = _activeRemoteSession

    /** 过渡期：与 Telegram 相关的旧代码仍可能读取；由 [activeRemoteSession] 同步。 */
    var tgActiveChatId: Long = 0L
        private set

    override val bridgeStatus: StateFlow<BridgeStatus>
        get() = remoteBridge.telegramStatus

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    var config = ApiConfig()
        private set
    var isAgentRunning = false
        private set
    private var agentJob: Job? = null
    private var consecutiveSameCount = 0
    private var lastFingerprint = ""
    private var loopRetryCount = 0
    private var networkRetryCount = 0
    private var ordinaryFailureReplanCount = 0
    private var routineSuccessSinceVerifier = 0
    private var uiState = AgentUiState()
    private var activePlan: AgentPlan? = null
    private var nextStepId = 1

    @Volatile
    private var activeStepTiming: StepTiming? = null

    private val dpmBridge by lazy { DpmBridge(appContext) }
    private lateinit var chatDao: ChatMessageDao
    private lateinit var planManager: PlanManager

    private class StepTiming(
        val id: Int,
        goal: String,
        hasInputScreenshot: Boolean
    ) {
        val startedAtMs: Long = SystemClock.elapsedRealtime()
        private var lastMarkMs: Long = startedAtMs
        private val shortGoal = goal.take(80)

        init {
            Log.d(TAG, "step#$id timing start hasInputScreenshot=$hasInputScreenshot goal=$shortGoal")
        }

        @Synchronized
        fun mark(stage: String, details: String? = null) {
            val now = SystemClock.elapsedRealtime()
            val delta = now - lastMarkMs
            val total = now - startedAtMs
            lastMarkMs = now
            val suffix = details?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
            Log.d(TAG, "step#$id timing stage=$stage delta=${formatDuration(delta)} total=${formatDuration(total)}$suffix")
        }

        fun elapsedMs(): Long = SystemClock.elapsedRealtime() - startedAtMs
    }

    private fun screenshotSuccessMessage(session: RemoteSession?, fileName: String): String {
        val base = "截图已保存：Pictures/Andclaw/$fileName"
        return base + when (session?.channel) {
            RemoteChannel.TELEGRAM -> "（远程已发送到 Telegram）"
            RemoteChannel.CLAWBOT -> "（ClawBot 暂不支持图片远程回传；本地已保存，应用将尝试向远端发送文本说明）"
            RemoteChannel.FEISHU -> "（飞书暂不支持图片远程回传；本地已保存）"
            else -> ""
        }
    }

    /** 拍照/录像/录音/录屏等远程回传后的补充说明（与 [RemoteBridgeManager] 媒体策略一致）。 */
    private fun appendRemoteBinaryMediaNote(session: RemoteSession?, base: String): String {
        val suffix = when (session?.channel) {
            RemoteChannel.TELEGRAM -> "（已发送到 Telegram）"
            RemoteChannel.CLAWBOT -> "（已保存到本地；ClawBot 暂不支持该类型远程回传，应用将尝试向远端发送文本说明）"
            RemoteChannel.FEISHU -> "（已保存到本地；飞书暂不支持该类型远程回传）"
            else -> ""
        }
        return base + suffix
    }

    private fun formatDuration(ms: Long): String =
        if (ms < 1000L) "${ms}ms" else String.format("%.2fs", ms / 1000.0)

    private fun appendStepTiming(text: String, timing: StepTiming? = activeStepTiming): String {
        timing ?: return text
        return "$text\n\n[step#${timing.id} 耗时 ${formatDuration(timing.elapsedMs())}]"
    }

    private suspend fun sendRemoteText(
        session: RemoteSession?,
        text: String,
        replyToMessageId: Long? = null,
        includeStepTiming: Boolean = true
    ) {
        val body = if (includeStepTiming) appendStepTiming(text) else text
        RemoteOutboundHelper.sendText(remoteBridge, session, body, replyToMessageId)
    }

    fun init(context: Context, dao: ChatMessageDao, bridge: IRemoteBridgeService) {
        appContext = context.applicationContext
        chatDao = dao
        remoteBridge = bridge
        planManager = PlanManager(appContext)
        remoteBridge.setTelegramInboundHandler { msg ->
            handleTelegramCommand(msg.chatId, msg.messageId, msg.text)
        }
        remoteBridge.setClawBotInboundHandler { msg ->
            handleClawBotCommand(msg)
        }
        remoteBridge.setFeishuInboundHandler { msg ->
            handleFeishuCommand(msg)
        }
        migrateOldProviderKeys()
        restoreConfig()
        loadHistory()
        loadLatestUnfinishedPlan()
    }

    private fun loadHistory() {
        scope.launch(Dispatchers.IO) {
            val entities = chatDao.getAll()
            val msgs = entities.map { e ->
                val action = e.actionJson?.let {
                    try { gson.fromJson(it, AiAction::class.java) } catch (_: Exception) { null }
                }
                ChatMessage(role = e.role, content = e.content, action = action, timestamp = e.timestamp, id = e.id)
            }
            _messages.value = msgs
        }
    }

    private fun loadLatestUnfinishedPlan() {
        activePlan = planManager.loadLatestUnfinishedPlan()
        activePlan?.let { plan ->
            addMessage("system", "Restored unfinished plan: ${plan.summary} (${plan.status}). Open Plans to resume.")
        }
    }

    private fun migrateOldProviderKeys() {
        val oldPrefs = appContext.getSharedPreferences("ai_provider_keys", Context.MODE_PRIVATE)
        val allEntries = oldPrefs.all
        if (allEntries.isEmpty()) return

        val prefs = getPrefs()
        val editor = prefs.edit()
        for ((key, value) in allEntries) {
            if (!prefs.contains(key)) {
                editor.putString(key, value as? String ?: "")
            }
        }
        editor.apply()
        oldPrefs.edit().clear().apply()
        Log.d(TAG, "migrateOldProviderKeys: migrated ${allEntries.size} entries")
    }

    private fun restoreConfig() {
        val prefs = getPrefs()
        val savedProvider = prefs.getString("ai_provider", null)
        val provider = DEFAULT_PROVIDER
        val apiKey = if (savedProvider != null) {
            prefs.getString("ai_api_key", null)
                ?: loadProviderKey(savedProvider)
        } else {
            loadProviderKey(provider).ifEmpty { config.apiKey }
        }
        config = ApiConfig(
            provider = provider,
            apiKey = apiKey,
            apiUrl = prefs.getString("ai_api_url", config.apiUrl) ?: config.apiUrl,
            model = prefs.getString("ai_model", config.model) ?: config.model
        )
        Log.d(TAG, "restoreConfig: provider=${config.provider}, apiKey=${Utils.maskKey(config.apiKey)}")
    }

    private fun persistConfig() {
        getPrefs().edit()
            .putString("ai_provider", config.provider)
            .putString("ai_api_key", config.apiKey)
            .putString("ai_api_url", config.apiUrl)
            .putString("ai_model", config.model)
            .apply()
    }

    override val provider: String get() = config.provider
    override val apiUrl: String get() = config.apiUrl
    override val apiKey: String get() = config.apiKey
    override val model: String get() = config.model
    override val defaultApiKey: String get() = ""

    override fun updateConfig(provider: String, apiUrl: String, apiKey: String, model: String) {
        config = config.copy(provider = provider, apiUrl = apiUrl, apiKey = apiKey, model = model)
        persistConfig()
    }

    override fun saveProviderKey(provider: String, key: String) {
        if (provider.isNotBlank() && key.isNotBlank()) {
            getPrefs().edit().putString("api_key_$provider", key).apply()
        }
    }

    override fun loadProviderKey(provider: String): String {
        return getPrefs().getString("api_key_$provider", "") ?: ""
    }

    fun getPrefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun syncLegacyTgChatIdFromSession(session: RemoteSession?) {
        tgActiveChatId = when {
            session == null -> 0L
            session.channel == RemoteChannel.TELEGRAM -> session.sessionKey.toLongOrNull() ?: 0L
            else -> 0L
        }
    }

    // --- ITgBridgeService ---

    override fun startBridge() {
        remoteBridge.startEligibleBridges()
    }

    override fun stopBridge() {
        remoteBridge.stopTelegramBridge()
    }

    private suspend fun handleTelegramCommand(chatId: Long, msgId: Long, text: String) {
        val telegramSession = RemoteSession(
            channel = RemoteChannel.TELEGRAM,
            sessionKey = chatId.toString(),
            messageId = msgId.toString(),
        )
        when (text) {
            "/status" -> {
                val allowedId = channelConfig.getTgChatId()
                val accessInfo = if (allowedId == 0L) "⚠️ 未设置 Chat ID 白名单" else "✅ Chat ID 已锁定"
                val agentInfo = if (isAgentRunning) "▶️ Agent 运行中: ${uiState.userInput}" else "⏸ Agent 空闲"
                val body = "Andclaw 状态\n$agentInfo\n$accessInfo\n你的 Chat ID: $chatId"
                sendRemoteText(telegramSession, body, replyToMessageId = msgId)
            }
            "/stop" -> {
                withContext(Dispatchers.Main) { stopAgent("用户请求停止") }
                sendRemoteText(telegramSession, "✅ 已停止当前任务", replyToMessageId = msgId)
            }
            else -> {
                val busy = withContext(Dispatchers.Main) { isAgentRunning to uiState.userInput }
                if (busy.first) {
                    sendRemoteText(
                        telegramSession,
                        "⏳ Agent 正在执行上一任务，不会开始新任务。请稍后或发送 /stop 停止。进行中的任务：${busy.second}",
                        replyToMessageId = msgId
                    )
                    return
                }
                RemoteOutboundHelper.sendTyping(remoteBridge, telegramSession)
                withContext(Dispatchers.Main) { startAgent(text, remoteSession = telegramSession) }
            }
        }
    }

    private suspend fun handleClawBotCommand(msg: RemoteIncomingMessage) {
        val clawSession = RemoteSession(
            channel = RemoteChannel.CLAWBOT,
            sessionKey = msg.sessionKey,
            replyToken = msg.replyToken,
            userId = msg.senderId,
            messageId = msg.messageId,
            accountId = msg.accountId,
        )
        when (msg.text.trim()) {
            "/status" -> {
                val agentInfo = if (isAgentRunning) "▶️ Agent 运行中: ${uiState.userInput}" else "⏸ Agent 空闲"
                val body = "Andclaw 状态\n$agentInfo\n会话: ${msg.sessionKey}"
                sendRemoteText(clawSession, body, replyToMessageId = null)
            }
            "/stop" -> {
                withContext(Dispatchers.Main) { stopAgent("用户请求停止") }
                sendRemoteText(clawSession, "✅ 已停止当前任务", replyToMessageId = null)
            }
            else -> {
                val busy = withContext(Dispatchers.Main) { isAgentRunning to uiState.userInput }
                if (busy.first) {
                    sendRemoteText(
                        clawSession,
                        "⏳ Agent 正在执行上一任务，不会开始新任务。请稍后或发送 /stop 停止。进行中的任务：${busy.second}",
                        replyToMessageId = null
                    )
                    return
                }
                RemoteOutboundHelper.sendTyping(remoteBridge, clawSession)
                withContext(Dispatchers.Main) { startAgent(msg.text, remoteSession = clawSession) }
            }
        }
    }

    private suspend fun handleFeishuCommand(msg: FeishuInboundMessage) {
        Log.d(TAG, "Feishu message received: ${msg.text.take(100)}, chatId=${msg.chatId}, sender=${msg.senderId}")
        val feishuSession = RemoteSession(
            channel = RemoteChannel.FEISHU,
            sessionKey = msg.chatId,
            replyToken = msg.parentMessageId ?: msg.messageId,
            userId = msg.senderId,
            messageId = msg.messageId,
            displayName = msg.senderType
        )
        when (msg.text.trim()) {
            "/status" -> {
                val agentInfo = if (isAgentRunning) "▶️ Agent 运行中: ${uiState.userInput}" else "⏸ Agent 空闲"
                val body = "Andclaw 状态\n$agentInfo\n会话: ${msg.chatId}"
                sendRemoteText(feishuSession, body, replyToMessageId = null)
            }
            "/stop" -> {
                withContext(Dispatchers.Main) { stopAgent("用户请求停止") }
                sendRemoteText(feishuSession, "✅ 已停止当前任务", replyToMessageId = null)
            }
            else -> {
                val busy = withContext(Dispatchers.Main) { isAgentRunning to uiState.userInput }
                if (busy.first) {
                    sendRemoteText(
                        feishuSession,
                        "⏳ Agent 正在执行上一任务，不会开始新任务。请稍后或发送 /stop 停止。进行中的任务：${busy.second}",
                        replyToMessageId = null
                    )
                    return
                }
                RemoteOutboundHelper.sendTyping(remoteBridge, feishuSession)
                withContext(Dispatchers.Main) { startAgent(msg.text, remoteSession = feishuSession) }
            }
        }
    }

    // --- Agent Logic ---

    fun startAgent(input: String, remoteSession: RemoteSession? = null) {
        _activeRemoteSession = remoteSession
        syncLegacyTgChatIdFromSession(remoteSession)

        addMessage("user", input)
        isAgentRunning = true
        uiState = uiState.copy(isRunning = true, userInput = input)
        consecutiveSameCount = 0
        lastFingerprint = ""
        loopRetryCount = 0
        networkRetryCount = 0
        ordinaryFailureReplanCount = 0
        routineSuccessSinceVerifier = 0
        activePlan = planManager.createPlan(input)
        activePlan?.let { plan ->
            addMessage("system", "Long-term plan created: ${planManager.planDir(plan).absolutePath}/plan.md")
            sendRemotePlanProgress(plan, "Plan created")
        }

        Log.d(TAG, "startAgent: provider=${config.provider}, model=${config.model}, apiUrl=${config.apiUrl}, apiKey=${Utils.maskKey(config.apiKey)}")

        agentJob = scope.launch {
            generateInitialPlan(input)
            executeAgentStep(input)
        }
    }

    fun listPlans(): List<PlanListItem> = planManager.listPlans()

    fun getPlanMarkdown(planId: String): String =
        planManager.readPlanMarkdown(planId) ?: "Plan not found: $planId"

    fun resumePlan(planId: String) {
        if (isAgentRunning) {
            addMessage("system", "Agent is already running. Stop it before resuming another plan.")
            return
        }
        val plan = planManager.loadPlan(planId)
        if (plan == null) {
            addMessage("system", "Plan not found: $planId")
            return
        }
        activePlan = plan
        _activeRemoteSession = null
        syncLegacyTgChatIdFromSession(null)
        isAgentRunning = true
        uiState = uiState.copy(isRunning = true, userInput = plan.goal)
        consecutiveSameCount = 0
        lastFingerprint = ""
        loopRetryCount = 0
        networkRetryCount = 0
        ordinaryFailureReplanCount = 0
        routineSuccessSinceVerifier = 0
        addMessage("system", "Resuming plan: ${plan.summary}")
        sendRemotePlanProgress(plan, "Plan resumed")
        agentJob = scope.launch {
            executeAgentStep(plan.goal)
        }
    }

    fun stopAgent(reason: String? = null) {
        val sessionToNotify = _activeRemoteSession
        val timingToNotify = activeStepTiming
        val wasRunning = isAgentRunning
        isAgentRunning = false
        uiState = uiState.copy(isRunning = false, status = "Agent Stopped.")
        agentJob?.cancel()
        activePlan = planManager.markCancelled(activePlan, reason)
        _activeRemoteSession = null
        syncLegacyTgChatIdFromSession(null)

        if (wasRunning && sessionToNotify != null) {
            val suffix = reason?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            val body = appendStepTiming("⏹ Agent 已停止$suffix", timingToNotify)
            scope.launch(Dispatchers.IO) {
                RemoteOutboundHelper.sendText(remoteBridge, sessionToNotify, body)
                activeStepTiming = null
            }
        } else if (wasRunning) {
            activeStepTiming = null
        }
    }

    private suspend fun generateInitialPlan(input: String) {
        activeStepTiming?.mark("initial_planner_started")
        val screenData = AgentAccessibilityService.instance?.captureScreenHierarchy() ?: "Screen data inaccessible"
        activeStepTiming?.mark("initial_planner_screen_captured", "chars=${screenData.length}")
        val response = Utils.callInitialPlanner(input, screenData, config, appContext)
        activeStepTiming?.mark("initial_planner_response_received", "chars=${response.length}")
        val draft = planManager.parseDraft(response)
        activeStepTiming?.mark("initial_planner_parsed", "hasDraft=${draft != null}")
        val upgraded = planManager.replaceWithDraft(activePlan, draft)
        if (upgraded != null && upgraded !== activePlan && draft?.steps?.isNotEmpty() == true) {
            activePlan = upgraded
            addMessage("system", "Long-term plan generated with ${upgraded.steps.size} steps.")
            sendRemotePlanProgress(upgraded, "Plan generated")
        }
    }

    private suspend fun replanActivePlan(reason: String) {
        val planContext = planManager.toPromptContext(activePlan) ?: return
        activeStepTiming?.mark("replan_started", reason)
        val screenData = AgentAccessibilityService.instance?.captureScreenHierarchy() ?: "Screen data inaccessible"
        activeStepTiming?.mark("replan_screen_captured", "chars=${screenData.length}")
        val response = Utils.callPlanPatchPlanner(
            userGoal = uiState.userInput,
            screenData = screenData,
            planContext = planContext,
            reason = reason,
            config = config,
            context = appContext
        )
        activeStepTiming?.mark("replan_response_received", "chars=${response.length}")
        val patch = planManager.parsePatch(response)
        activeStepTiming?.mark("replan_parsed", "hasPatch=${patch != null}")
        val patched = planManager.applyPatch(activePlan, patch)
        if (patched != null && patch != null) {
            activePlan = patched
            addMessage("system", "Long-term plan patched: ${patch.reason ?: reason}")
            sendRemotePlanProgress(patched, "Plan updated")
        }
    }

    private suspend fun executeAgentStep(userInput: String, screenshotBase64: String? = null) {
        if (!isAgentRunning) return

        val stepTiming = StepTiming(nextStepId++, userInput, hasInputScreenshot = screenshotBase64 != null)
        activeStepTiming = stepTiming
        RemoteOutboundHelper.sendTyping(remoteBridge, activeRemoteSession)
        stepTiming.mark("remote_typing_sent")

        val svc = AgentAccessibilityService.instance
        val screenData = svc?.captureScreenHierarchy() ?: "Screen data inaccessible"
        stepTiming.mark("screen_hierarchy_captured", "chars=${screenData.length}")

        val finalScreenshot = screenshotBase64
        stepTiming.mark(
            "screenshot_policy",
            if (finalScreenshot == null) "skipped_auto_screenshot=true" else "provided_by_recovery=true"
        )

        val historyContext = buildLlmHistoryContext(_messages.value)
        stepTiming.mark("history_built", "items=${historyContext.size}")

        try {
            val isDeviceOwner = Util.isDeviceOwner(appContext)
            Log.d(TAG, "executeAgentStep: calling LLM, provider=${config.provider}, apiKey=${Utils.maskKey(config.apiKey)}, historySize=${historyContext.size}, hasScreenshot=${finalScreenshot != null}")
            var response = Utils.callLLMWithHistory(
                userInput, screenData, historyContext, config, appContext,
                isDeviceOwner = isDeviceOwner,
                screenshotBase64 = finalScreenshot,
                planContext = planManager.toPromptContext(activePlan),
                logLabel = "agentStep#${stepTiming.id}"
            )
            stepTiming.mark("llm_response_received", "chars=${response.length}")
            var action = Utils.parseAction(response)
            stepTiming.mark("llm_response_parsed", "type=${action.type}")

            if (action.type == "error" && action.reason?.contains("Failed to parse") == true) {
                Log.w(TAG, "LLM returned non-JSON, retrying with correction prompt")
                stepTiming.mark("llm_parse_retry_started")
                val retryHistory = historyContext.toMutableList().apply {
                    add(mapOf("role" to "assistant", "content" to response))
                    add(mapOf("role" to "user", "content" to "Invalid response. Output a single JSON object only, no other text."))
                }
                response = Utils.callLLMWithHistory(
                    userInput, screenData, retryHistory, config, appContext,
                    isDeviceOwner = isDeviceOwner,
                    planContext = planManager.toPromptContext(activePlan),
                    logLabel = "agentStep#${stepTiming.id}-retry"
                )
                stepTiming.mark("llm_retry_response_received", "chars=${response.length}")
                action = Utils.parseAction(response)
                stepTiming.mark("llm_retry_response_parsed", "type=${action.type}")
            }

            if (action.type == "error") {
                val reason = action.reason ?: "AI returned error"
                stepTiming.mark("step_error", reason)
                if (isRecoverableNetworkError(reason)) {
                    scheduleNetworkRetry(reason)
                } else {
                    addMessage("system", "Error occurred: $reason")
                    activePlan = planManager.recordFailure(activePlan, reason, terminal = true)
                    stopAgent(reason)
                }
            } else {
                if (networkRetryCount > 0) {
                    val recoveredMessage = "网络已恢复，继续执行任务。"
                    addMessage("system", recoveredMessage)
                    scope.launch {
                        sendRemoteText(activeRemoteSession, "✅ $recoveredMessage")
                    }
                }
                networkRetryCount = 0
                activePlan = planManager.recordAction(activePlan, action)
                stepTiming.mark("action_recorded", "type=${action.type}")
                withContext(Dispatchers.Main) {
                    val aiDisplayMessage = "[Progress: ${action.progress ?: "Executing"}]\n${action.reason ?: "Thinking..."}"
                    addMessage("ai", aiDisplayMessage, action)
                    handleAction(action)
                }
            }
        } catch (e: Exception) {
            stepTiming.mark("step_exception", e.message.orEmpty())
            withContext(Dispatchers.Main) {
                val reason = e.message ?: "unknown"
                if (isRecoverableNetworkError(reason)) {
                    scheduleNetworkRetry(reason)
                } else {
                    addMessage("system", "AI Request Failed: $reason")
                    stopAgent("AI 请求失败: $reason")
                }
            }
        }
    }

    private fun isRecoverableNetworkError(reason: String): Boolean {
        val normalized = reason.lowercase()
        return normalized.contains("network timeout") ||
            normalized.contains("connection failed") ||
            normalized.contains("timeout") ||
            normalized.contains("timed out")
    }

    private fun buildLlmHistoryContext(messages: List<ChatMessage>): List<Map<String, String>> {
        val selected = mutableListOf<Map<String, String>>()
        var assistantActions = 0
        var systemFeedback = 0
        var userMessages = 0

        messages.asReversed().forEach { message ->
            if (selected.size >= 6) return@forEach
            when (message.role) {
                "user" -> {
                    if (userMessages < 1) {
                        selected += mapOf("role" to "user", "content" to compactPromptText(message.content, 700))
                        userMessages++
                    }
                }

                "ai" -> {
                    val action = message.action ?: return@forEach
                    if (assistantActions < 3) {
                        selected += mapOf("role" to "assistant", "content" to compactPromptText(gson.toJson(action), 900))
                        assistantActions++
                    }
                }

                "system" -> {
                    val content = message.content
                    val shouldKeep = content.startsWith("Intent failed:") ||
                        content.startsWith("Loop detected") ||
                        content.startsWith("Execution Exception:") ||
                        content.startsWith("Error occurred:") ||
                        content.startsWith("AI Request Failed:") ||
                        content.startsWith("Click target") ||
                        content.startsWith("Click blocked:") ||
                        content.startsWith("No focused input field") ||
                        (content.startsWith("Action success.") && content.contains("\n"))
                    if (shouldKeep && systemFeedback < 2) {
                        selected += mapOf("role" to "user", "content" to "System feedback: ${compactPromptText(content, 700)}")
                        systemFeedback++
                    }
                }
            }
        }

        return selected.asReversed()
    }

    private fun compactPromptText(text: String, maxChars: Int): String {
        val oneLine = text.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= maxChars) oneLine else oneLine.take(maxChars) + "...[truncated]"
    }

    private fun nextNetworkRetryDelayMs(): Long {
        val seconds = when (networkRetryCount) {
            0 -> 5L
            1 -> 10L
            else -> 15L
        }
        return seconds * 1000L
    }

    private fun scheduleNetworkRetry(reason: String) {
        if (!isAgentRunning) return

        val delayMs = nextNetworkRetryDelayMs()
        networkRetryCount++
        val waitSeconds = delayMs / 1000L
        val message = "网络暂时不可用：$reason。将在 ${waitSeconds} 秒后重试；恢复前会持续高频重试，尽快在第一时间恢复，可发送 /stop 停止。"
        addMessage("system", message)
        activePlan = planManager.recordFailure(activePlan, reason, terminal = false)

        scope.launch {
            sendRemoteText(activeRemoteSession, "⏳ $message")
            delay(delayMs)
            if (!isAgentRunning) return@launch
            addMessage("system", "Network retry #$networkRetryCount. Analyzing next step...")
            executeAgentStep(uiState.userInput)
        }
    }

    private fun handleAction(action: AiAction) {
        if (!isAgentRunning) return

        activeStepTiming?.mark("handle_action_started", "type=${action.type}")
        val fingerprint = when (action.type) {
            AiAction.TYPE_HTTP_REQUEST -> "${action.type}_${action.data}_${action.httpMethod}"
            AiAction.TYPE_CLICK -> "${action.type}_${action.nodeId ?: "xy"}_${action.x}_${action.y}_${action.targetText.orEmpty()}"
            else -> "${action.type}_${action.x}_${action.y}"
        }
        if (fingerprint == lastFingerprint) {
            consecutiveSameCount++
        } else {
            consecutiveSameCount = 1
            lastFingerprint = fingerprint
            loopRetryCount = 0
        }

        if (consecutiveSameCount >= 5) {
            consecutiveSameCount = 0
            loopRetryCount++

            if (loopRetryCount >= 3) {
                addMessage("system", "Loop detected. Same action [$fingerprint] repeated ${loopRetryCount * 5} times with screenshots. Agent stopped.")
                activePlan = planManager.recordFailure(activePlan, "Loop detected for action [$fingerprint]", terminal = true)
                stopAgent("检测到重复操作，已触发循环保护")
                return
            }

            scope.launch {
                val screenshot = captureScreenBase64()
                addMessage("system", "Loop detected. Action [$fingerprint] repeated 5 times. Taking screenshot for visual analysis... (retry $loopRetryCount/3)", screenshotBase64 = screenshot)
                replanActivePlan("Loop detected for action [$fingerprint], retry $loopRetryCount/3")
                executeAgentStep(uiState.userInput, screenshotBase64 = screenshot)
            }
            return
        }

        when (action.type) {
            AiAction.TYPE_INTENT -> {
                addMessage("ai", action.reason ?: "I will use a system shortcut.", action)
                executeIntent(action)

                val isTerminal = action.action?.let {
                    it.contains("ALARM") || it.contains("SEND")
                } ?: false
                if (isTerminal) {
                    addMessage("system", "Task dispatched via system.")
                    activePlan = planManager.markDone(activePlan, action.reason ?: "Task dispatched via system")
                    stopAgent("任务已通过系统分发")
                } else {
                    addMessage("system", "App opened. Waiting for UI to settle...")
                    isAgentRunning = true
                    scope.launch {
                        continueAfterSuccessfulAction(action)
                    }
                }
            }

            AiAction.TYPE_CLICK,
            AiAction.TYPE_SWIPE,
            AiAction.TYPE_LONG_PRESS,
            AiAction.TYPE_TEXT_INPUT,
            AiAction.TYPE_GLOBAL_ACTION,
            AiAction.TYPE_SCREENSHOT,
            AiAction.TYPE_DOWNLOAD,
            AiAction.TYPE_HTTP_REQUEST,
            AiAction.TYPE_CAMERA,
            AiAction.TYPE_SCREEN_RECORD,
            AiAction.TYPE_VOLUME,
            AiAction.TYPE_AUDIO_RECORD,
            AiAction.TYPE_WAKE_SCREEN -> {
                performConfirmedAction(action)
            }

            AiAction.TYPE_DPM -> {
                val dpmAction = action.dpmAction
                if (dpmAction.isNullOrEmpty()) {
                    addMessage("system", "DPM action name missing")
                    activePlan = planManager.recordFailure(activePlan, "DPM action name missing", terminal = true)
                    stopAgent("DPM action name missing")
                    return
                }
                performConfirmedAction(action)
            }

            AiAction.TYPE_WAIT -> {
                val waitMs = if (action.duration > 0) action.duration.coerceAtMost(10000) else 1000L
                addMessage("system", "Waiting ${waitMs}ms for UI update...")
                scope.launch {
                    delay(waitMs)
                    if (!isAgentRunning) return@launch
                    addMessage("system", "Wait finished. Analyzing next step...")
                    executeAgentStep(uiState.userInput)
                }
            }

            AiAction.TYPE_FINISH -> {
                addMessage("system", "Finished.")
                activePlan = planManager.markDone(activePlan, action.reason)
                stopAgent(action.reason ?: "任务完成")
            }

            AiAction.TYPE_ERROR -> {
                addMessage("system", "AI Error: ${action.reason}")
                activePlan = planManager.recordFailure(activePlan, action.reason ?: "AI returned error", terminal = true)
                stopAgent(action.reason ?: "AI 返回错误")
            }

            else -> {
                addMessage("system", "Unknown action: ${action.type}")
                activePlan = planManager.recordFailure(activePlan, "Unknown action: ${action.type}", terminal = true)
                stopAgent("Unknown action: ${action.type}")
            }
        }
    }

    fun performConfirmedAction(action: AiAction) {
        if (!isAgentRunning) return

        scope.launch(Dispatchers.IO) {
            activeStepTiming?.mark("action_execution_started", "type=${action.type}")
            var success = false
            var outputMsg: String? = null
            try {
                when (action.type) {
                    AiAction.TYPE_CLICK -> {
                        val svc = AgentAccessibilityService.instance
                        if (svc == null) {
                            outputMsg = "Accessibility service not running"
                        } else {
                            val targetText = action.targetText?.trim().orEmpty()
                            val inputMethodBlock = withContext(Dispatchers.Main) {
                                svc.blockedInputMethodClickReason(action.nodeId, action.x, action.y, targetText)
                            }
                            if (inputMethodBlock != null) {
                                outputMsg = inputMethodBlock
                            } else if (action.nodeId != null) {
                                success = withContext(Dispatchers.Main) {
                                    svc.clickNodeAndWaitForCompletion(action.nodeId)
                                }
                                if (!success && targetText.isNotEmpty()) {
                                    withContext(Dispatchers.Main) { svc.captureScreenHierarchy() }
                                    success = withContext(Dispatchers.Main) {
                                        svc.clickBestTargetTextAndWaitForCompletion(targetText)
                                    }
                                    if (success) outputMsg = "Click target node_id=${action.nodeId} was stale; recovered by target_text '$targetText'"
                                }
                                if (!success) outputMsg = "Click target node_id=${action.nodeId} was not found in the latest UI snapshot"
                            } else if (targetText.isNotEmpty()) {
                                val matchesTarget = withContext(Dispatchers.Main) {
                                    svc.doesNodeAtMatchTarget(action.x, action.y, targetText)
                                }
                                if (!matchesTarget) {
                                    withContext(Dispatchers.Main) { svc.captureScreenHierarchy() }
                                    success = withContext(Dispatchers.Main) {
                                        svc.clickBestTargetTextAndWaitForCompletion(targetText)
                                    }
                                    if (success) {
                                        outputMsg = "Click coordinate target_text mismatch recovered by target_text '$targetText'"
                                    } else {
                                        val actual = withContext(Dispatchers.Main) {
                                            svc.describeNodeAt(action.x, action.y)
                                        } ?: "unknown element"
                                        outputMsg = "Click blocked: target_text '$targetText' did not match element at (${action.x},${action.y}): $actual"
                                    }
                                } else {
                                    success = withContext(Dispatchers.Main) {
                                        svc.clickAndWaitForCompletion(action.x, action.y)
                                    }
                                    if (!success) outputMsg = "Click gesture was cancelled"
                                }
                            } else {
                                success = withContext(Dispatchers.Main) {
                                    svc.clickAndWaitForCompletion(action.x, action.y)
                                }
                                if (!success) outputMsg = "Click gesture was cancelled"
                            }
                        }
                    }

                    AiAction.TYPE_SWIPE -> {
                        val svc = AgentAccessibilityService.instance
                        if (svc == null) {
                            outputMsg = "Accessibility service not running"
                        } else {
                            val dur = if (action.duration > 0) action.duration else 300L
                            success = withContext(Dispatchers.Main) {
                                svc.swipeAndWaitForCompletion(action.x, action.y, action.endX, action.endY, dur)
                            }
                            if (!success) outputMsg = "Swipe gesture was cancelled"
                        }
                    }

                    AiAction.TYPE_LONG_PRESS -> {
                        val svc = AgentAccessibilityService.instance
                        if (svc == null) {
                            outputMsg = "Accessibility service not running"
                        } else {
                            val dur = if (action.duration > 0) action.duration else 1000L
                            success = withContext(Dispatchers.Main) {
                                svc.longPressAndWaitForCompletion(action.x, action.y, dur)
                            }
                            if (!success) outputMsg = "Long press gesture was cancelled"
                        }
                    }

                    AiAction.TYPE_TEXT_INPUT -> {
                        val svc = AgentAccessibilityService.instance
                        if (svc == null) {
                            outputMsg = "Accessibility service not running"
                        } else if (action.text.isNullOrEmpty()) {
                            outputMsg = "text field is empty"
                        } else {
                            val result = withContext(Dispatchers.Main) {
                                svc.inputText(action.text)
                            }
                            success = result
                            if (!result) outputMsg = "No focused input field found"
                        }
                    }

                    AiAction.TYPE_GLOBAL_ACTION -> {
                        val svc = AgentAccessibilityService.instance
                        if (svc == null) {
                            outputMsg = "Accessibility service not running"
                        } else {
                            val actionId = when (action.globalAction) {
                                "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                                "home" -> AccessibilityService.GLOBAL_ACTION_HOME
                                "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                                "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                                "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                                else -> {
                                    outputMsg = "Unknown global_action: ${action.globalAction}"
                                    -1
                                }
                            }
                            if (actionId >= 0) {
                                withContext(Dispatchers.Main) { svc.globalAction(actionId) }
                                success = true
                            }
                        }
                    }

                    AiAction.TYPE_SCREENSHOT -> {
                        val svc = AgentAccessibilityService.instance
                        if (svc == null) {
                            outputMsg = "Accessibility service not running"
                        } else {
                            val latch = CountDownLatch(1)
                            var bitmap: Bitmap? = null
                            withContext(Dispatchers.Main) {
                                svc.captureScreenshot { bmp ->
                                    bitmap = bmp
                                    latch.countDown()
                                }
                            }
                            latch.await(5, TimeUnit.SECONDS)
                            if (bitmap != null) {
                                val fileName = "screenshot_${System.currentTimeMillis()}.png"
                                val values = ContentValues().apply {
                                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Andclaw")
                                }
                                appContext.contentResolver.insert(
                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                                )?.let { uri ->
                                    appContext.contentResolver.openOutputStream(uri)?.use { out ->
                                        bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, out)
                                    }
                                }

                                activeRemoteSession?.let { session ->
                                    val baos = ByteArrayOutputStream()
                                    bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, baos)
                                    RemoteOutboundHelper.sendPhoto(
                                        remoteBridge, session,
                                        baos.toByteArray(), caption = fileName, fileName = fileName
                                    )
                                }

                                success = true
                                outputMsg = screenshotSuccessMessage(activeRemoteSession, fileName)
                            } else {
                                outputMsg = "Screenshot failed (API 30+ required)"
                            }
                        }
                    }

                    AiAction.TYPE_DOWNLOAD -> {
                        if (action.data.isNullOrEmpty()) {
                            outputMsg = "Download URL (data) is empty"
                        } else {
                            try {
                                val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                val fileName = action.data.substringAfterLast("/")
                                    .substringBefore("?")
                                    .ifEmpty { "download_${System.currentTimeMillis()}" }
                                val request = DownloadManager.Request(
                                    Uri.parse(action.data)
                                ).apply {
                                    setTitle("Andclaw Download")
                                    setDescription(fileName)
                                    setNotificationVisibility(
                                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                                    )
                                    setDestinationInExternalPublicDir("Download", fileName)
                                }
                                val downloadId = dm.enqueue(request)
                                success = true
                                outputMsg = "Download started: $fileName (ID=$downloadId)"
                            } catch (e: Exception) {
                                outputMsg = "Download failed: ${e.message}"
                            }
                        }
                    }

                    AiAction.TYPE_HTTP_REQUEST -> {
                        val (ok, msg) = Utils.executeHttpRequest(action)
                        success = ok
                        outputMsg = msg
                    }

                    AiAction.TYPE_DPM -> {
                        val dpmResult = dpmBridge.execute(action.dpmAction ?: "", action.extras)
                        success = dpmResult.success
                        outputMsg = "DPM ${action.dpmAction}: ${dpmResult.message}"
                    }

                    AiAction.TYPE_CAMERA -> {
                        val cameraAction = action.cameraAction
                        if (cameraAction.isNullOrEmpty()) {
                            outputMsg = "camera_action field is empty"
                        } else {
                            CameraActivity.lastResult = null
                            val cameraIntent = Intent(appContext, CameraActivity::class.java).apply {
                                putExtra(CameraActivity.EXTRA_CAMERA_ACTION, cameraAction)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            appContext.startActivity(cameraIntent)

                            if (cameraAction == CameraActivity.ACTION_START_VIDEO) {
                                delay(3000)
                                success = true
                                outputMsg = CameraActivity.lastResult ?: "Video recording started"
                            } else {
                                var waited = 0L
                                while (CameraActivity.lastResult == null && waited < 15000) {
                                    delay(500)
                                    waited += 500
                                }
                                val result = CameraActivity.lastResult
                                if (result != null) {
                                    success = true
                                    outputMsg = result

                                    activeRemoteSession?.let { session ->
                                        when (cameraAction) {
                                            CameraActivity.ACTION_TAKE_PHOTO -> {
                                                val uri = CameraActivity.lastPhotoUri
                                                if (uri != null) {
                                                    try {
                                                        appContext.contentResolver.openInputStream(uri)?.use { input ->
                                                            RemoteOutboundHelper.sendPhoto(
                                                                remoteBridge, session,
                                                                input.readBytes(), caption = null, fileName = "photo.jpg"
                                                            )
                                                            outputMsg = appendRemoteBinaryMediaNote(session, result)
                                                        }
                                                    } catch (_: Exception) { }
                                                }
                                            }
                                            CameraActivity.ACTION_STOP_VIDEO -> {
                                                val uri = CameraActivity.lastVideoUri
                                                if (uri != null) {
                                                    try {
                                                        appContext.contentResolver.openInputStream(uri)?.use { input ->
                                                            RemoteOutboundHelper.sendVideo(
                                                                remoteBridge, session,
                                                                input.readBytes(), caption = null, fileName = "video.mp4"
                                                            )
                                                            outputMsg = appendRemoteBinaryMediaNote(session, result)
                                                        }
                                                    } catch (_: Exception) { }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    outputMsg = "Camera operation timed out"
                                }
                            }
                        }
                    }

                    AiAction.TYPE_AUDIO_RECORD -> {
                        val recordAction = action.audioRecordAction
                        if (recordAction.isNullOrEmpty()) {
                            outputMsg = "audio_record_action field is empty"
                        } else {
                            AudioRecordActivity.lastResult = null
                            val recordIntent = Intent(appContext, AudioRecordActivity::class.java).apply {
                                putExtra(AudioRecordActivity.EXTRA_AUDIO_RECORD_ACTION, recordAction)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            appContext.startActivity(recordIntent)

                            if (recordAction == AudioRecordActivity.ACTION_START_RECORD) {
                                delay(3000)
                                success = true
                                outputMsg = AudioRecordActivity.lastResult ?: "Audio recording started"
                            } else {
                                var waited = 0L
                                while (AudioRecordActivity.lastResult == null && waited < 15000) {
                                    delay(500)
                                    waited += 500
                                }
                                val result = AudioRecordActivity.lastResult
                                if (result != null) {
                                    success = true
                                    outputMsg = result

                                    activeRemoteSession?.let { session ->
                                        val uri = AudioRecordActivity.lastAudioUri
                                        if (uri != null) {
                                            try {
                                                appContext.contentResolver.openInputStream(uri)?.use { input ->
                                                    RemoteOutboundHelper.sendAudio(
                                                        remoteBridge, session,
                                                        input.readBytes(), caption = null, fileName = "audio.m4a"
                                                    )
                                                    outputMsg = appendRemoteBinaryMediaNote(session, result)
                                                }
                                            } catch (_: Exception) { }
                                        }
                                    }
                                } else {
                                    outputMsg = "Audio record operation timed out"
                                }
                            }
                        }
                    }

                    AiAction.TYPE_SCREEN_RECORD -> {
                        val recordAction = action.screenRecordAction
                        if (recordAction.isNullOrEmpty()) {
                            outputMsg = "screen_record_action field is empty"
                        } else if (recordAction == ScreenRecordActivity.ACTION_STOP) {
                            if (!ScreenRecordService.isRecording) {
                                outputMsg = "当前没有在录屏"
                            } else {
                                val stopIntent = Intent(appContext, ScreenRecordService::class.java)
                                stopIntent.action = "STOP"
                                appContext.startService(stopIntent)
                                delay(2000)
                                success = true
                                val filePath = ScreenRecordService.lastRecordedFile
                                val stoppedMsg = "录屏已停止, 文件: ${filePath ?: "unknown"}"
                                outputMsg = stoppedMsg

                                if (filePath != null) {
                                    activeRemoteSession?.let { session ->
                                        try {
                                            val file = File(filePath)
                                            if (file.exists()) {
                                                RemoteOutboundHelper.sendVideo(
                                                    remoteBridge, session,
                                                    file.readBytes(), caption = null, fileName = file.name
                                                )
                                                outputMsg = appendRemoteBinaryMediaNote(session, stoppedMsg)
                                            }
                                        } catch (_: Exception) { }
                                    }
                                }
                            }
                        } else {
                            if (ScreenRecordService.isRecording) {
                                success = true
                                outputMsg = "录屏已在进行中"
                            } else {
                                ScreenRecordActivity.lastResult = null
                                val recordIntent = Intent(appContext, ScreenRecordActivity::class.java).apply {
                                    putExtra(ScreenRecordActivity.EXTRA_RECORD_ACTION, recordAction)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                appContext.startActivity(recordIntent)
                                delay(1500)
                                success = true
                                outputMsg = "录屏授权对话框已弹出，请在下一步点击「立即开始」按钮完成授权"
                            }
                        }
                    }

                    AiAction.TYPE_VOLUME -> {
                        val volumeAction = action.volumeAction
                        if (volumeAction.isNullOrEmpty()) {
                            outputMsg = "volume_action field is empty"
                        } else {
                            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            val streamType = when (action.extras?.get("stream")?.toString()) {
                                "ring" -> AudioManager.STREAM_RING
                                "notification" -> AudioManager.STREAM_NOTIFICATION
                                "alarm" -> AudioManager.STREAM_ALARM
                                "system" -> AudioManager.STREAM_SYSTEM
                                else -> AudioManager.STREAM_MUSIC
                            }
                            val streamName = action.extras?.get("stream")?.toString() ?: "music"
                            when (volumeAction) {
                                "set" -> {
                                    val maxVol = audioManager.getStreamMaxVolume(streamType)
                                    val level = when (val v = action.extras?.get("level")) {
                                        is Number -> v.toInt()
                                        is String -> v.toIntOrNull() ?: 50
                                        else -> 50
                                    }
                                    val vol = (level * maxVol / 100).coerceIn(0, maxVol)
                                    audioManager.setStreamVolume(streamType, vol, 0)
                                    success = true
                                    outputMsg = "音量已设置: $streamName $vol/$maxVol ($level%)"
                                }
                                "adjust_up" -> {
                                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_RAISE, 0)
                                    val cur = audioManager.getStreamVolume(streamType)
                                    val max = audioManager.getStreamMaxVolume(streamType)
                                    success = true
                                    outputMsg = "音量已调高: $streamName $cur/$max"
                                }
                                "adjust_down" -> {
                                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_LOWER, 0)
                                    val cur = audioManager.getStreamVolume(streamType)
                                    val max = audioManager.getStreamMaxVolume(streamType)
                                    success = true
                                    outputMsg = "音量已调低: $streamName $cur/$max"
                                }
                                "mute" -> {
                                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_MUTE, 0)
                                    success = true
                                    outputMsg = "已静音: $streamName"
                                }
                                "unmute" -> {
                                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_UNMUTE, 0)
                                    val cur = audioManager.getStreamVolume(streamType)
                                    val max = audioManager.getStreamMaxVolume(streamType)
                                    success = true
                                    outputMsg = "已取消静音: $streamName $cur/$max"
                                }
                                "get" -> {
                                    val cur = audioManager.getStreamVolume(streamType)
                                    val max = audioManager.getStreamMaxVolume(streamType)
                                    val pct = if (max > 0) cur * 100 / max else 0
                                    val muted = audioManager.isStreamMute(streamType)
                                    success = true
                                    outputMsg = "当前音量: $streamName $cur/$max ($pct%)${if (muted) " [已静音]" else ""}"
                                }
                                else -> outputMsg = "Unknown volume_action: $volumeAction"
                            }
                        }
                    }

                    AiAction.TYPE_WAKE_SCREEN -> {
                        val pm = appContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                        @Suppress("DEPRECATION")
                        val wakeLock = pm.newWakeLock(
                            android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                            "andclaw:wakeup"
                        )
                        wakeLock.acquire(3000L)
                        wakeLock.release()
                        success = true
                        outputMsg = "屏幕已唤醒"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    activePlan = planManager.recordFailure(activePlan, "Execution Exception: ${e.message}", terminal = false)
                    addMessage("system", "Execution Exception: ${e.message}")
                }
            }

            val finalMsg = outputMsg
            activeStepTiming?.mark(
                "action_execution_finished",
                "type=${action.type} success=$success result=${finalMsg?.take(120).orEmpty()}"
            )
            if (success && isAgentRunning) {
                withContext(Dispatchers.Main) {
                    val msg = if (finalMsg != null) "Action success.\n$finalMsg" else "Action success."
                    activePlan = planManager.recordObservation(activePlan, msg)
                    addMessage("system", msg)
                    addMessage("system", "Waiting for UI to settle...")
                }
                continueAfterSuccessfulAction(action, finalMsg)
            } else {
                withContext(Dispatchers.Main) {
                    val willRetry = ordinaryFailureReplanCount < 2
                    activePlan = planManager.recordFailure(activePlan, finalMsg ?: "Action failed", terminal = !willRetry)
                    if (finalMsg != null) addMessage("system", finalMsg)
                }
                handleOrdinaryActionFailure(finalMsg ?: "动作执行失败")
            }
        }
    }

    private suspend fun handleOrdinaryActionFailure(reason: String) {
        if (!isAgentRunning) return
        if (ordinaryFailureReplanCount >= 2) {
            withContext(Dispatchers.Main) {
                stopAgent(reason)
            }
            return
        }
        ordinaryFailureReplanCount++
        val patchPlan = shouldPatchPlanForFailure(reason)
        withContext(Dispatchers.Main) {
            val retryMode = if (patchPlan) "Replanning before retry" else "Refreshing UI before retry"
            addMessage("system", "Action failed. $retryMode... ($ordinaryFailureReplanCount/2)")
        }
        if (patchPlan) {
            replanActivePlan("Ordinary action failure: $reason")
        } else {
            activeStepTiming?.mark("replan_skipped", reason)
        }
        if (!isAgentRunning) return
        executeAgentStep(uiState.userInput)
    }

    private fun shouldPatchPlanForFailure(reason: String): Boolean {
        val normalized = reason.lowercase()
        val transientUiFailures = listOf(
            "node_id",
            "latest ui snapshot",
            "target_text",
            "input-method keyboard",
            "click gesture was cancelled",
            "no focused input field"
        )
        return transientUiFailures.none { normalized.contains(it) }
    }

    private suspend fun continueAfterSuccessfulAction(action: AiAction, actionResult: String? = null) {
        activeStepTiming?.mark("ui_wait_started", "type=${action.type}")
        waitAfterSuccessfulAction(action)
        activeStepTiming?.mark("ui_wait_finished", "type=${action.type}")
        if (!isAgentRunning) return
        if (shouldVerifyAfterSuccessfulAction(action, actionResult)) {
            routineSuccessSinceVerifier = 0
            verifyCurrentPlanStep(actionResult)
        } else {
            routineSuccessSinceVerifier++
            activeStepTiming?.mark("plan_verifier_skipped", "routine_successes=$routineSuccessSinceVerifier")
        }
        activeStepTiming?.mark("step_complete")
        if (!isAgentRunning) return
        withContext(Dispatchers.Main) {
            addMessage("system", "UI settled. Analyzing next step...")
        }
        executeAgentStep(uiState.userInput)
    }

    private fun shouldVerifyAfterSuccessfulAction(action: AiAction, actionResult: String?): Boolean {
        if (planManager.toPromptContext(activePlan) == null) return false
        if (actionResult?.contains("recovered", ignoreCase = true) == true) return true
        val currentStepType = activePlan
            ?.steps
            ?.firstOrNull { it.id == activePlan?.currentStepId }
            ?.type
        return when (action.type) {
            AiAction.TYPE_INTENT,
            AiAction.TYPE_GLOBAL_ACTION,
            AiAction.TYPE_DPM,
            AiAction.TYPE_HTTP_REQUEST,
            AiAction.TYPE_DOWNLOAD,
            AiAction.TYPE_CAMERA,
            AiAction.TYPE_SCREEN_RECORD,
            AiAction.TYPE_AUDIO_RECORD,
            AiAction.TYPE_WAKE_SCREEN -> true

            AiAction.TYPE_CLICK,
            AiAction.TYPE_TEXT_INPUT,
            AiAction.TYPE_SWIPE,
            AiAction.TYPE_LONG_PRESS -> {
                currentStepType in setOf(StepType.VERIFY, StepType.DECISION) ||
                    routineSuccessSinceVerifier >= 8
            }

            else -> false
        }
    }

    private suspend fun verifyCurrentPlanStep(actionResult: String?) {
        val planContext = planManager.toVerifierPromptContext(activePlan) ?: return
        activeStepTiming?.mark("plan_verifier_started")
        val screenData = AgentAccessibilityService.instance?.captureScreenHierarchy() ?: "Screen data inaccessible"
        activeStepTiming?.mark("plan_verifier_screen_captured", "chars=${screenData.length}, plan_chars=${planContext.length}")
        val response = Utils.callStepVerifier(
            userGoal = uiState.userInput,
            screenData = screenData,
            planContext = planContext,
            actionResult = actionResult ?: "Action success.",
            config = config,
            context = appContext
        )
        activeStepTiming?.mark("plan_verifier_response_received", "chars=${response.length}")
        val verification = planManager.parseVerification(response)
        activeStepTiming?.mark("plan_verifier_parsed", "hasVerification=${verification != null}")
        if (verification != null) {
            val normalizedVerification = normalizeVerifierBlocker(verification)
            activePlan = planManager.applyVerification(activePlan, normalizedVerification)
            val evidence = normalizedVerification.evidence?.takeIf { it.isNotBlank() } ?: "step status updated"
            addMessage("system", "Plan verifier: $evidence")
            if (normalizedVerification.taskComplete) {
                sendRemotePlanProgress(activePlan, "Plan completed")
                stopAgent(normalizedVerification.evidence ?: "任务完成")
                return
            }
            sendRemotePlanProgress(activePlan, "Plan progress")
            normalizedVerification.blocker?.takeIf { it.isNotBlank() }?.let { blocker ->
                replanActivePlan("Verifier found blocker: $blocker")
            }
        }
    }

    private fun normalizeVerifierBlocker(verification: StepVerification): StepVerification {
        val blocker = verification.blocker?.trim().orEmpty()
        if (blocker.isBlank() || isActionableVerifierBlocker(blocker)) {
            return verification
        }

        val evidence = listOfNotNull(
            verification.evidence?.takeIf { it.isNotBlank() },
            "Ignored non-actionable verifier blocker: $blocker"
        ).joinToString(" ")

        return verification.copy(
            currentStepStatus = when {
                verification.currentStepStatus.equals("BLOCKED", ignoreCase = true) -> "IN_PROGRESS"
                verification.currentStepStatus.equals("FAILED", ignoreCase = true) -> "IN_PROGRESS"
                else -> verification.currentStepStatus
            },
            evidence = evidence,
            blocker = null
        )
    }

    private fun isActionableVerifierBlocker(blocker: String): Boolean {
        val text = blocker.lowercase()
        val hardBlockerKeywords = listOf(
            "login",
            "log in",
            "sign in",
            "permission",
            "captcha",
            "payment",
            "credential",
            "unsafe",
            "unavailable",
            "forbidden",
            "unauthorized",
            "登录",
            "登陆",
            "权限",
            "验证码",
            "支付",
            "付款",
            "账号",
            "账户",
            "密码",
            "凭证",
            "不安全",
            "不可用",
            "无法访问",
            "未安装"
        )
        return hardBlockerKeywords.any { text.contains(it) }
    }

    private fun sendRemotePlanProgress(plan: AgentPlan?, title: String) {
        val session = activeRemoteSession ?: return
        val text = planManager.formatProgress(plan, title) ?: return
        scope.launch(Dispatchers.IO) {
            sendRemoteText(session, text, includeStepTiming = false)
        }
    }

    private suspend fun waitAfterSuccessfulAction(action: AiAction) {
        val svc = AgentAccessibilityService.instance
        when (action.type) {
            AiAction.TYPE_CLICK,
            AiAction.TYPE_TEXT_INPUT,
            AiAction.TYPE_GLOBAL_ACTION -> {
                svc?.waitForUiStabilization(timeoutMs = 900, quietMs = 160, minWaitMs = 260) ?: delay(260)
            }

            AiAction.TYPE_SWIPE -> {
                val gestureMs = if (action.duration > 0) action.duration else 300L
                svc?.waitForUiStabilization(
                    timeoutMs = (gestureMs + 700L).coerceAtMost(1600L),
                    quietMs = 180,
                    minWaitMs = 180
                ) ?: delay(gestureMs + 150L)
            }

            AiAction.TYPE_LONG_PRESS -> {
                val gestureMs = if (action.duration > 0) action.duration else 1000L
                svc?.waitForUiStabilization(
                    timeoutMs = (gestureMs + 700L).coerceAtMost(2200L),
                    quietMs = 180,
                    minWaitMs = 220
                ) ?: delay(gestureMs + 150L)
            }

            AiAction.TYPE_INTENT -> {
                svc?.waitForUiStabilization(timeoutMs = 1800, quietMs = 220, minWaitMs = 500) ?: delay(900)
            }

            AiAction.TYPE_SCREEN_RECORD,
            AiAction.TYPE_CAMERA,
            AiAction.TYPE_AUDIO_RECORD,
            AiAction.TYPE_WAKE_SCREEN -> {
                svc?.waitForUiStabilization(timeoutMs = 800, quietMs = 180, minWaitMs = 300) ?: delay(300)
            }

            AiAction.TYPE_SCREENSHOT,
            AiAction.TYPE_DOWNLOAD,
            AiAction.TYPE_HTTP_REQUEST,
            AiAction.TYPE_DPM,
            AiAction.TYPE_VOLUME -> {
                delay(80)
            }

            else -> {
                delay(250)
            }
        }
    }

    private fun executeIntent(action: AiAction) {
        try {
            Intent(action.action).let { intent ->
                if (!action.data.isNullOrEmpty()) {
                    intent.data = action.data.toUri()
                }
                if (!action.packageName.isNullOrEmpty() && !action.className.isNullOrEmpty()) {
                    intent.component = ComponentName(action.packageName, action.className)
                } else if (!action.packageName.isNullOrEmpty()) {
                    intent.setPackage(action.packageName)
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                action.fillIntentExtras(intent)
                appContext.startActivity(intent)
            }
        } catch (e: Exception) {
            addMessage("system", "Intent failed: ${e.message}")
        }
    }

    // --- Helpers ---

    private suspend fun captureScreenBase64(): String? {
        val svc = AgentAccessibilityService.instance ?: return null
        return suspendCancellableCoroutine { cont ->
            svc.captureScreenshot { bitmap ->
                if (bitmap != null) {
                    val baos = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                    cont.resume(Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))
                } else {
                    cont.resume(null)
                }
            }
        }
    }

    fun addMessage(role: String, content: String, action: AiAction? = null, screenshotBase64: String? = null) {
        val msg = ChatMessage(role, content, action, screenshotBase64 = screenshotBase64)
        _messages.update { current -> current + msg }
        Log.d(TAG, "[$role]: $content")

        scope.launch(Dispatchers.IO) {
            val entity = ChatMessageEntity(
                role = msg.role,
                content = msg.content,
                actionJson = action?.let { gson.toJson(it) },
                timestamp = msg.timestamp
            )
            val id = chatDao.insert(entity)
            _messages.update { list ->
                list.map { if (it.timestamp == msg.timestamp && it.role == msg.role && it.id == 0L) it.copy(id = id) else it }
            }
        }

        val sessionToEcho = activeRemoteSession
        if (RemoteOutboundHelper.shouldAttemptRemoteEcho(role, sessionToEcho)) {
            scope.launch(Dispatchers.IO) {
                sendRemoteText(sessionToEcho, "[$role] $content")
            }
        }
    }

    fun deleteMessages(ids: List<Long>) {
        scope.launch(Dispatchers.IO) {
            chatDao.deleteByIds(ids)
            _messages.update { list -> list.filter { it.id !in ids } }
        }
    }

    fun clearAllMessages() {
        scope.launch(Dispatchers.IO) {
            chatDao.deleteAll()
            _messages.value = emptyList()
        }
    }
}
