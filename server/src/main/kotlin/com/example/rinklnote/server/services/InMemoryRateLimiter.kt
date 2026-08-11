package com.example.rinklnote.server.services

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Simple in-memory sliding-window rate limiter keyed by an arbitrary string
 * (typically the client IP). A window resets once [windowSeconds] elapses since
 * the window's first recorded failure, so a burst is bounded but a legitimate
 * user behind a shared IP is not permanently locked out.
 *
 * Single-process only — fine for a small self-hosted deployment. If the app is
 * ever run behind multiple server instances, move this to Redis or a DB table.
 */
class InMemoryRateLimiter(
    private val maxAttempts: Int,
    private val windowSeconds: Long,
) {
    // key -> [windowStartEpochSeconds, failureCount]
    private val attempts = ConcurrentHashMap<String, LongArray>()

    fun isBlocked(key: String): Boolean {
        val now = Instant.now().epochSecond
        val arr = attempts[key] ?: return false
        return arr[1] >= maxAttempts && now - arr[0] <= windowSeconds
    }

    fun recordFailure(key: String) {
        val now = Instant.now().epochSecond
        attempts.compute(key) { _, arr ->
            if (arr == null || now - arr[0] > windowSeconds) longArrayOf(now, 1)
            else longArrayOf(arr[0], arr[1] + 1)
        }
    }

    fun recordSuccess(key: String) {
        attempts.remove(key)
    }

    /** Opportunistic cleanup so expired windows do not accumulate forever. */
    fun prune() {
        val now = Instant.now().epochSecond
        attempts.entries.removeIf { now - it.value[0] > windowSeconds }
    }
}
