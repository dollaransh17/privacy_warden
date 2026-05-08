package com.privacywarden.app.vpn

import com.privacywarden.app.net.Rule
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory rule store. Persisted to disk on a future iteration via Room+SQLCipher.
 *
 * Keyed by domain (with optional package scoping). The DNS layer queries this on
 * every lookup; misses are resolved normally and hits return 0.0.0.0.
 */
object RuleStore {
    private val blocked = ConcurrentHashMap<String, Rule>()
    private val allowed = ConcurrentHashMap<String, Rule>()

    fun apply(rule: Rule) {
        when (rule.action.uppercase()) {
            "BLOCK" -> blocked[rule.domain.lowercase()] = rule
            "ALLOW" -> allowed[rule.domain.lowercase()] = rule
        }
    }

    fun shouldBlock(domain: String): Boolean {
        val d = domain.lowercase()
        if (allowed.containsKey(d)) return false
        if (blocked.containsKey(d)) return true
        // suffix-match against user-applied rules
        if (blocked.keys.any { d.endsWith(".$it") }) return true
        // ── panic mode: drop every known tracker / analytics / ad domain ──
        if (WardenState.panicMode.get() && PanicBlocklist.matches(d)) {
            WardenState.panicBlockedCount.incrementAndGet()
            return true
        }
        return false
    }

    fun snapshot(): List<Rule> = blocked.values.toList()
}
