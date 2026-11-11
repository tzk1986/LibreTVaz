package com.tzk1986.libretvaz.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** B14（审计 2026-11-11）：换线时当前在播集迁移——保序号，新线集少钳位到末集，空线归 0 */
class PlaybackTest {
    @Test fun `keep same index when new line long enough`() {
        assertEquals(5, Playback.epMigrate(5, 24))
        assertEquals(0, Playback.epMigrate(0, 1))
    }

    @Test fun `clamp to last when new line shorter`() {
        assertEquals(11, Playback.epMigrate(23, 12))
        assertEquals(3, Playback.epMigrate(3, 4))
    }

    @Test fun `empty line falls to zero`() {
        assertEquals(0, Playback.epMigrate(7, 0))
    }
}
