package com.openmausdroid.app.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

/**
 * Executes commands inside the Ubuntu rootfs through the bundled static proot.
 *
 * The guest shares the host network namespace, so loopback ports opened inside
 * the rootfs (8799 harness, 7681 terminal, 5900+N/6080+N sessions) are directly
 * reachable from the app at 127.0.0.1.
 */
object Proot {

    lateinit var prootBin: File
        private set
    lateinit var rootfs: File
        private set
    lateinit var logsDir: File
        private set

    private val running = ConcurrentHashMap<String, Process>()

    private const val GUEST_PATH =
        "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    fun init(context: Context) {
        prootBin = File(context.filesDir, "bin/proot")
        rootfs = File(context.filesDir, "rootfs")
        logsDir = File(context.filesDir, "logs")
        logsDir.mkdirs()
    }

    private fun binds(): List<String> {
        val args = mutableListOf<String>()
        fun bind(host: String) {
            val f = File(host)
            if (f.exists()) args += listOf("-b", host)
        }
        bind("/dev")
        bind("/proc")
        bind("/sys")
        // The user's shared storage, visible inside the environment at /sdcard.
        bind("/sdcard")
        bind("/storage")
        return args
    }

    private fun baseCommand(extraEnv: Map<String, String> = emptyMap()): MutableList<String> {
        val cmd = mutableListOf(
            prootBin.absolutePath,
            "-l",              // link2symlink: Android FS may refuse hardlinks
            "-0",              // fake root inside the guest
            "--kill-on-exit",  // kill guest children when proot dies
            "-r", rootfs.absolutePath,
        )
        cmd += binds()
        cmd += listOf("-w", "/root", "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "LOGNAME=root",
            "PATH=$GUEST_PATH", "LANG=C.UTF-8", "TERM=xterm-256color",
            "SHELL=/bin/bash", "OMB_PORT=8799",
            "ELECTRON_SKIP_BINARY_DOWNLOAD=1")
        for ((k, v) in extraEnv) cmd += "$k=$v"
        return cmd
    }

    /** Runs a bash snippet inside the guest and returns its combined output. */
    suspend fun run(
        script: String,
        extraEnv: Map<String, String> = emptyMap(),
        timeoutMs: Long = 10 * 60 * 1000,
        onLine: ((String) -> Unit)? = null,
    ): Result<Pair<Int, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val cmd = baseCommand(extraEnv) + listOf("/bin/bash", "-lc", script)
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            val out = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val watcher = Thread {
                try {
                    reader.forEachLine { line ->
                        synchronized(out) { out.appendLine(line) }
                        runCatching { onLine?.invoke(line) }
                    }
                } catch (_: Exception) {}
            }
            watcher.isDaemon = true
            watcher.start()
            val finished = waitFor(process, timeoutMs)
            if (!finished) {
                process.destroyForcibly()
                watcher.join(2000)
                throw RuntimeException("command timed out after ${timeoutMs / 1000}s")
            }
            watcher.join(3000)
            val text = synchronized(out) { out.toString() }
            Result.success(process.exitValue() to text)
        }.fold(
            onSuccess = { it },
            onFailure = { Result.failure(it) },
        )
    }

    /**
     * Launches a long-lived guest process (harness, ttyd, a desktop session).
     * The process stays registered until [stop] is called or the app dies,
     * which keeps proot's path translation alive for its whole tree.
     */
    fun launch(name: String, script: String, extraEnv: Map<String, String> = emptyMap()): Boolean {
        running[name]?.let { if (it.isAlive) return true }
        return try {
            val cmd = baseCommand(extraEnv) + listOf("/bin/bash", "-lc", script)
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(File(logsDir, "$name.log"))
                .start()
            running[name] = process
            Runtime.append("started $name")
            true
        } catch (e: Exception) {
            Runtime.append("failed to start $name: ${e.message}")
            false
        }
    }

    fun isAlive(name: String): Boolean = running[name]?.isAlive == true

    fun stop(name: String) {
        val process = running.remove(name) ?: return
        process.destroy()
        Thread {
            try {
                if (!waitFor(process, 4000)) process.destroyForcibly()
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
    }

    fun stopAll() {
        val names = running.keys.toList()
        names.forEach { stop(it) }
    }

    fun logOf(name: String): String = runCatching {
        File(logsDir, "$name.log").takeIf { it.exists() }?.readText().orEmpty()
    }.getOrDefault("")

    private fun waitFor(process: Process, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) return true
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return !process.isAlive
    }
}
