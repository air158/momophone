package com.andforce.andclaw

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.text.method.ScrollingMovementMethod
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.andforce.andclaw.databinding.ActivityChatHistoryBinding
import com.andforce.andclaw.plan.PlanListItem
import com.andforce.andclaw.plan.PlanStatus
import com.andforce.andclaw.view.ChatAdapter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        observeMessages()
    }

    private fun setupChatList() {
        chatAdapter = ChatAdapter(
            onConfirmAction = { action -> AgentController.performConfirmedAction(action) },
            onSelectionChanged = { isSelecting, count -> onSelectionChanged(isSelecting, count) }
        )
        binding.chatRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ChatHistoryActivity)
            adapter = chatAdapter
        }
    }

    private fun onSelectionChanged(isSelecting: Boolean, selectedCount: Int) {
        inSelectionMode = isSelecting
        if (isSelecting) {
            binding.toolbar.title = getString(R.string.selected_count, selectedCount)
        } else {
            binding.toolbar.title = "Andclaw AI Agent"
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
            R.id.action_clear_all -> {
                confirmClearAll()
                true
            }
            R.id.action_plans -> {
                showPlans()
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

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setMessage(R.string.confirm_clear_all)
            .setPositiveButton(android.R.string.ok) { _, _ -> AgentController.clearAllMessages() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPlans() {
        val plans = AgentController.listPlans()
        if (plans.isEmpty()) {
            AlertDialog.Builder(this)
                .setMessage(R.string.no_plans)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val labels = plans.map { plan ->
            val updated = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(plan.updatedAt))
            "${plan.summary}\n${plan.status} · $updated · ${plan.currentStepId ?: "-"}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.plans)
            .setItems(labels) { _, which -> showPlanDetail(plans[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPlanDetail(plan: PlanListItem) {
        val markdown = AgentController.getPlanMarkdown(plan.id)
        val view = TextView(this).apply {
            text = markdown
            setPadding(48, 24, 48, 24)
            textSize = 13f
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(true)
        }
        val canResume = plan.status in setOf(
            PlanStatus.RUNNING,
            PlanStatus.PLANNING,
            PlanStatus.PAUSED,
            PlanStatus.BLOCKED,
            PlanStatus.FAILED
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.plan_detail)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.resume_plan, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (canResume) {
                AgentController.resumePlan(plan.id)
                dialog.dismiss()
            } else {
                AlertDialog.Builder(this)
                    .setMessage(R.string.plan_resume_unavailable)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
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
                    binding.chatRecyclerView.smoothScrollToPosition(messageList.size - 1)
                }
            }
        }
    }
}
