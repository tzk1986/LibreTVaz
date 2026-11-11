package com.tzk1986.libretvaz.data

/**
 * 种子闸门决策纯函数（审计 2026-11-11 E44）：修复「先装无源版（assets 空表）→ 闸门先被烧掉
 * → 再装私有包永远跳过补种」的公开分发场景缺陷。规则=只有本轮真的执行了加源才写闸门版本号；
 * 空站单（公开克隆/读失败）不种也不写，留待私有包装入后的下次冷启/进资源口补种。
 */
object SeedGate {
    enum class Action {
        /** 先加源、成功后由调用方写闸门版本号 */
        SEED_AND_STAMP,

        /** 闸门已到目标版本：整轮跳过 */
        SKIP_DONE,

        /** 站单为空：本轮不种、不写闸门 */
        SKIP_EMPTY,
    }

    fun plan(currentVer: String, targetVer: String, presetSize: Int): Action = when {
        currentVer == targetVer -> Action.SKIP_DONE
        presetSize == 0 -> Action.SKIP_EMPTY
        else -> Action.SEED_AND_STAMP
    }
}
