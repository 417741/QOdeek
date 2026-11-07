# 修复：QQ 内置浏览器顶部乱码（渲染异常）

## 1. 现象

打开 QQ 内置浏览器（`QQBrowserActivity`）后，窗口顶部状态栏/标题栏区域出现一块巨大的黑色畸形乱码，形状固定、位置固定在顶部约 0~256px。

关键特征：

- 只在 QQ 内置浏览器页面出现，QQ 主界面/聊天页/设置页均正常。
- 与网页内容无关（百度、任意网址都会出现）。
- 用 `adb screencap` 截图能看到乱码 → 说明它**不是系统层的挖孔遮罩**（系统遮罩由 SurfaceFlinger 绘制，不会出现在截屏里），而是 **QQ 浏览器窗口自己渲染的内容**。
- `uiautomator dump` 的 View 树里找不到对应控件 → 说明它不是 View 画的，而是画在**窗口 Surface / 渲染缓冲层**（沉浸式背景、毛玻璃 backdrop 之类，非 View 元素）。

## 2. 根因

QQ 9.3.75 内置浏览器标题栏有一层自绘的沉浸式/挖孔适配背景（非 View，直接画在渲染缓冲层）。这层背景在渲染时会从 QQ 进程全局 `Resources` 解析资源。

而模块在 `Application.onCreate` 刚结束时（QQ 首个 Activity 渲染之前）就调用了 `Parasitics.initForStubActivity()`，其中有一句：

```kotlin
runCatching { injectModuleResources(ctx.resources) }
```

它通过 `Resources.addLoaders(...)`（Android 10+）或 `AssetManager.addAssetPath(...)`（Android 10 以下）向 QQ 的**全局 Resources** 注入模块自己的资源包。

这个注入时机恰好赶在浏览器标题栏背景渲染之前，扰动了资源解析的时序，把 QQ 9.3.75 浏览器自身的渲染竞态踩了出来 → 背景层采样到脏数据 → 画出乱码。

### 佐证（A/B 实测）

| 版本 | 模块入口 | 乱码 |
| --- | --- | --- |
| 原版（含早期全局资源注入） | 立即 | 出现 |
| 最小注入测试版（砍掉全部初始化） | 无 | 消失 |
| 8 秒延后版（只延后 DexKit，保留资源注入） | 延迟 8 秒 | 仍偶发 |
| 去掉早期全局资源注入 | 立即 | 消失 |

第 3 行是关键：只延后 DexKit 但保留资源注入，乱码仍在；说明元凶不是 DexKit/Hook 数量，而是**早期全局资源注入**。

## 3. 修复内容

核心改动只有一处：删除 `Parasitics.initForStubActivity()` 里的早期全局资源注入。

文件：`qedge/src/main/java/me/lengyu/qedge/lifecycle/Parasitics.kt`

### 改动前

```kotlin
@JvmStatic
fun initForStubActivity(ctx: Context) {
    runCatching {
        // ... 设置 ProxyInstrumentation、hookIActivityManager、hookIPackageManager ...

        // 进程早期（Application.onCreate 后）同步注入模块资源，确保首个 Activity
        // 渲染前宿主 resources 已能解析 0x69 包的模块资源，避免入口图标/设置页
        // 因 addLoaders 晚于渲染而抛 ResourcesNotFoundException。
        runCatching { injectModuleResources(ctx.resources) }

        initialized = true
    }
}
```

### 改动后

```kotlin
@JvmStatic
fun initForStubActivity(ctx: Context) {
    runCatching {
        // ... 设置 ProxyInstrumentation、hookIActivityManager、hookIPackageManager ...

        // 不在进程早期向 QQ 全局 Resources 注入模块资源：addLoaders 会扰动启动期
        // 渲染时序，实测触发 QQ 9.3.75 内置浏览器标题栏的渲染竞态（顶部乱码）。
        // 资源注入改由 ProxyInstrumentation.callActivityOnCreate 按需执行，
        // 各功能注入点（QQSettingInject/ChatSettingLoader 等）也均有按需调用兜底。
        initialized = true
    }
}
```

## 4. 为什么删掉它不影响功能

`injectModuleResources` 在工程里还有其他多个**按需调用点**，会在打开对应页面时注入资源，兜底功能完整：

- `ProxyInstrumentation.callActivityOnCreate()`（两个重载）——每个 Activity 创建时注入，这是最晚、最安全的兜底。
- `QQSettingInject.kt`（第 567、594 行）——QQ 设置入口注入。
- `QQPlusInject.java`（第 74 行）——QQ 侧边栏入口注入。
- `ChatSettingLoader.kt` / `MediaPanelLoader.kt` / `RepeatMsg.kt` / `QQDeepLink.kt` 等——各功能按需注入。

因此早期全局注入是**冗余且有害**的，删除后模块入口和所有功能照常工作。

## 5. 验证方法

1. 构建：`gradlew.bat assembleRelease`，产物 `qe.apk`。
2. 安装并重启 QQ：
   ```
   adb install -r QO.apk
   adb shell am force-stop com.tencent.mobileqq
   adb shell monkey -p com.tencent.mobileqq -c android.intent.category.LAUNCHER 1
   ```
3. 打开内置浏览器任意网址：
   ```
   adb shell am start -n com.tencent.mobileqq/.activity.QQBrowserActivity -a android.intent.action.VIEW -d "https://www.baidu.com"
   ```
4. 截屏确认顶部无乱码，同时确认 QQ 设置页「QO」模块入口正常显示。

## 6. 备注

- 若后续换到真正的源码 / 新版本 QQ，只需记住一条：**不要在进程早期向宿主全局 `Resources` 注入模块资源**，改用按 Activity 创建的 `callActivityOnCreate` 时机注入即可。
- 若升级到其他 QQ 版本仍复现乱码，说明那版浏览器标题栏背景的渲染对资源注入更敏感，可进一步把 `injectModuleResources` 的调用时机全部收口到 `ProxyInstrumentation.callActivityOnCreate`（已经是最晚时机），或干脆只在模块自己的 `SettingActivity` 里注入。
