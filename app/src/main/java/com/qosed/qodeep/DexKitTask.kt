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

    /**
     * 真实查询的 key 集合（默认 = 全部）。
     * 占位槽（如 slot4~slot16）是为后续功能预留的、当前必然扫不到的空查询，
     * 它们不该计入「缓存是否完整」的判断，否则会永远显示「未知」。
     * 实现类把占位槽从返回里剔除即可。
     */
    fun realQueryKeys(): Set<String> = getQueryMap().keys
}
