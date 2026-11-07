package com.qosed.qodeep.levelboost

import android.util.Log
import com.qosed.qodeep.Env
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

/**
 * 日志落盘工具：对齐 QEdge LogUtils 的做法，把运行日志写到模块目录 log/ 下，
 * 按日期分文件，后台单线程异步写，调用线程只入队不阻塞。
 *
 * 路径：{Env.moduleDataDir}/log/yyyy-MM-dd.log
 */
object FileLog {

    private const val TAG = "QOdeep.FileLog"

    private val dateFmt = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) as SimpleDateFormat }
    private val timeFmt = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()) as SimpleDateFormat }

    private val queue: BlockingQueue<String> = LinkedBlockingQueue(4096)

    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        Thread({
            val batch = ArrayList<String>()
            while (true) {
                try {
                    val first = queue.take()
                    batch.add(first)
                    queue.drainTo(batch)
                    flush(batch)
                    batch.clear()
                    Thread.sleep(50)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                } catch (_: Throwable) {
                    batch.clear()
                }
            }
        }, "QOdeep-Log").apply { isDaemon = true }.start()
    }

    private fun flush(batch: List<String>) {
        if (batch.isEmpty()) return
        runCatching {
            val dir = File(Env.moduleDataDir, "log")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "${dateFmt.get().format(Date())}.log")
            FileWriter(file, true).use { w ->
                for (line in batch) w.write(line)
            }
        }
    }

    private fun enqueue(level: String, msg: String) {
        ensureStarted()
        queue.offer("${timeFmt.get().format(Date())} $level: $msg\n")
    }

    fun d(msg: String) {
        Log.d(TAG, msg)
        enqueue("D", msg)
    }

    fun i(msg: String) {
        Log.i(TAG, msg)
        enqueue("I", msg)
    }

    fun w(msg: String) {
        Log.w(TAG, msg)
        enqueue("W", msg)
    }

    fun e(msg: String) {
        Log.e(TAG, msg)
        enqueue("E", msg)
    }

    fun e(msg: String, t: Throwable) {
        Log.e(TAG, msg, t)
        enqueue("E", "$msg\n${Log.getStackTraceString(t)}")
    }
}
