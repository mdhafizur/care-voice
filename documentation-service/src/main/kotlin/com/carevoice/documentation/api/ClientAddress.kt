package com.carevoice.documentation.api

import com.carevoice.documentation.config.IpNetwork
import com.carevoice.documentation.config.parseIpLiteral
import io.ktor.server.application.ApplicationCall
import java.net.InetAddress

/**
 * The address of the real client, for per-IP limits such as login attempts.
 *
 * Behind Traefik, the TCP connection comes from Traefik, so every user would share one address. Traefik passes the
 * client's address in `X-Forwarded-For`. That header is believed ONLY when the connection itself comes from a trusted
 * proxy ([trustedProxies], `TRUSTED_PROXIES`): otherwise anyone could send a new fake address with every attempt and
 * never be limited.
 *
 * The header is read from right to left (`client, proxy1, proxy2`: each proxy appends the address it received the
 * request from). Addresses of trusted proxies are skipped; the first one that is not trusted is the client. An entry
 * that is not an IP literal stops the walk and the connecting proxy's address is used: a shared limit is the safe
 * fallback, a spoofed one is not. With no trusted proxies (the default) the header is ignored completely.
 */
fun clientAddress(peer: String, forwardedFor: List<String>, trustedProxies: List<IpNetwork>): String {
    val peerAddress = parseIpLiteral(peer) ?: return peer
    if (!peerAddress.isIn(trustedProxies)) return peer
    val hops = forwardedFor.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
    var nearest: InetAddress = peerAddress
    for (hop in hops.asReversed()) {
        val address = parseIpLiteral(withoutPort(hop)) ?: return nearest.hostAddress
        if (!address.isIn(trustedProxies)) return address.hostAddress
        nearest = address
    }
    // Every hop is a trusted proxy (a call from inside the network): the leftmost one is as close as it gets.
    return nearest.hostAddress
}

/** [clientAddress] for a Ktor call: the socket's peer address plus its `X-Forwarded-For` headers. */
fun ApplicationCall.clientAddress(trustedProxies: List<IpNetwork>): String = clientAddress(
    peer = request.local.remoteAddress,
    forwardedFor = request.headers.getAll(X_FORWARDED_FOR).orEmpty(),
    trustedProxies = trustedProxies,
)

private const val X_FORWARDED_FOR = "X-Forwarded-For"

/** True when this address falls inside at least one of [networks]. */
private fun InetAddress.isIn(networks: List<IpNetwork>): Boolean = networks.any { it.contains(this) }

/** `1.2.3.4:5678` → `1.2.3.4`, `[::1]:5678` → `::1`; a bare IPv6 address is left alone. */
private fun withoutPort(hop: String): String = when {
    hop.startsWith("[") -> hop.substringAfter('[').substringBefore(']')
    // Exactly one ':' can only be IPv4 with a port; an IPv6 address always has at least two.
    hop.count { it == ':' } == 1 -> hop.substringBefore(':')
    else -> hop
}
