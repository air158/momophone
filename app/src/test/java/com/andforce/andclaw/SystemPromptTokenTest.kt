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

    // ── new gates ───────────────────────────────────────────────────────────

    @Test
    fun `simple navigation task excludes http_request, download, screenshot sections`() {
        val p = prompt("打开微信")
        assertFalse("HTTP_REQUEST section should be absent", p.contains("=== HTTP_REQUEST ==="))
        assertFalse("DOWNLOAD section should be absent", p.contains("=== DOWNLOAD ==="))
        assertFalse("SCREENSHOT section should be absent", p.contains("=== SCREENSHOT ==="))
        assertFalse("http_method schema field should be absent", p.contains("http_method"))
    }

    @Test
    fun `api task includes http_request section`() {
        val p = prompt("调用接口查询用户信息")
        assertTrue("HTTP_REQUEST section should be present", p.contains("=== HTTP_REQUEST ==="))
        assertTrue("http_method schema field should be present", p.contains("http_method"))
    }

    @Test
    fun `download task includes download section`() {
        val p = prompt("下载这个apk文件")
        assertTrue("DOWNLOAD section should be present", p.contains("=== DOWNLOAD ==="))
    }

    @Test
    fun `screenshot task includes screenshot section`() {
        val p = prompt("截图保存到相册")
        assertTrue("SCREENSHOT section should be present", p.contains("=== SCREENSHOT ==="))
    }

    @Test
    fun `simple task excludes search and comment caveats`() {
        val p = prompt("打开微信")
        assertFalse("Search-suggestion caveat should be absent", p.contains("Search flows"))
        assertFalse("Comment caveat should be absent", p.contains("Comment/reply submission"))
    }

    @Test
    fun `search task includes search caveat`() {
        val p = prompt("搜索AI视频")
        assertTrue("Search caveat should be present", p.contains("Search flows"))
    }

    @Test
    fun `comment task includes comment caveat`() {
        val p = prompt("在抖音评论区发评论")
        assertTrue("Comment caveat should be present", p.contains("Comment/reply submission"))
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

    @Test
    fun `dump prompt for real test goal`() {
        val goal = "在抖音找一个的AI相关的视频，要视频1000赞以上，在评论区找到点赞最多的评论，仿照这个评论发表评论"
        val p = prompt(goal, isDeviceOwner = false)
        java.io.File("/tmp/new_system_prompt.txt").writeText(p)
        println("Wrote ${p.length} chars to /tmp/new_system_prompt.txt")
    }

    @Test
    fun `print sizes for representative goals`() {
        val cases = listOf(
            "打开微信" to false,
            "在抖音找一个的AI相关的视频，要视频1000赞以上，在评论区找到点赞最多的评论，仿照这个评论发表评论" to false,
            "调用接口查询天气" to false,
            "下载这个apk并安装" to true,
            "拍照 录屏 录音 调音量 截图 下载 调用接口" to true,
        )
        println("\n=== System prompt size by goal ===")
        for ((goal, owner) in cases) {
            val p = prompt(goal, owner)
            val short = if (goal.length > 30) goal.take(28) + "…" else goal
            println("  ${p.length} chars  owner=$owner  goal=\"$short\"")
        }
    }
}
