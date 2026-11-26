package com.dailyai.assistant.integration;

import org.apache.hc.client5.http.DnsResolver;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/** Supplies exactly the already-validated addresses to Apache HttpClient's connection operator. */
final class PinnedDnsResolver implements DnsResolver {
    private final String host;
    private final InetAddress[] addresses;

    PinnedDnsResolver(String host, InetAddress[] addresses) {
        this.host = host.toLowerCase(java.util.Locale.ROOT);
        this.addresses = Arrays.copyOf(addresses, addresses.length);
        if (this.addresses.length == 0) throw new IllegalArgumentException("At least one pinned address is required");
    }

    @Override
    public InetAddress[] resolve(String requestedHost) throws UnknownHostException {
        if (requestedHost == null || !host.equals(requestedHost.toLowerCase(java.util.Locale.ROOT))) {
            throw new UnknownHostException("Only the validated publisher host is allowed");
        }
        return Arrays.copyOf(addresses, addresses.length);
    }

    @Override
    public String resolveCanonicalHostname(String requestedHost) throws UnknownHostException {
        if (requestedHost == null || !host.equals(requestedHost.toLowerCase(java.util.Locale.ROOT))) {
            throw new UnknownHostException("Only the validated publisher host is allowed");
        }
        return host;
    }
}
