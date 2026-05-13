package com.andforce.andclaw

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.andforce.andclaw.databinding.ActivityChatHistoryBinding
import com.andforce.andclaw.overlay.StatusOverlayController
import com.andforce.andclaw.view.ChatAdapter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ChatHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatHistoryBinding
    private lateinit var chatAdapter: ChatAdapter
    private var inSelectionMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        setupChatList()
        setupInputBar()
        setupExamplePrompts()
        binding.btnGoLogin.setOnClickListener { openSettings() }
        observeMessages()
        observeRunningState()
        StatusOverlayController.ensureStarted(this)
    }

    private fun setupChatList() {
        chatAdapter = ChatAdapter(
            onConfirmAction = { action -> AgentController.performConfirmedAction(action) },
            onSelectionChanged = { isSelecting, count -> onSelectionChanged(isSelecting, count) }
        )
        binding.chatRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ChatHistoryActivity).also {
                it.stackFromEnd = true
            }
            adapter = chatAdapter
        }
    }

    private fun setupInputBar() {
        binding.btnSend.setOnClickListener { sendCommand() }
        binding.btnStop.setOnClickListener {
            AgentController.stopAgent("本地点击停止")
        }
        binding.etCommand.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                binding.btnSend.isEnabled = !s.isNullOrBlank()
                binding.btnSend.alpha = if (binding.btnSend.isEnabled) 1f else 0.4f
            }
        })
        binding.btnSend.isEnabled = false
        binding.btnSend.alpha = 0.4f
    }

    private fun setupExamplePrompts() {
        val chips = listOf(
            binding.examplePrompt1,
            binding.examplePrompt2,
            binding.examplePrompt3,
            binding.examplePrompt4
        )
        chips.forEach { chip ->
            chip.setOnClickListener {
                binding.etCommand.setText(chip.text)
                binding.etCommand.setSelection(chip.text.length)
            }
        }
    }

    private fun sendCommand() {
        val text = binding.etCommand.text.toString().trim()
        if (text.isEmpty()) return
        if (AgentController.isAgentRunning) {
            Toast.makeText(this, "Agent 正在执行中，先停止再发送", Toast.LENGTH_SHORT).show()
            return
        }
        if (AgentController.apiKey.isBlank()) {
            Toast.makeText(this, "请先在设置中登录 MomoAI", Toast.LENGTH_LONG).show()
            openSettings()
            return
        }
        binding.etCommand.setText("")
        AgentController.startAgent(text, remoteSession = null)
    }

    private fun openSettings() {
        try {
            val intent = Intent().apply {
                setClassName(this@ChatHistoryActivity, "com.afwsamples.testdpc.policy.locktask.AiSettingsActivity")
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "设置页未就绪", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onSelectionChanged(isSelecting: Boolean, selectedCount: Int) {
        inSelectionMode = isSelecting
        if (isSelecting) {
            binding.toolbar.title = getString(R.string.selected_count, selectedCount)
            binding.toolbar.subtitle = null
        } else {
            binding.toolbar.title = "Andclaw"
            applyRunningSubtitle(AgentController.isAgentRunning)
        }
        invalidateOptionsMenu()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(
            if (inSelectionMode) R.menu.menu_chat_selection else R.menu.menu_chat_history,
            menu
        )
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                openSettings()
                true
            }
            R.id.action_select_all -> {
                chatAdapter.selectAll()
                true
            }
            R.id.action_delete_selected -> {
                confirmDeleteSelected()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun confirmDeleteSelected() {
        val ids = chatAdapter.getSelectedIds()
        if (ids.isEmpty()) return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.confirm_delete_selected, ids.size))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                AgentController.deleteMessages(ids)
                chatAdapter.exitSelectionMode()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @Deprecated("Use OnBackPressedCallback")
    override fun onBackPressed() {
        if (chatAdapter.isSelectionMode) {
            chatAdapter.exitSelectionMode()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun observeMessages() {
        lifecycleScope.launch {
            AgentController.messages.collect { messageList ->
                chatAdapter.submitList(messageList)
                binding.emptyState.visibility =
                    if (messageList.isEmpty()) View.VISIBLE else View.GONE
                if (messageList.isNotEmpty()) {
                    binding.chatRecyclerView.scrollToPosition(messageList.size - 1)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.notLoggedInBanner.visibility =
            if (AgentController.apiKey.isBlank()) View.VISIBLE else View.GONE
    }

    private fun observeRunningState() {
        lifecycleScope.launch {
            // Poll lightweight isAgentRunning; messages updates also re-trigger UI hints.
            // Keeps subtitle / stop button in sync with both local and remote starts.
            while (true) {
                applyRunningSubtitle(AgentController.isAgentRunning)
                delay(500)
            }
        }
    }

    private fun applyRunningSubtitle(running: Boolean) {
        if (inSelectionMode) return
        binding.toolbar.subtitle = if (running) getString(R.string.status_running) else getString(R.string.status_idle)
        binding.btnStop.visibility = if (running) View.VISIBLE else View.GONE
    }
}
