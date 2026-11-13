package com.carevoice.documentation.config

import java.net.Inet4Address
import java.net.InetAddress

/**
 * An IP network in CIDR notation, e.g. `10.42.0.0/16` or `fd00::/8` (a plain address means a single host).
 * Used for `TRUSTED_PROXIES`: the proxies whose `X-Forwarded-For` header the service believes.
 */
class IpNetwork private constructor(private val network: ByteArray, private val prefixLength: Int, val text: String) {
    /** True when the first [prefixLength] bits of [address] equal the network's. IPv4 never matches IPv6. */
    fun contains(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size != network.size) return false
        val fullBytes = prefixLength / 8
        for (i in 0 until fullBytes) if (bytes[i] != network[i]) return false
        val remainingBits = prefixLength % 8
        if (remainingBits == 0) return true
        // A mask for the top remainingBits bits of the next byte, e.g. 3 bits -> 1110_0000 (0xE0).
        // `and 0xFF` cuts the shifted Int back to one byte.
        val mask = (0xFF shl (8 - remainingBits)) and 0xFF
        return (bytes[fullBytes].toInt() and mask) == (network[fullBytes].toInt() and mask)
    }

    /** The text this network was parsed from, as configured. */
    override fun toString(): String = text

    companion object {
        /** Parses `address/prefix` or a plain address; null when it is not a valid IP literal and prefix. */
        fun parse(text: String): IpNetwork? {
            val trimmed = text.trim()
            val address = parseIpLiteral(trimmed.substringBefore('/')) ?: return null
            // 4 bytes * 8 = 32 bits for IPv4, 16 bytes * 8 = 128 bits for IPv6.
            val maxPrefix = address.address.size * 8
            val prefix =
                if ('/' in trimmed) trimmed.substringAfter('/').toIntOrNull() ?: return null else maxPrefix
            if (prefix !in 0..maxPrefix) return null
            return IpNetwork(address.address, prefix, trimmed)
        }
    }
}

/** Four dot-separated parts of 1 to 3 digits, e.g. `10.42.0.1`; [parseIpLiteral] checks each part is 0 to 255. */
private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

/** Only hex digits, colons and dots (dots for an embedded IPv4 part, e.g. `::ffff:10.0.0.1`). */
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
        // Each IPv4 part is one byte: 0 to 255.
        if (parts.any { it > 255 }) return null
        return Inet4Address.getByAddress(parts.map { it.toByte() }.toByteArray())
    }
    if (':' !in value || !IPV6.matches(value)) return null
    return runCatching { InetAddress.getByName(value) }.getOrNull()
}
