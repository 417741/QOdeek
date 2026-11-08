package com.qosed.qodeep.liquidglass;

import android.app.Activity;
import android.app.Instrumentation;
import android.util.Log;

import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 主页底栏液态玻璃的装配入口，只服务 QQ 主进程。
 *
 * <p>把新式 hook 链适配到本模块使用的传统 Xposed API：其余渲染/布局代码
 * 只需要 getArg / getArgs / getThisObject / proceed 四个能力，
 * 这里用 MethodHookParam + invokeOriginalMethod 等价实现，
 * 并保持「回调抛异常不冒泡到宿主」的保护语义。
 */
public final class LiquidGlassModule {

    static final String TAG = "QOdeep.Glass";

    private static volatile boolean sInstalled;

    private LiquidGlassModule() {
    }

    static HostApp app() {
        return HostApp.QQ;
    }

    static void log(int prio, String msg) {
        Log.println(prio, TAG, msg);
    }

    static void logErr(String msg, Throwable t) {
        Log.e(TAG, msg, t);
    }

    /** 只在开关开启时由启动流程调用；关闭状态下整条 hook 链都不挂载，重启即恢复原生底栏。 */
    public static void install(ClassLoader hostClassLoader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            TabBarBridge.install(HostApp.QQ, hostClassLoader);
        } catch (Throwable t) {
            logErr("底栏桥接挂载失败", t);
        }
        try {
            Method resume = Instrumentation.class.getMethod("callActivityOnResume", Activity.class);
            hookAfter(resume, chain -> {
                Object arg0 = chain.getArg(0);
                if (!(arg0 instanceof Activity)) {
                    return;
                }
                Activity activity = (Activity) arg0;
                if (!HostApp.QQ.launcherActivity.equals(activity.getClass().getName())) {
                    return;
                }
                GlassConfig.load(activity);
                LiquidGlassInstaller.scheduleInstall(activity);
            });
            log(Log.INFO, "主页底栏液态玻璃已挂载");
        } catch (Throwable t) {
            logErr("主页 onResume 挂载失败", t);
        }
    }

    /** 原方法执行完之后跑回调，忽略回调结果。 */
    static void hookAfter(Executable ex, AfterCallback fn) {
        XposedBridge.hookMethod((Member) ex, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    fn.after(new Chain(param, true));
                } catch (Throwable t) {
                    logErr("后置 hook 回调失败", t);
                }
            }
        });
    }

    /** 接管原方法：回调可改写参数、替换结果或直接丢弃调用。 */
    static void hookIntercept(Executable ex, InterceptCallback fn) {
        XposedBridge.hookMethod((Member) ex, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Chain chain = new Chain(param, false);
                Object result;
                try {
                    result = fn.intercept(chain);
                } catch (Throwable t) {
                    logErr("接管 hook 回调失败，回落到原方法", t);
                    result = chain.proceed();
                }
                param.setResult(result);
            }
        });
    }

    interface InterceptCallback {
        Object intercept(Chain chain) throws Throwable;
    }

    interface AfterCallback {
        void after(Chain chain) throws Throwable;
    }

    /** 等价于框架链对象，只暴露渲染代码实际用到的四个方法。 */
    static final class Chain {

        private final XC_MethodHook.MethodHookParam param;
        private final boolean after;
        private boolean proceeded;
        private Object originalResult;

        Chain(XC_MethodHook.MethodHookParam param, boolean after) {
            this.param = param;
            this.after = after;
        }

        public Object getThisObject() {
            return param.thisObject;
        }

        public Object getArg(int index) {
            return param.args[index];
        }

        public List<Object> getArgs() {
            return Arrays.asList(param.args);
        }

        public Object proceed() throws Throwable {
            if (after) {
                return param.getResult();
            }
            if (!proceeded) {
                proceeded = true;
                originalResult = XposedBridge.invokeOriginalMethod(
                        param.method, param.thisObject, param.args);
            }
            return originalResult;
        }
    }
}
