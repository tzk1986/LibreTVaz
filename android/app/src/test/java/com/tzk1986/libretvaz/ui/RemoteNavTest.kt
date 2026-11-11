package com.tzk1986.libretvaz.ui

import com.tzk1986.libretvaz.Screen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A13（审计 2026-11-11）：远程词派发——播放态不跳页 + 池沿 backTo 递归归属 */
class RemoteNavTest {

    private val item = com.tzk1986.libretvaz.data.VodSummary(
        sourceKey = "k", sourceName = "s", sourceUrl = "u", vodId = "1", name = "n",
    )

    @Test
    fun `playback screens do not jump`() {
        assertFalse(remoteJump(Screen.Detail(item, Screen.Browse)))
        assertFalse(remoteJump(Screen.Player("http://u", Screen.Browse, live = true)))
    }

    @Test
    fun `non-playback screens jump`() {
        assertTrue(remoteJump(Screen.Browse))
        assertTrue(remoteJump(Screen.Search(false)))
        assertTrue(remoteJump(Screen.Live))
        assertTrue(remoteJump(Screen.Sources))
        assertTrue(remoteJump(Screen.Collect("k", false, null)))
    }

    @Test
    fun `zone taken from pool-carrying screens`() {
        assertTrue(remoteZone(Screen.Search(true)))
        assertFalse(remoteZone(Screen.Search(false)))
        assertTrue(remoteZone(Screen.Keep(true)))
        assertTrue(remoteZone(Screen.Collect("k", true, null)))
        assertTrue(remoteZone(Screen.Hist(true)))
        assertTrue(remoteZone(Screen.Zone))
        assertFalse(remoteZone(Screen.Browse))
    }

    @Test
    fun `detail and player inherit zone along backTo chain`() {
        // 专区详情（从 Zone 进）＝专区池
        assertTrue(remoteZone(Screen.Detail(item, Screen.Zone)))
        // 三层链：Player←Detail(专区搜索)←Search(true)
        assertTrue(remoteZone(Screen.Player("u", Screen.Detail(item, Screen.Search(true)))))
        // 点播链不误升
        assertFalse(remoteZone(Screen.Player("u", Screen.Detail(item, Screen.Browse))))
        assertFalse(remoteZone(Screen.Detail(item, Screen.Search(false))))
    }
}
