package com.tzk1986.libretvaz.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A5（审计 2026-11-11）跳过片头片尾纯判定：0=关；短视频头尾窗重叠不跳；尾跳需过半防起播误触 */
class SkipIntroTest {

    @Test fun `off never jumps`() {
        assertNull(skipStartJump(0, 600_000, 0))
        assertNull(skipEndJump(599_000, 600_000, 0))
    }

    @Test fun `intro jump when inside window and duration ample`() {
        // 85s 片头、36:48 片长、起播 5s → 跳 85s
        assertEquals(85_000L, skipStartJump(5_000, 2_208_000, 85))
        // 已过片头 → 不再跳
        assertNull(skipStartJump(90_000, 2_208_000, 85))
    }

    @Test fun `intro skipped for unknown or short duration`() {
        assertNull(skipStartJump(5_000, 0, 85))        // 时长未知
        assertNull(skipStartJump(5_000, 100_000, 85)) // 片长 ≤ 2×片头=短视频，不跳
    }

    @Test fun `outro jump only past midpoint inside window`() {
        // 剩 20s < 25s 尾窗、已过半场 → 跳结尾
        assertEquals(599_000L, skipEndJump(580_000, 600_000, 25))
        // 刚起播 pos=3s、dur=30s 的短视频：落入尾窗但未过半 → 不跳
        assertNull(skipEndJump(3_000, 30_000, 25))
        // 距结束尚远 → 不跳
        assertNull(skipEndJump(100_000, 600_000, 25))
        // 时长未知 → 不跳
        assertNull(skipEndJump(5_000, 0, 25))
    }
}
