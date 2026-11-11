package com.tzk1986.libretvaz.data

import com.tzk1986.libretvaz.data.SeedGate.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 审计 2026-11-11 E44：种子闸门须「加源成功后再写」。
 * 缺陷场景=先装无源版（assets 空表）闸门即被烧掉，再装私有包永远跳过补种；
 * 本表钉死三分支决策：已完成跳过 / 空表不烧闸门 / 非空先加后烧。
 */
class SeedGateTest {

    @Test
    fun `gate already at target version skips regardless of preset`() {
        assertEquals(Action.SKIP_DONE, SeedGate.plan("v5", "v5", 30))
        assertEquals(Action.SKIP_DONE, SeedGate.plan("v5", "v5", 0))
    }

    @Test
    fun `empty preset must not burn the gate`() {
        // 无源版/公开克隆：读不到站单则本轮不种也不写闸门，留待私有包装入后补种
        assertEquals(Action.SKIP_EMPTY, SeedGate.plan("", "v5", 0))
        assertEquals(Action.SKIP_EMPTY, SeedGate.plan("v4", "v5", 0))
    }

    @Test
    fun `non-empty preset on version change seeds then stamps`() {
        assertEquals(Action.SEED_AND_STAMP, SeedGate.plan("", "v2", 17))
        assertEquals(Action.SEED_AND_STAMP, SeedGate.plan("v4", "v5", 1))
    }

    @Test
    fun `older gate version against newer target re-seeds`() {
        // 升 SEED_VER 后旧闸门（任意非相等字符串）都须重走补种
        assertEquals(Action.SEED_AND_STAMP, SeedGate.plan("v3", "v5", 8))
    }
}
