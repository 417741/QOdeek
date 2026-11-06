package com.qosed.qodeep

/** 所有 DexKit 查找任务的注册表。 */
object DexKitTaskRegistry {
    val tasks = mutableListOf<DexKitTask>()

    fun register(task: DexKitTask) {
        if (tasks.none { it === task }) tasks.add(task)
    }
}
