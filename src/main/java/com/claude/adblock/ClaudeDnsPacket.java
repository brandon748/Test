package com.claude.adblock;

import java.util.Locale;

/** Minimal IPv4/UDP/DNS parsing and building for the DNS packets that reach the VPN. */
final class ClaudeDnsPacket {

    private ClaudeDnsPacket() {}

    /** Parsed IPv4 UDP datagram. */
    static final class Udp {
        byte[] srcIp = new byte[4];
        byte[] dstIp = new byte[4];
        int srcPort;
        int dstPort;
        byte[] payload;
    }

    /** Returns the UDP datagram in an IPv4 packet, or null if it isn't one. */
    static Udp parseIpv4Udp(byte[] pkt, int len) {
        if (len < 28) return null;
        if ((pkt[0] >> 4 & 0xF) != 4) return null;
        int ihl = (pkt[0] & 0xF) * 4;
        if (ihl < 20) return null;
        int total = u16(pkt, 2);
        if (total > len || total < ihl + 8) return null;
        if ((pkt[9] & 0xFF) != 17) return null; // UDP only
        // Ignore fragments (DNS queries are tiny and never fragmented).
        if ((u16(pkt, 6) & 0x3FFF) != 0) return null;
        Udp u = new Udp();
        System.arraycopy(pkt, 12, u.srcIp, 0, 4);
        System.arraycopy(pkt, 16, u.dstIp, 0, 4);
        u.srcPort = u16(pkt, ihl);
        u.dstPort = u16(pkt, ihl + 2);
        int udpLen = u16(pkt, ihl + 4);
        if (udpLen < 8 || ihl + udpLen > total) return null;
        u.payload = new byte[udpLen - 8];
        System.arraycopy(pkt, ihl + 8, u.payload, 0, u.payload.length);
        return u;
    }

    /** Wraps a DNS reply in IPv4/UDP headers addressed back to the original sender. */
    static byte[] buildReply(Udp query, byte[] dns, int dnsLen) {
        int total = 20 + 8 + dnsLen;
        byte[] p = new byte[total];
        p[0] = 0x45;
        put16(p, 2, total);
        p[6] = 0x40; // don't fragment
        p[8] = 64; // TTL
        p[9] = 17; // UDP
        System.arraycopy(query.dstIp, 0, p, 12, 4);
        System.arraycopy(query.srcIp, 0, p, 16, 4);
        put16(p, 10, ipChecksum(p, 20));
        put16(p, 20, query.dstPort);
        put16(p, 22, query.srcPort);
        put16(p, 24, 8 + dnsLen);
        // UDP checksum 0 = "not computed", which IPv4 allows.
        System.arraycopy(dns, 0, p, 28, dnsLen);
        return p;
    }

    /** Returns the first question name of a DNS query, lower-cased, or null. */
    static String queryName(byte[] dns) {
        if (dns.length < 17) return null;
        if ((dns[2] & 0x80) != 0) return null; // a response, not a query
        if (u16(dns, 4) < 1) return null;
        StringBuilder sb = new StringBuilder();
        int i = 12;
        while (i < dns.length) {
            int n = dns[i] & 0xFF;
            if (n == 0) break;
            if ((n & 0xC0) != 0 || i + 1 + n > dns.length) return null;
            if (sb.length() > 0) sb.append('.');
            for (int k = 0; k < n; k++) sb.append((char) (dns[i + 1 + k] & 0xFF));
            i += 1 + n;
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** Builds an NXDOMAIN reply ("no such domain") for a query. */
    static byte[] nxdomain(byte[] query) {
        // Keep header + question section only.
        int end = 12;
        while (end < query.length && query[end] != 0) end += 1 + (query[end] & 0xFF);
        end = Math.min(end + 1 + 4, query.length); // root label + QTYPE + QCLASS
        byte[] r = new byte[end];
        System.arraycopy(query, 0, r, 0, end);
        r[2] = (byte) (0x80 | (query[2] & 0x79)); // QR=1, keep opcode + RD
        r[3] = (byte) 0x83; // RA=1, RCODE=3 (NXDOMAIN)
        put16(r, 4, 1);
        put16(r, 6, 0);
        put16(r, 8, 0);
        put16(r, 10, 0);
        return r;
    }

    private static int ipChecksum(byte[] b, int len) {
        int sum = 0;
        for (int i = 0; i < len; i += 2) sum += u16(b, i);
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return ~sum & 0xFFFF;
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static void put16(byte[] b, int off, int v) {
        b[off] = (byte) (v >> 8);
        b[off + 1] = (byte) v;
    }
}
