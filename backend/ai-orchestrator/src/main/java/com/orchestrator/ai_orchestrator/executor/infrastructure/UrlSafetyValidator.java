package com.orchestrator.ai_orchestrator.executor.infrastructure;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;

/**
 * Decides whether the agent may fetch a URL. Blocks "server-side request forgery" (SSRF):
 * tricking OUR server into calling addresses that only our server can reach.
 *
 * <p>Why it matters here: the URL comes from a language model, which reads untrusted web pages.
 * A page can say "fetch http://169.254.169.254/latest/meta-data/" and the model may obey. That
 * address is the cloud metadata service; it hands out the machine's credentials. The same
 * trick reaches localhost services (this app's own database port, actuator) and company
 * networks.
 *
 * <p>Rules, all of which must hold:
 * <ol>
 *   <li>scheme is http or https, and there is no "user:password@" part</li>
 *   <li>port is the default, 80 or 443</li>
 *   <li>EVERY address the host name resolves to is public (not loopback, private,
 *       link-local, carrier-grade NAT, multicast, unspecified or IPv6 unique-local)</li>
 * </ol>
 *
 * <p>Known limit: the address is checked here and resolved again by the HTTP client a moment
 * later. A malicious DNS server could answer differently the second time ("DNS rebinding").
 * Closing that gap fully needs a network-level egress policy; see the README (Phase 6).
 */
@Component
public class UrlSafetyValidator {

    private static final Set<Integer> ALLOWED_PORTS = Set.of(80, 443);

    /**
     * @return the parsed URI if it is safe
     * @throws IllegalArgumentException with a message that is safe to show the model if it is not
     */
    public URI validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("URL is empty");
        }

        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid URL");
        }

        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Only http and https URLs are allowed");
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("URL has no host");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("URLs with a user name or password are not allowed");
        }
        if (uri.getPort() != -1 && !ALLOWED_PORTS.contains(uri.getPort())) {
            throw new IllegalArgumentException("Only ports 80 and 443 are allowed");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Host could not be resolved");
        }
        for (InetAddress address : addresses) {
            if (isInternal(address)) {
                // Deliberately does not say which address, to avoid helping a probe.
                throw new IllegalArgumentException("URL points to a non-public address and is blocked");
            }
        }
        return uri;
    }

    /** True for every address range that is not reachable from the public internet. */
    static boolean isInternal(InetAddress address) {
        if (address.isAnyLocalAddress()        // 0.0.0.0, ::
                || address.isLoopbackAddress() // 127.0.0.0/8, ::1
                || address.isLinkLocalAddress()// 169.254.0.0/16 (cloud metadata), fe80::/10
                || address.isSiteLocalAddress()// 10/8, 172.16/12, 192.168/16
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] b = address.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            return first == 0                                  // 0.0.0.0/8 "this network"
                    || (first == 100 && (second & 0xC0) == 64) // 100.64.0.0/10 carrier-grade NAT
                    || (first == 198 && (second & 0xFE) == 18);// 198.18.0.0/15 benchmarking
        }
        // IPv6 unique-local fc00::/7. Java's isSiteLocalAddress only knows the retired fec0::/10.
        return (b[0] & 0xFE) == 0xFC;
    }
}