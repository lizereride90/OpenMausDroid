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
    lateinit var activeProot: File
        private set
    lateinit var ttydBin: File
        private set
    private lateinit var libDir: String
    private val helperCandidates = mutableListOf<File>()
    lateinit var rootfs: File
        private set
    lateinit var logsDir: File
        private set
    lateinit var tmpDir: File
        private set

    private val running = ConcurrentHashMap<String, Process>()

    private const val GUEST_PATH =
        "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    fun init(context: Context) {
        libDir = context.applicationInfo.nativeLibraryDir
        prootBin = resolveHelper(context, "libproot.so", "bundle/proot", "proot")
        ttydBin = resolveHelper(context, "libttyd.so", "bundle/ttyd", "ttyd")
        helperCandidates.clear()
        // Termux-built proot first: it is linked against bionic and built for
        // the app seccomp policy, so it survives on ROMs that kill the
        // generic static build at startup.
        File(libDir, "libprootT.so").takeIf { it.isFile }?.let { helperCandidates += it }
        if (prootBin.isFile && !helperCandidates.contains(prootBin)) helperCandidates += prootBin
        val remembered = Prefs.activeProotPath.takeIf { it.isNotEmpty() }?.let(::File)
        activeProot = remembered?.takeIf { it.isFile && it.canExecute() }
            ?: helperCandidates.firstOrNull { it.canExecute() }
            ?: prootBin
        rootfs = File(context.filesDir, "rootfs")
        logsDir = File(context.filesDir, "logs")
        logsDir.mkdirs()
        // The Termux-built proot defaults to /data/data/com.termux/files/usr/tmp
        // and Android has no /tmp - without a writable dir it aborts with
        // "can't create temporary directory". PROOT_TMP_DIR overrides that.
        tmpDir = File(context.filesDir, "tmp")
        tmpDir.mkdirs()
    }

    /** Ordered proot candidates for the setup smoke ladder. */
    fun candidates(): List<File> = helperCandidates.toList()

    fun useProot(f: File) {
        activeProot = f
        Prefs.activeProotPath = f.absolutePath
    }

    /**
     * Locates a runnable host helper binary. Some ROMs refuse exec() from the
     * app data dir, so the primary source is the app's native lib dir (the
     * installer extracts bundled .so files there with the exec bit set). If
     * that is missing, falls back to copying the asset into files/bin.
     */
    private fun resolveHelper(
        context: Context,
        libName: String,
        assetPath: String,
        binName: String,
    ): File {
        val lib = File(context.applicationInfo.nativeLibraryDir, libName)
        if (lib.isFile) {
            Runtime.append("$binName: ${lib.absolutePath} (executable=${lib.canExecute()})")
            if (lib.canExecute()) return lib
        } else {
            Runtime.append("$binName: no bundled $libName, copying from assets")
        }
        val dest = File(context.filesDir, "bin/$binName").apply { parentFile?.mkdirs() }
        if (!(dest.isFile && dest.length() > 0 && dest.canExecute())) {
            val tmp = File(dest.parentFile, dest.name + ".part")
            context.assets.open(assetPath).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            dest.setExecutable(true)
            runCatching {
                java.lang.Runtime.getRuntime().exec(arrayOf("/system/bin/chmod", "755", dest.absolutePath)).waitFor()
            }
        }
        Runtime.append("$binName: ${dest.absolutePath} (executable=${dest.canExecute()})")
        return dest
    }

    /**
     * Environment for the host-side proot process. The guest never sees this:
     * its environment is reset by the `env -i` in [baseCommand].
     */
    private fun applyHostEnv(pb: ProcessBuilder) {
        pb.environment()["LD_LIBRARY_PATH"] = libDir
        pb.environment()["PROOT_TMP_DIR"] = tmpDir.absolutePath
        pb.environment()["TMPDIR"] = tmpDir.absolutePath
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
            activeProot.absolutePath,
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
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            // Lets a bionic-linked proot (Termux build) find its bundled
            // libs and its temp dir. The guest never sees this: env -i resets
            // its environment.
            applyHostEnv(pb)
            val process = pb.start()
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
            watcher.join(15000)
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
            val pb = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(File(logsDir, "$name.log"))
            applyHostEnv(pb)
            val process = pb.start()
            running[name] = process
            Runtime.append("started $name")
            true
        } catch (e: Exception) {
            Runtime.append("failed to start $name: ${e.message}")
            false
        }
    }

    /**
     * Runs a raw host command (outside the guest) and returns its exit code
     * plus combined output. Used by the setup smoke ladder to find a proot
     * binary that survives on this device. Exit 159 (128+SIGSYS) means a
     * seccomp kill.
     */
    fun probe(cmd: List<String>, timeoutMs: Long = 20_000): Pair<Int, String> {
        return try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            applyHostEnv(pb)
            val process = pb.start()
            val out = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(out) { out.appendLine(line) }
                    }
                } catch (_: Exception) {}
            }
            reader.isDaemon = true
            reader.start()
            if (!waitFor(process, timeoutMs)) {
                process.destroyForcibly()
                reader.join(2000)
                return -1 to "timed out after ${timeoutMs / 1000}s"
            }
            reader.join(5000)
            process.exitValue() to synchronized(out) { out.toString() }
        } catch (e: Exception) {
            -1 to (e.message ?: e::class.java.simpleName)
        }
    }

    /**
     * Smoke-runs a guest command with the exact flag set real sessions use
     * (-l, -0, --kill-on-exit, binds), so the ladder rejects candidates that
     * would fail later in bootstrap.
     */
    fun probeGuest(cand: File, guest: List<String>, timeoutMs: Long = 30_000): Pair<Int, String> {
        val cmd = mutableListOf(
            cand.absolutePath,
            "-l", "-0", "--kill-on-exit",
            "-r", rootfs.absolutePath,
        )
        cmd += binds()
        cmd += listOf("-w", "/") + guest
        return probe(cmd, timeoutMs)
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
