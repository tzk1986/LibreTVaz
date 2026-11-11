package com.tzk1986.libretvaz.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** E46（审计 2026-11-11）：订阅仓库清单用户可编——序列化往返/空回退默认/按 URL 增删 */
class SubReposTest {

    @Test fun `空raw回退默认清单`() {
        assertEquals(SubRepos.DEFAULT, SubRepos.editable(""))
        assertEquals(SubRepos.DEFAULT, SubRepos.editable("   \n "))
        assertTrue(SubRepos.DEFAULT.size == 14 && SubRepos.DEFAULT.none { it.second.isBlank() })
    }

    @Test fun `序列化往返保序`() {
        val l = listOf("甲" to "https://a.com/1.json", "乙" to "https://b.com/2.json")
        assertEquals(l, SubRepos.editable(SubRepos.serialize(l)))
    }

    @Test fun `删除按norm匹配`() {
        val l = listOf("甲" to "https://a.com/1.json", "乙" to "https://b.com/2.json")
        val r = SubRepos.remove(l, "https://a.com/1.json//")
        assertEquals(listOf("乙" to "https://b.com/2.json"), r)
    }

    @Test fun `add 去重且可改名`() {
        val l = listOf("甲" to "https://a.com/1.json")
        assertEquals(1, SubRepos.add(l, "甲改", "https://a.com/1.json").size)
        assertEquals("甲改", SubRepos.add(l, "甲改", "https://a.com/1.json").first().first)
        val l2 = SubRepos.add(l, "乙", "https://b.com/2.json")
        assertEquals(2, l2.size)
    }

    @Test fun `脏行跳过 名称不含制表换行`() {
        val dirty = "坏行无tab\n甲\thttps://a.com\n\t \n丙\t换行\r\n名字"
        val p = SubRepos.parse(dirty)
        assertTrue(p.any { it.first == "甲" })
        assertFalse(p.any { it.second.isBlank() })
        assertFalse(p.any { it.first.contains("\n") || it.first.contains("\t") })
    }
}
