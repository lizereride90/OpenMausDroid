package com.openmausdroid.app.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** App-wide runtime state, shared between the foreground service and the UI. */
object Runtime {

    enum class Phase {
        IDLE,       // service not started yet
        EXTRACTING, // unpacking the Ubuntu rootfs
        BOOTSTRAP,  // running bootstrap.sh inside the rootfs
        STARTING,   // starting harness + terminal
        READY,      // everything up
        FAILED,
        STOPPED,
    }

    const val SETUP_VERSION = 1

    val phase = MutableStateFlow(Phase.IDLE)
    val log = MutableStateFlow<List<String>>(emptyList())
    val serverReady = MutableStateFlow(false)
    val harnessPid = MutableStateFlow<Int?>(null)
    val lastError = MutableStateFlow<String?>(null)
    val sessions = MutableStateFlow<Set<Int>>(emptySet())
    val setupProgress = MutableStateFlow(0f)

    private const val MAX_LOG = 800

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        append("OpenMausDroid ready. Start the environment from Setup.")
    }

    fun append(line: String) {
        val stamped = line.trimEnd('\n')
        if (stamped.isEmpty()) return
        val next = (log.value + stamped).takeLast(MAX_LOG)
        log.value = next
    }

    fun fail(message: String) {
        lastError.value = message
        append("ERROR: $message")
        phase.value = Phase.FAILED
    }

    fun resetForSetup() {
        lastError.value = null
        setupProgress.value = 0f
    }
}
