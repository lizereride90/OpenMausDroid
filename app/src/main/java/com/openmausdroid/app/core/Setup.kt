package com.openmausdroid.app.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * First-run preparation of the Ubuntu rootfs.
 *
 * Copies the bundled assets (static proot, ubuntu-base tarball, ttyd and the
 * setup scripts) out of the APK, extracts the rootfs and then runs
 * bootstrap.sh inside it to install XFCE, VNC, Node and OpenMausBot.
 * Everything is skipped once the rootfs is stamped as done.
 */
object Setup {

    private const val BOOTSTRAP_TIMEOUT_MS = 45 * 60 * 1000L

    val rootfsReady: Boolean
        get() = Prefs.setupVersion >= Runtime.SETUP_VERSION &&
            File(Proot.rootfs, "etc/maus-bootstrap-done").exists()

    suspend fun prepare(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (rootfsReady) {
                Runtime.append("environment already set up")
                Runtime.setupProgress.value = 1f
                return@runCatching
            }
            Runtime.resetForSetup()

            val binDir = File(context.filesDir, "bin").apply { mkdirs() }
            val cacheDir = File(context.filesDir, "cache").apply { mkdirs() }
            val proot = File(binDir, "proot")
            val tarball = File(cacheDir, "ubuntu.tar.gz")
            val ttyd = File(cacheDir, "ttyd")

            Runtime.append("copying bundled environment files")
            copyAsset(context, "bundle/proot", proot)
            copyAsset(context, "bundle/ubuntu.tar.gz", tarball)
            copyAsset(context, "bundle/ttyd", ttyd)
            proot.setExecutable(true)
            ttyd.setExecutable(true)

            Runtime.phase.value = Runtime.Phase.EXTRACTING
            val marker = File(Proot.rootfs, "etc/maus-bootstrap-done")
            val extracted = File(Proot.rootfs, ".rootfs-extracted")
            if (!extracted.exists()) {
                if (Proot.rootfs.exists()) {
                    Runtime.append("clearing incomplete rootfs")
                    Proot.rootfs.deleteRecursively()
                }
                Proot.rootfs.mkdirs()
                Runtime.append("extracting Ubuntu rootfs (this can take a few minutes)")
                Archive.extractTarGz(tarball, Proot.rootfs) { p ->
                    Runtime.setupProgress.value = p * 0.7f
                }
                if (!extracted.createNewFile()) {
                    throw IllegalStateException("could not stamp the extracted rootfs")
                }
                Runtime.append("rootfs extracted")
            }
            if (!marker.exists()) {
                installSetupFiles(ttyd)
                Runtime.phase.value = Runtime.Phase.BOOTSTRAP
                Runtime.append("bootstrapping environment (apt, Node, OpenMausBot, VNC)")
                val (code, out) = Proot.run(
                    "bash /setup/bootstrap.sh",
                    timeoutMs = BOOTSTRAP_TIMEOUT_MS,
                    onLine = { Runtime.append(it) },
                ).getOrThrow()
                if (code != 0) throw IllegalStateException("bootstrap failed (exit $code)")
                Runtime.append("bootstrap finished")
            }
            Prefs.setupVersion = Runtime.SETUP_VERSION
            Runtime.setupProgress.value = 1f
        }.onFailure {
            Runtime.fail(it.message ?: "setup failed")
        }
    }

    /** Places the setup scripts and ttyd inside the rootfs at /setup. */
    private fun installSetupFiles(ttyd: File) {
        val setupDir = File(Proot.rootfs, "setup").apply { mkdirs() }
        val scripts = listOf("bootstrap.sh", "maus-session", "maus-harness", "maus-ttyd")
        for (name in scripts) {
            val dest = File(setupDir, name)
            OpenMausApp.instance.assets.open("setup/$name").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.setExecutable(true, false)
        }
        ttyd.copyTo(File(setupDir, "ttyd"), overwrite = true)
        File(setupDir, "ttyd").setExecutable(true, false)
    }

    private fun copyAsset(context: Context, assetPath: String, dest: File) {
        if (dest.exists() && dest.length() > 0) return
        val tmp = File(dest.parentFile, dest.name + ".part")
        context.assets.open(assetPath).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }
}
