package com.demo.scandemo

import android.content.Context
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 极简崩溃捕获：把上次未捕获的异常堆栈写到 SharedPreferences，App 下次启动时 Activity 可以读出来 Toast/弹窗。
 * 给"日志看不了"的场景加一条抓手，日志能看后可以整个删掉。
 *
 * 用法：Application 或 MainActivity.onCreate 里第一件事调 install(context)。
 * 之后读上次崩溃：consumeLastCrash(context) 返回 String? 并同时清空。
 */
object CrashCatcher {
    private const val PREF = "crash_catcher"
    private const val KEY = "last_crash"
    private const val TAG = "CrashCatcher"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val text = "thread=${t.name}\n${sw}"
                appContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY, text)
                    .commit() // 同步写盘：进程马上就要死了，异步 apply 可能来不及落地
                Log.e(TAG, "已捕获未处理异常并写盘", e)
            } catch (t2: Throwable) {
                Log.e(TAG, "写崩溃日志本身也炸了", t2)
            } finally {
                previous?.uncaughtException(t, e)
            }
        }
    }

    /** 读上次崩溃并清空；没有返回 null */
    fun consumeLastCrash(context: Context): String? {
        val sp = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val text = sp.getString(KEY, null) ?: return null
        sp.edit().remove(KEY).apply()
        return text
    }
}
