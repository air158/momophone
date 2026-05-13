package com.andforce.andclaw

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for AgentAccessibilityService.containsLabelSegment — the
 * boundary-aware substring matcher used by the UI tree dedup pass to drop
 * pure-text leaves whose content is already exposed by an ancestor's label.
 *
 * Note: we can't instantiate AgentAccessibilityService in a JVM test (it
 * needs an Android Context), so we reproduce the exact algorithm here. Any
 * change to the production implementation MUST be mirrored here — these
 * tests document the semantics that toPromptView() relies on.
 */
class LabelSegmentTest {

    private val boundaries = setOf(' ', ',', '，', '|', '、', '\t', '\n')

    private fun containsLabelSegment(haystack: String, needle: String): Boolean {
        if (needle.isEmpty() || haystack.length < needle.length) return false
        var from = 0
        while (from <= haystack.length - needle.length) {
            val idx = haystack.indexOf(needle, from, ignoreCase = true)
            if (idx < 0) return false
            val beforeOk = idx == 0 || haystack[idx - 1] in boundaries
            val end = idx + needle.length
            val afterOk = end == haystack.length || haystack[end] in boundaries
            if (beforeOk && afterOk) return true
            from = idx + 1
        }
        return false
    }

    @Test
    fun `comma-separated segment matches`() {
        val haystack = "宜宾动感鱼,让机器干全部的活,05-07, · 四川,回复 按钮,"
        assertTrue(containsLabelSegment(haystack, "宜宾动感鱼"))
        assertTrue(containsLabelSegment(haystack, "05-07"))
        assertTrue(containsLabelSegment(haystack, "· 四川"))
    }

    @Test
    fun `prevents substring match without boundary`() {
        // 'Sea' inside 'Search' would falsely match a naive substring check;
        // the segment matcher must reject it.
        assertFalse(containsLabelSegment("Search", "Sea"))
        assertFalse(containsLabelSegment("回复按钮", "回复"))
    }

    @Test
    fun `matches at start and end of haystack`() {
        assertTrue(containsLabelSegment("Hello,world", "Hello"))
        assertTrue(containsLabelSegment("Hello,world", "world"))
    }

    @Test
    fun `chinese comma is a valid boundary`() {
        assertTrue(containsLabelSegment("名字，内容，时间", "内容"))
    }

    @Test
    fun `pipe is a valid boundary`() {
        assertTrue(containsLabelSegment("title | subtitle | meta", "subtitle"))
    }

    @Test
    fun `empty or oversized needle is rejected`() {
        assertFalse(containsLabelSegment("anything", ""))
        assertFalse(containsLabelSegment("short", "longer"))
    }

    @Test
    fun `case insensitive match works`() {
        assertTrue(containsLabelSegment("Click,SUBMIT,Cancel", "submit"))
    }

    @Test
    fun `repeated needle still matches once boundary is found`() {
        // The first occurrence "Searc" has no boundary; the loop must keep
        // scanning and find the second occurrence which does.
        assertTrue(containsLabelSegment("Searcher,Search", "Search"))
    }
}
