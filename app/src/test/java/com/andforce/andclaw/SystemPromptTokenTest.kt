package com.andforce.andclaw

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptTokenTest {

    private fun prompt(goal: String, isDeviceOwner: Boolean = false) =
        Utils.buildAgentSystemPrompt(goal, isDeviceOwner)

    // ── exotic sections absent for unrelated tasks ──────────────────────────

    @Test
    fun `social media task excludes camera section`() {
        val p = prompt("在抖音找一个视频，仿照点赞最多的评论发评论")
        assertFalse("CAMERA section should be absent", p.contains("=== CAMERA ==="))
        assertFalse("camera_action schema field should be absent", p.contains("camera_action"))
    }

    @Test
    fun `social media task excludes screen_record section`() {
        val p = prompt("在抖音找一个视频，仿照点赞最多的评论发评论")
        assertFalse("SCREEN_RECORD section should be absent", p.contains("=== SCREEN_RECORD ==="))
        assertFalse("screen_record_action schema field should be absent", p.contains("screen_record_action"))
    }

    @Test
    fun `social media task excludes volume section`() {
        val p = prompt("在抖音找一个视频，仿照点赞最多的评论发评论")
        assertFalse("VOLUME section should be absent", p.contains("=== VOLUME ==="))
        assertFalse("volume_action schema field should be absent", p.contains("volume_action"))
    }

    @Test
    fun `social media task excludes audio_record section`() {
        val p = prompt("在抖音找一个视频，仿照点赞最多的评论发评论")
        assertFalse("AUDIO_RECORD section should be absent", p.contains("=== AUDIO_RECORD ==="))
        assertFalse("audio_record_action schema field should be absent", p.contains("audio_record_action"))
    }

    // ── exotic sections present when task needs them ────────────────────────

    @Test
    fun `photo task includes camera section`() {
        val p = prompt("帮我拍一张照片")
        assertTrue("CAMERA section should be present", p.contains("=== CAMERA ==="))
        assertTrue("camera_action should be in type list", p.contains("camera"))
    }

    @Test
    fun `screen record task includes screen_record section`() {
        val p = prompt("开始录屏")
        assertTrue("SCREEN_RECORD section should be present", p.contains("=== SCREEN_RECORD ==="))
    }

    @Test
    fun `volume task includes volume section`() {
        val p = prompt("把音量调到50%")
        assertTrue("VOLUME section should be present", p.contains("=== VOLUME ==="))
    }

    @Test
    fun `audio record task includes audio_record section`() {
        val p = prompt("录一段音频")
        assertTrue("AUDIO_RECORD section should be present", p.contains("=== AUDIO_RECORD ==="))
    }

    // ── English keywords also trigger correctly ─────────────────────────────

    @Test
    fun `English camera keyword triggers section`() {
        val p = prompt("take a photo of my screen")
        assertTrue(p.contains("=== CAMERA ==="))
    }

    @Test
    fun `English mute keyword triggers volume section`() {
        val p = prompt("mute the phone")
        assertTrue(p.contains("=== VOLUME ==="))
    }

    // ── DPM section only when device owner ──────────────────────────────────

    @Test
    fun `non device owner excludes dpm section`() {
        val p = prompt("打开设置", isDeviceOwner = false)
        assertFalse(p.contains("=== DPM"))
    }

    @Test
    fun `device owner includes dpm section`() {
        val p = prompt("打开设置", isDeviceOwner = true)
        assertTrue(p.contains("=== DPM"))
    }

    // ── token size sanity ───────────────────────────────────────────────────

    @Test
    fun `social media prompt is significantly smaller than full prompt`() {
        val social = prompt("在抖音找视频发评论")
        val full = prompt("拍照 录屏 录音 调音量")
        val savings = full.length - social.length
        println("Full prompt: ${full.length} chars")
        println("Social prompt: ${social.length} chars")
        println("Savings: $savings chars (~${savings / 4} tokens)")
        assertTrue("Should save at least 2000 chars for typical social task", savings > 2000)
    }
}
