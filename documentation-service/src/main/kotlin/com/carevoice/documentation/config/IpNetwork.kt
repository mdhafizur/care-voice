package com.carevoice.documentation.config

import java.net.Inet4Address
import java.net.InetAddress

/**
 * An IP network in CIDR notation, e.g. `10.42.0.0/16` or `fd00::/8` (a plain address means a single host).
 * Used for `TRUSTED_PROXIES`: the proxies whose `X-Forwarded-For` header the service believes.
 */
class IpNetwork private constructor(private val network: ByteArray, private val prefixLength: Int, val text: String) {
    fun contains(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size != network.size) return false
        val fullBytes = prefixLength / 8
        for (i in 0 until fullBytes) if (bytes[i] != network[i]) return false
        val remainingBits = prefixLength % 8
        if (remainingBits == 0) return true
        val mask = (0xFF shl (8 - remainingBits)) and 0xFF
        return (bytes[fullBytes].toInt() and mask) == (network[fullBytes].toInt() and mask)
    }

    override fun toString(): String = text

    companion object {
        /** Parses `address/prefix` or a plain address; null when it is not a valid IP literal and prefix. */
        fun parse(text: String): IpNetwork? {
            val trimmed = text.trim()
            val address = parseIpLiteral(trimmed.substringBefore('/')) ?: return null
            val maxPrefix = address.address.size * 8
            val prefix =
                if ('/' in trimmed) trimmed.substringAfter('/').toIntOrNull() ?: return null else maxPrefix
            if (prefix !in 0..maxPrefix) return null
            return IpNetwork(address.address, prefix, trimmed)
        }
    }
}

private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
private val IPV6 = Regex("""^[0-9A-Fa-f:.]+$""")

/**
 * Parses an IP address literal WITHOUT any DNS lookup, or returns null. Important for `X-Forwarded-For`: the header
 * comes from the network, and `InetAddress.getByName("attacker.example")` would resolve a hostname. IPv4 is parsed
 * by hand; IPv6 only after checking it contains a colon and nothing but hex digits, colons and dots (Java treats such
 * a string as a literal and never resolves it). Zone ids (`fe80::1%eth0`) are not accepted.
 */
fun parseIpLiteral(text: String): InetAddress? {
    val value = text.trim().removePrefix("[").removeSuffix("]")
    IPV4.matchEntire(value)?.let { match ->
        val parts = match.groupValues.drop(1).map { it.toInt() }
        if (parts.any { it > 255 }) return null
        return Inet4Address.getByAddress(parts.map { it.toByte() }.toByteArray())
    }
    if (':' !in value || !IPV6.matches(value)) return null
    return runCatching { InetAddress.getByName(value) }.getOrNull()
}
