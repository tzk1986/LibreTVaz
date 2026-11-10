package com.tzk1986.libretvaz.ui

import org.junit.Assert.assertEquals
import org.junit.Test

// TVBox 复刻（用户 2026-11-09）全屏左/右半区竖向拖拽纯逻辑（无 Android 依赖，可单测）。
class AvGesturesTest {
    @Test
    fun `left half is brightness right half is volume`() {
        assertEquals(EdgeZone.Brightness, edgeZone(10f, 100f))
        assertEquals(EdgeZone.Brightness, edgeZone(49f, 100f))
        assertEquals(EdgeZone.Volume, edgeZone(50f, 100f))
        assertEquals(EdgeZone.Volume, edgeZone(90f, 100f))
    }

    @Test
    fun `drag up raises level and down lowers it`() {
        // dy 为纵向位移，向上为负：从 0.5 上拖半屏高→1.0
        assertEquals(1f, dragToLevel(-50f, 100f, 0.5f), 0f)
        // 下拖 25/100→0.25
        assertEquals(0.25f, dragToLevel(25f, 100f, 0.5f), 0f)
    }

    @Test
    fun `dragToLevel clamps to full range`() {
        assertEquals(1f, dragToLevel(-200f, 100f, 0.5f), 0f)
        assertEquals(0f, dragToLevel(200f, 100f, 0.5f), 0f)
    }

    @Test
    fun `tapZone splits width in thirds`() {
        // 双击分区（TVBox）：左1/3=退、中=切播放、右1/3=进；边界含左不含右
        assertEquals(TapZone.Left, tapZone(0f, 99f))
        assertEquals(TapZone.Left, tapZone(32f, 99f))
        assertEquals(TapZone.Center, tapZone(33f, 99f))
        assertEquals(TapZone.Center, tapZone(65f, 99f))
        assertEquals(TapZone.Right, tapZone(66f, 99f))
        assertEquals(TapZone.Right, tapZone(99f, 99f))
    }
}
