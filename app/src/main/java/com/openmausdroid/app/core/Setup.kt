package com.openmausdroid.app.core

import android.content.Context
import com.openmausdroid.app.OpenMausApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

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
            Runtime.append(
                "device: sdk=${android.os.Build.VERSION.SDK_INT} " +
                    "abi=${android.os.Build.SUPPORTED_ABIS.firstOrNull()} " +
                    "fp=${android.os.Build.FINGERPRINT.take(100)}",
            )

            val cacheDir = File(context.filesDir, "cache").apply { mkdirs() }
            val tarball = File(cacheDir, "ubuntu.tar.gz")

            val cands = Proot.candidates()
            if (cands.isEmpty()) throw IOException("no proot binary bundled on this install")
            Runtime.append(
                "proot candidates: " +
                    cands.joinToString { "${it.name}(exec=${it.canExecute()})" },
            )
            Runtime.append("copying bundled environment files")
            // NOTE: the asset is named *.bin (gzipped content) because aapt2
            // gunzips *.gz assets and strips the extension at packaging time.
            copyAsset(context, "bundle/ubuntu-rootfs.bin", tarball)

            Runtime.phase.value = Runtime.Phase.EXTRACTING
            val marker = File(Proot.rootfs, "etc/maus-bootstrap-done")
            // Bump the suffix whenever the extractor changes so devices with a
            // tree stamped by older code wipe and re-extract cleanly.
            val extracted = File(Proot.rootfs, ".rootfs-extracted-v2")
            if (!extracted.exists()) {
                if (Proot.rootfs.exists()) {
                    Runtime.append("clearing incomplete rootfs")
                    Proot.rootfs.deleteRecursively()
                }
                Proot.rootfs.mkdirs()
                Runtime.append("extracting Ubuntu rootfs (this can take a few minutes)")
                try {
                    Archive.extractTarGz(tarball, Proot.rootfs) { p ->
                        Runtime.setupProgress.value = p * 0.7f
                    }
                } catch (e: Exception) {
                    // A corrupt/incomplete tarball would fail identically on every
                    // retry, so delete it - the next run re-copies it from the APK.
                    runCatching { tarball.delete() }
                    throw e
                }
                if (!extracted.createNewFile()) {
                    throw IllegalStateException("could not stamp the extracted rootfs")
                }
                Runtime.append("rootfs extracted")
            }
            if (!marker.exists()) {
                installSetupFiles(Proot.ttydBin)
                val proot = selectProot()
                Runtime.append("using proot: ${proot.absolutePath}")
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
            Runtime.fail("${it::class.java.simpleName}: ${it.message ?: "setup failed"}")
        }
    }

    /**
     * Smoke-tests each bundled proot and returns the first one that runs a
     * guest command. Stage A (--version) exercises proot startup only; stage B
     * (/bin/true in the rootfs) exercises tracing plus guest libc startup.
     */
    private fun selectProot(): File {
        val failures = mutableListOf<String>()
        for (cand in Proot.candidates()) {
            Runtime.append("probing ${cand.name}")
            val (vc, vout) = Proot.probe(listOf(cand.absolutePath, "--version"))
            Runtime.append("--version -> exit $vc ${vout.trim().take(200)}")
            if (vc != 0) {
                failures += "${cand.name}: startup exit $vc"
                continue
            }
            val (tc, tout) = Proot.probe(
                listOf(cand.absolutePath, "-0", "-r", Proot.rootfs.absolutePath, "/bin/true"),
                timeoutMs = 30_000,
            )
            if (tc != 0) {
                Runtime.append("guest /bin/true -> exit $tc ${tout.trim().take(300)}")
                failures += "${cand.name}: guest exit $tc"
                continue
            }
            Proot.useProot(cand)
            return cand
        }
        throw IOException("no working proot on this device (${failures.joinToString("; ")})")
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
