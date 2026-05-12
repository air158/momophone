package com.afwsamples.testdpc.policy.locktask

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.afwsamples.testdpc.databinding.ActivityAiSettingsBinding
import com.base.services.BridgeStatus
import com.base.services.ClawBotLoginStatus
import com.base.services.IAiConfigService
import com.base.services.IRemoteBridgeService
import com.base.services.IRemoteChannelConfigService
import com.base.services.RemoteChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.koin.android.ext.android.inject
import java.net.HttpURLConnection
import java.net.URL

class AiSettingsActivity : AppCompatActivity() {

    private companion object {
        const val DEFAULT_PROVIDER = "momoai"
        const val DEFAULT_API_URL = "https://www.momoai.pro/api/v1/chat/completions"
        const val DEFAULT_MODEL = "momoai/momo_196"
        val MODEL_PRESETS = arrayOf(
            "momoai/momo_196",
            "momoai/momo_237"
        )
        const val REGISTER_URL = "https://www.momoai.pro/"
    }

    private lateinit var binding: ActivityAiSettingsBinding
    private val aiConfigService: IAiConfigService by inject()
    private val channelConfig: IRemoteChannelConfigService by inject()
    private val remoteBridge: IRemoteBridgeService by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.etModel.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, MODEL_PRESETS)
        )
        binding.etModel.threshold = 1
        binding.etModel.setOnClickListener { binding.etModel.showDropDown() }

        loadCurrentConfig()
        observeRemoteChannelSummary()

        binding.btnMomoLogin.setOnClickListener { loginMomoAccount() }
        binding.tvMomoRegister.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REGISTER_URL)))
            } catch (_: Exception) {
            }
        }
        binding.btnTestApi.setOnClickListener { testApiConnection() }
        binding.btnOpenRemoteChannelSettings.setOnClickListener {
            startActivity(Intent(this, RemoteChannelSettingsActivity::class.java))
        }
        binding.btnOpenMdm.setOnClickListener {
            startActivity(Intent(this, SetupKioskModeActivity::class.java))
        }
        binding.btnSave.setOnClickListener { saveAndFinish() }
    }

    private fun observeRemoteChannelSummary() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    channelConfig.activeRemoteChannel,
                    remoteBridge.telegramStatus,
                    remoteBridge.feishuStatus,
                    remoteBridge.clawBotStatus,
                    remoteBridge.clawBotLoginStatus
                ) { active, tg, fs, cb, cbLogin ->
                    active to summarizeRemoteLine(active, tg, fs, cb, cbLogin)
                }.collect { (_, line) ->
                    binding.tvRemoteChannelSummary.text = line
                }
            }
        }
    }

    private fun summarizeRemoteLine(
        active: RemoteChannel,
        tg: BridgeStatus,
        fs: BridgeStatus,
        cb: BridgeStatus,
        cbLogin: ClawBotLoginStatus
    ): String {
        val mode = when (active) {
            RemoteChannel.TELEGRAM -> "Telegram"
            RemoteChannel.FEISHU -> "飞书"
            RemoteChannel.CLAWBOT -> "ClawBot"
        }
        val detail = when (active) {
            RemoteChannel.TELEGRAM -> bridgeShort(tg)
            RemoteChannel.FEISHU -> bridgeShort(fs)
            RemoteChannel.CLAWBOT -> formatClawBotShort(cb, cbLogin)
        }
        return "当前：$mode · $detail"
    }

    private fun bridgeShort(s: BridgeStatus): String = when (s) {
        BridgeStatus.NOT_CONFIGURED -> "未配置"
        BridgeStatus.STOPPED -> "已停止"
        BridgeStatus.CONNECTED -> "已连接"
        BridgeStatus.DISCONNECTED -> "未连接"
    }

    private fun formatClawBotShort(bridge: BridgeStatus, login: ClawBotLoginStatus): String {
        val b = bridgeShort(bridge)
        val l = when (login) {
            ClawBotLoginStatus.NOT_CONFIGURED -> "未配置"
            ClawBotLoginStatus.LOGIN_REQUIRED -> "需登录"
            ClawBotLoginStatus.QR_READY -> "二维码就绪"
            ClawBotLoginStatus.WAITING_CONFIRM -> "待确认"
            ClawBotLoginStatus.CONNECTED -> "已登录"
            ClawBotLoginStatus.DISCONNECTED -> "已断开"
            ClawBotLoginStatus.STOPPED -> "已停止"
        }
        return "桥接 $b · 登录 $l"
    }

    private fun loadCurrentConfig() {
        val savedKey = aiConfigService.loadProviderKey(DEFAULT_PROVIDER)
        binding.etApiKey.setText(savedKey.ifEmpty { aiConfigService.apiKey })
        // Model field is hidden; carry a stable default forward to updateConfig.
        binding.etModel.setText(aiConfigService.model.ifEmpty { DEFAULT_MODEL }, false)
    }

    private fun loginMomoAccount() {
        val account = binding.etMomoAccount.text.toString().trim()
        val password = binding.etMomoPassword.text.toString()
        if (account.isEmpty() || password.isEmpty()) {
            showLoginResult("请输入账号和密码", isError = true)
            return
        }
        binding.btnMomoLogin.isEnabled = false
        showLoginResult("正在登录...", isError = false)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                MomoAccountClient.login(account, password)
            }
            binding.btnMomoLogin.isEnabled = true
            result.onSuccess { res ->
                binding.etApiKey.setText(res.momoApiKey)
                binding.etMomoPassword.setText("")
                aiConfigService.saveProviderKey(DEFAULT_PROVIDER, res.momoApiKey)
                aiConfigService.updateConfig(
                    provider = DEFAULT_PROVIDER,
                    apiUrl = DEFAULT_API_URL,
                    apiKey = res.momoApiKey,
                    model = DEFAULT_MODEL
                )
                showLoginResult("登录成功：${res.username} · 已自动填入 API Key", isError = false)
            }.onFailure { e ->
                showLoginResult(e.message ?: "登录失败", isError = true)
            }
        }
    }

    private fun showLoginResult(text: String, isError: Boolean) {
        binding.tvMomoLoginResult.apply {
            visibility = View.VISIBLE
            this.text = text
            setTextColor(getColor(if (isError) com.afwsamples.testdpc.R.color.mdm_status_error else com.afwsamples.testdpc.R.color.mdm_status_running))
        }
    }

    private fun saveAndFinish() {
        val apiKey = binding.etApiKey.text.toString().trim()
        aiConfigService.saveProviderKey(DEFAULT_PROVIDER, apiKey)
        aiConfigService.updateConfig(
            provider = DEFAULT_PROVIDER,
            apiUrl = DEFAULT_API_URL,
            apiKey = apiKey,
            model = binding.etModel.text.toString().ifEmpty { DEFAULT_MODEL }
        )
        finish()
    }

    private fun testApiConnection() {
        val apiKey = binding.etApiKey.text.toString().trim()
        val model = binding.etModel.text.toString().ifEmpty { DEFAULT_MODEL }

        if (apiKey.isEmpty()) {
            showApiResult("请先登录 MomoAI 账号获取 API Key", isError = true)
            return
        }

        binding.btnTestApi.isEnabled = false
        showApiResult("正在测试连接...", isError = false)

        lifecycleScope.launch {
            val result = testOpenAiCompatibleApi(DEFAULT_API_URL, apiKey, model)
            binding.btnTestApi.isEnabled = true
            showApiResult(result.first, result.second)
        }
    }

    private suspend fun testOpenAiCompatibleApi(
        baseUrl: String,
        apiKey: String,
        model: String
    ): Pair<String, Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = if (baseUrl.contains("chat/completions")) baseUrl
            else "${baseUrl.removeSuffix("/")}/chat/completions"

            val body = JSONObject().apply {
                put("model", model)
                put("max_tokens", 64)
                if (!model.contains("k2.5")) {
                    put("temperature", 0.0)
                }
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "Say 'OK' if you can hear me.")
                    })
                })
            }
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
            }
            val startedAt = SystemClock.elapsedRealtime()
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val respBody = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            conn.disconnect()

            if (code in 200..299) {
                val text = JSONObject(respBody)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                "连接成功 ✓\n时延: ${formatLatency(elapsedMs)}\n模型回复: ${text.take(100)}" to false
            } else {
                "连接失败 (HTTP $code)\n时延: ${formatLatency(elapsedMs)}\n$respBody" to true
            }
        } catch (e: Exception) {
            "连接失败: ${e.message}" to true
        }
    }

    private fun formatLatency(ms: Long): String {
        return if (ms < 1000) "${ms}ms" else String.format("%.2fs", ms / 1000.0)
    }

    private fun showApiResult(text: String, isError: Boolean) {
        binding.tvApiTestResult.apply {
            visibility = View.VISIBLE
            this.text = text
            setTextColor(getColor(if (isError) com.afwsamples.testdpc.R.color.mdm_status_error else com.afwsamples.testdpc.R.color.mdm_status_running))
        }
    }
}
