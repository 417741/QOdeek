# QOdeep 入口显示与打开原理

## 一、入口怎么出现在 QQ 设置页

1. QQ 设置页的分组列表由 `com.tencent.mobileqq.setting.main` 包下一个继承 `SettingConfigProvider` 的类的 `f(Context)` 方法生成，返回 `List`（分组列表）。
2. 模块 Xposed hook 该方法，在返回值里插入一个自建分组。
3. 分组 = 分组类（常量池含字符串 `itemProcessors`）+ 条目类（含 `SimpleItemProcessor`）。条目构造时传入标题 `QOdeep`、类型、点击回调。
4. 插入位置：账号与安全分组上方。
5. 类名随 QQ 版本混淆变化 → 首次用 DexKit 扫 dex 按特征找类并缓存；找不到回退硬编码类名。
6. QQ 走 QFix 热补丁 → hook `QFixApplicationImplProxy.attachBaseContext` 抢真实 ClassLoader 后再挂上面的 hook。

## 二、点入口为什么不转跳到应用

1. 点击回调用 QQ 进程内的 context 构造 Intent，组件包名保持为 QQ，骗过 QQ 客户端「离开 QQ」检测。
2. 系统 `IActivityTaskManager` 层把 Intent 换成 QQ 自己的壳 Activity，真正的 Intent 塞进 extra。
3. ActivityThread 消息队列里还原 Intent；`Instrumentation.newActivity` 把壳 Activity 实例换成模块的 `SettingsActivity`。
4. 结果：模块页面在 QQ 进程内打开，不弹「即将离开 QQ」。
