package com.qosed.qodeep

import org.luckypray.dexkit.query.base.BaseFinder

/**
 * DexKit 查找任务：每个实现声明一组「特征查询」。
 * 查询结果（类描述符）由 DexKitFinder 扫描宿主 dex 后写入 DexKitCache，
 * 之后通过 requireClass/requireMethod 取回真实类/方法。
 */
interface DexKitTask {
    val tag: String
    fun getQueryMap(): Map<String, BaseFinder>
}
