package com.openmausdroid.app.core

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

/**
 * Extracts the bundled ubuntu-base tarball into the rootfs directory with a
 * pure-Kotlin extractor covering what ubuntu-base ships: regular files,
 * directories, symlinks, hardlinks, GNU long names and pax headers.
 *
 * This deliberately avoids the platform tar (toybox): it fails on hardlinks
 * without root and refuses to create absolute symlinks, both of which the
 * rootfs needs. Entries escaping the destination dir are skipped.
 */
object Archive {

    fun extractTarGz(archive: File, destDir: File, onProgress: (Float) -> Unit) {
        destDir.mkdirs()
        val base = runCatching { destDir.canonicalPath }.getOrDefault(destDir.absolutePath)
        extract(archive, destDir, base, onProgress)
    }

    private class PendingLink(val link: File, val target: File)

    private class Header(
        val name: ByteArray,
        val linkName: ByteArray,
        val mode: Long,
        val size: Long,
        val type: Int,
    )

    private class CountingStream(inner: InputStream) : FilterInputStream(inner) {
        var count = 0L
        override fun read(): Int {
            val b = super.read()
            if (b >= 0) count++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) count += n
            return n
        }
    }

    private const val TYPE_REG = 0
    private const val TYPE_LINK = 49       // '1' hardlink
    private const val TYPE_SYMLINK = 50    // '2'
    private const val TYPE_DIR = 53        // '5'
    private const val TYPE_LONG_NAME = 76  // 'L'
    private const val TYPE_LONG_LINK = 75  // 'K'
    private const val TYPE_PAX = 120       // 'x'
    private const val TYPE_PAX_GLOBAL = 103 // 'g'

    private fun extract(archive: File, destDir: File, base: String, onProgress: (Float) -> Unit) {
        val total = archive.length().coerceAtLeast(1)
        val pending = mutableListOf<PendingLink>()
        BufferedInputStream(FileInputStream(archive), 1 shl 16).use { fileIn ->
            val raw = CountingStream(fileIn)
            val gzip = GZIPInputStream(raw, 1 shl 16)
            var longName: String? = null
            var longLink: String? = null
            var paxName: String? = null
            var paxLink: String? = null
            while (true) {
                val header = readHeader(gzip) ?: break
                onProgress((raw.count.toFloat() / total).coerceIn(0f, 1f))
                when (header.type) {
                    TYPE_LONG_NAME -> { longName = bodyAndPad(gzip, header.size); continue }
                    TYPE_LONG_LINK -> { longLink = bodyAndPad(gzip, header.size); continue }
                    TYPE_PAX, 88 -> { // 'x', 'X'
                        val attrs = pax(bodyAndPad(gzip, header.size))
                        paxName = attrs["path"]
                        paxLink = attrs["linkpath"]
                        continue
                    }
                    TYPE_PAX_GLOBAL -> { skipBodyAndPad(gzip, header.size); continue }
                }
                val name = paxName ?: longName ?: header.name.decode()
                val link = paxLink ?: longLink ?: header.linkName.decode()
                paxName = null
                paxLink = null
                longName = null
                longLink = null
                val out = File(destDir, name.removePrefix("/"))
                if (!isInside(base, out)) {
                    skipBodyAndPad(gzip, header.size)
                    continue
                }
                when (header.type) {
                    TYPE_DIR -> { out.mkdirs(); skipBodyAndPad(gzip, header.size) }
                    TYPE_REG -> {
                        out.parentFile?.mkdirs()
                        removeForReplace(out)
                        out.outputStream().use { copyBody(gzip, it, header.size) }
                        skipPad(gzip, header.size)
                        applyMode(out, header.mode)
                    }
                    TYPE_LINK -> {
                        out.parentFile?.mkdirs()
                        pending += PendingLink(out, File(destDir, link.removePrefix("/")))
                        skipBodyAndPad(gzip, header.size)
                    }
                    TYPE_SYMLINK -> {
                        out.parentFile?.mkdirs()
                        removeForReplace(out)
                        runCatching { Files.createSymbolicLink(out.toPath(), Paths.get(link)) }
                        skipBodyAndPad(gzip, header.size)
                    }
                    else -> skipBodyAndPad(gzip, header.size)
                }
            }
        }
        for (p in pending) {
            if (p.target.exists()) {
                runCatching { Files.createLink(p.link.toPath(), p.target.toPath()) }
                    .onFailure { runCatching { p.target.copyTo(p.link, overwrite = true) } }
            }
        }
        onProgress(1f)
    }

    private fun ByteArray.decode(): String =
        toString(Charsets.UTF_8).trim { it <= ' ' }

    private fun readHeader(input: InputStream): Header? {
        val block = ByteArray(512)
        var read = 0
        while (read < 512) {
            val n = input.read(block, read, 512 - read)
            if (n < 0) return if (read == 0) null else throw IOException("truncated tar")
            read += n
        }
        if (block.all { it == 0.toByte() }) return null
        val prefix = block.copyOfRange(345, 500).decode()
        val base = block.copyOfRange(0, 100).decode()
        val name = if (prefix.isNotEmpty()) "$prefix/$base" else base
        val typeByte = block[156].toInt()
        return Header(
            name = name.toByteArray(),
            linkName = block.copyOfRange(157, 257),
            mode = octal(block, 100, 8),
            size = octal(block, 124, 12),
            type = if (typeByte == 0) TYPE_REG else typeByte,
        )
    }

    private fun octal(block: ByteArray, offset: Int, length: Int): Long {
        val s = block.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)
        val cleaned = s.trim { it == ' ' || it.code == 0 }
        return if (cleaned.isEmpty()) 0 else cleaned.toLongOrNull(8) ?: 0
    }

    private fun bodyAndPad(input: InputStream, size: Long): String {
        val text = readExactly(input, size).toString(Charsets.UTF_8)
        skipPad(input, size)
        return text.trim { it.code == 0 || it == '\n' }
    }

    private fun pax(text: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var i = 0
        while (i < text.length) {
            val sp = text.indexOf(' ', i)
            if (sp < 0) break
            val len = text.substring(i, sp).toIntOrNull() ?: break
            if (len <= 0 || i + len > text.length) break
            val rec = text.substring(sp + 1, i + len)
            val eq = rec.indexOf('=')
            if (eq > 0) out[rec.substring(0, eq).trim()] = rec.substring(eq + 1).trimEnd('\n')
            i += len
        }
        return out
    }

    private fun skipBodyAndPad(input: InputStream, size: Long) {
        var remaining = size
        while (remaining > 0) {
            val n = input.skip(remaining)
            if (n <= 0) {
                if (input.read() < 0) return
                remaining--
            } else remaining -= n
        }
        skipPad(input, size)
    }

    private fun skipPad(input: InputStream, size: Long) {
        val rem = size % 512
        if (rem == 0L) return
        var pad = 512 - rem
        while (pad > 0) {
            val n = input.skip(pad)
            if (n <= 0) {
                if (input.read() < 0) return
                pad--
            } else pad -= n
        }
    }

    private fun readExactly(input: InputStream, count: Long): ByteArray {
        val buf = ByteArray(count.toInt())
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IOException("truncated tar body")
            off += n
        }
        return buf
    }

    private fun copyBody(input: InputStream, out: OutputStream, size: Long) {
        val buf = ByteArray(1 shl 16)
        var remaining = size
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) throw IOException("truncated file body")
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun isInside(base: String, f: File): Boolean =
        runCatching {
            val c = f.canonicalPath
            c == base || c.startsWith(base + File.separatorChar)
        }.getOrDefault(false)

    /** Clears whatever a previous (partial) extraction left at this path. */
    private fun removeForReplace(out: File) {
        runCatching {
            if (out.exists() || Files.isSymbolicLink(out.toPath())) {
                if (!out.delete()) out.deleteRecursively()
            }
        }
    }

    private fun applyMode(file: File, mode: Long) {
        runCatching {
            file.setExecutable((mode and 0b101001001L) != 0L, false)
            file.setReadable((mode and 0b100100100L) != 0L, false)
            file.setWritable((mode and 0b010010010L) != 0L, false)
        }
    }
}
