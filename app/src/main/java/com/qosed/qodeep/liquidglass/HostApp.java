package com.qosed.qodeep.liquidglass;

/**
 * 目标宿主主页底栏的定位信息。
 *
 * <p>安装器和玻璃渲染只依赖 View 几何，本身不关心具体应用；唯一不可移植的
 * 是「怎么找到底栏」：宿主把资源 id 混淆过，按名字查 id 无效，能稳定依赖的
 * 只有 UI 类名。这里只保留 QQ 一套。
 */
final class HostApp {

    /**
     * QQ 9.2.85 (13860) 起，复核到 9.3.70。
     *
     * <p>同一个包里存在两种底栏，由服务端开关选择：{@code QQTabWidget}
     * （{@code android.widget.TabWidget} 的子类）是当前默认，
     * {@code QQTabLayout}（material 风格）在开关打开时启用。两者都是同一个
     * 全屏内容容器里的 bottom-gravity 子 View，切换页时都会走
     * {@code setCurrentTab(int)}。
     *
     * <p>{@code QQBlurViewWrapper} 是宿主自带的 54dp 磨砂条，位于底栏背后；
     * 不隐藏它会挡在玻璃和它要折射的页面之间，安装时一并隐藏。
     */
    static final HostApp QQ = new HostApp(
            "com.tencent.mobileqq",
            "com.tencent.mobileqq.activity.SplashActivity",
            new String[]{
                    "com.tencent.mobileqq.widget.QQTabWidget",
                    "com.tencent.mobileqq.widget.QQTabLayout",
            },
            new String[]{"setCurrentTab"},
            "getCurrentTab",
            new String[]{"com.tencent.qui.quiblurview.QQBlurViewWrapper"},
            new String[]{"TabDragAnimationView"},
            true,
            "com.tencent.mobileqq.");

    /** 包名，同时也是主页所在主进程的进程名。 */
    final String pkg;
    /** 承载底栏的 Activity，只有它值得响应。 */
    final String launcherActivity;
    /** 底栏 View 类名，最可能命中的放前面。 */
    final String[] tabViewClasses;
    /** 每次切页都会在底栏上调用的方法，作为安装触发点。 */
    final String[] tabSwitchMethods;
    /** 底栏上返回当前选中序号的无参方法，没有时改从 tab 的选中状态读。 */
    final String currentIndexMethod;
    /** 需要隐藏的同级 View，避免挡住玻璃与页面。 */
    final String[] hiddenSiblings;
    /** tab 图标 View 的类名后缀，图标会跟着文字一起染色。 */
    final String[] iconClassSuffixes;
    /** 夜间判定优先看 tab 文字颜色，而不是系统 uiMode。 */
    final boolean preferTextColorProbe;
    /** 找不到底栏时打印 View 树所用的包名前缀。 */
    final String uiPrefix;

    private HostApp(String pkg, String launcherActivity, String[] tabViewClasses,
                    String[] tabSwitchMethods, String currentIndexMethod,
                    String[] hiddenSiblings, String[] iconClassSuffixes,
                    boolean preferTextColorProbe, String uiPrefix) {
        this.pkg = pkg;
        this.launcherActivity = launcherActivity;
        this.tabViewClasses = tabViewClasses;
        this.tabSwitchMethods = tabSwitchMethods;
        this.currentIndexMethod = currentIndexMethod;
        this.hiddenSiblings = hiddenSiblings;
        this.iconClassSuffixes = iconClassSuffixes;
        this.preferTextColorProbe = preferTextColorProbe;
        this.uiPrefix = uiPrefix;
    }

    boolean isTabViewClass(String className) {
        for (String c : tabViewClasses) {
            if (c.equals(className)) {
                return true;
            }
        }
        return false;
    }

    boolean isHiddenSibling(String className) {
        for (String c : hiddenSiblings) {
            if (c.equals(className)) {
                return true;
            }
        }
        return false;
    }

    boolean isTabIconClass(String className) {
        for (String suffix : iconClassSuffixes) {
            if (className.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return pkg;
    }
}
