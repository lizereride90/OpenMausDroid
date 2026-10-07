package com.openmausdroid.app.core

/**
 * Owns the XFCE/VNC desktop sessions that live inside the rootfs.
 * Session n exposes VNC on 5900+n and noVNC on 6080+n (loopback).
 */
object Sessions {

    const val MAX = 8

    fun vncUrl(n: Int): String = "http://127.0.0.1:${6080 + n}/vnc.html" +
        "?autoconnect=true&resize=scale&password=openmaus&path=websockify"

    fun isRunning(n: Int): Boolean = Proot.isAlive("session-$n")

    fun start(n: Int): Boolean {
        if (n < 1 || n > MAX) return false
        val ok = Proot.launch("session-$n", "/usr/local/bin/maus-session start $n")
        if (ok) Runtime.sessions.value = Runtime.sessions.value + n
        return ok
    }

    fun stop(n: Int) {
        // The app owns the supervisor process, so killing proot tears down
        // Xvnc, XFCE and websockify together via --kill-on-exit.
        Proot.stop("session-$n")
        Runtime.sessions.value = Runtime.sessions.value - n
    }

    fun stopAll() {
        Runtime.sessions.value.forEach { stop(it) }
    }

    fun refresh() {
        val alive = Runtime.sessions.value.filter { isRunning(it) }.toSet()
        Runtime.sessions.value = alive
    }
}
