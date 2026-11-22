package com.jollydoddger.photoframe

import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * "Is anyone home?" — pings each phone's fixed IP on the home Wi-Fi every 30 s.
 * Phones nap their Wi-Fi, so we only call the house empty after none has answered
 * for the grace period (default 20 min).
 */
class PresenceMonitor {
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private val startedAt = System.currentTimeMillis()

    fun start() {
        exec.scheduleWithFixedDelay({ runCatching { scan() } }, 2, 30, TimeUnit.SECONDS)
    }

    fun stop() { exec.shutdownNow() }

    private fun scan() {
        val s = SettingsStore.current
        if (!s.presenceEnabled || s.phones.isEmpty()) return
        val threads = s.phones.map { p ->
            Thread {
                if (reachable(p.ip)) lastSeen[p.ip] = System.currentTimeMillis()
            }.also { it.start() }
        }
        threads.forEach { it.join(6000) }
        FrameState.presence = HashMap(lastSeen)
    }

    fun anyoneHome(): Boolean {
        val s = SettingsStore.current
        if (!s.presenceEnabled || s.phones.isEmpty()) return true
        val now = System.currentTimeMillis()
        val grace = s.awayGraceMin * 60_000L
        if (now - startedAt < 3 * 60_000L) return true            // give it a few scans after boot
        if (now - FrameState.lastUserActivity < grace) return true // someone used the remote or the web page
        return s.phones.any { p -> lastSeen[p.ip]?.let { now - it < grace } == true }
    }

    private fun reachable(ip: String): Boolean {
        // 1. ICMP ping via the system binary
        try {
            // Two tries, 2 s each: a locked Android phone's Wi-Fi is in power-save and only
            // picks up buffered packets at the router's beacon interval, so replies arrive late.
            val p = ProcessBuilder("/system/bin/ping", "-c", "2", "-i", "1", "-W", "2", ip).redirectErrorStream(true).start()
            val done = p.waitFor(5, TimeUnit.SECONDS)
            if (done && p.exitValue() == 0) return true
            if (!done) p.destroy()
        } catch (_: Exception) {
        }
        // 2. TCP knock: any answer, even "connection refused", proves the phone is awake on the network.
        //    Android phones have no firewall and answer a closed port with a reset straight away.
        for (port in intArrayOf(80, 443)) {
            try {
                Socket().use { it.connect(InetSocketAddress(ip, port), 1500) }
                return true
            } catch (e: ConnectException) {
                if (e.message?.contains("ECONNREFUSED") == true || e.message?.contains("refused", true) == true) return true
            } catch (_: Exception) {
            }
        }
        return false
    }
}
