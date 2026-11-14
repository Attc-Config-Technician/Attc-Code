package com.attc.opencode;

import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Userspace side of the Fake VPN.
 *
 * The tun device only routes 10.7.0.0/24, so normal traffic keeps using the
 * real network. The stack terminates HTTP to FAKE_IP locally (returning the
 * simulated public IP), answers A/AAAA for IP-echo hosts on DNS_IP, and
 * forwards every other DNS query to upstream resolvers over protected UDP
 * sockets. Anything else is dropped.
 */
public final class FakeVpnStack implements Runnable {

    private static final int MAX_PACKET = 32768;
    private static final int MAX_FLOWS = 256;
    private static final int MAX_CACHE = 256;
    private static final long MAX_TTL_MS = 300000L;
    private static final long DNS_TIMEOUT_MS = 5000L;
    private static final long FLOW_IDLE_MS = 120000L;

    private static final String[] DNS_SERVERS = {"1.1.1.1", "8.8.8.8"};

    private static final int FIN = 0x01;
    private static final int SYN = 0x02;
    private static final int RST = 0x04;
    private static final int PSH = 0x08;
    private static final int ACK = 0x10;

    private static final int FAKE = ip(VpnState.FAKE_IP);
    private static final int DNS = ip(VpnState.DNS_IP);

    private final VpnService service;
    private final ParcelFileDescriptor tun;
    private final FileInputStream in;
    private final FileOutputStream out;
    private final Object writeLock = new Object();
    private final ConcurrentHashMap<String, Flow> flows = new ConcurrentHashMap<>();
    private final AtomicInteger packetId = new AtomicInteger(1);
    private final Random random = new Random();

    private final ExecutorService dnsExecutor =
            Executors.newFixedThreadPool(8, new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "attc-fakevpn-dns");
                    t.setDaemon(true);
                    return t;
                }
            });

    private final LinkedHashMap<String, CacheEntry> dnsCache =
            new LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > MAX_CACHE;
                }
            };

    private final ConcurrentHashMap<String, Long> fakeLogs = new ConcurrentHashMap<>();
    private final AtomicInteger forwarded = new AtomicInteger();
    private volatile long lastForwardedLog;

    private volatile boolean running = true;

    private static final class Flow {
        final int clientIp;
        final int clientPort;
        final long ourIsn;

        long sndNxt;
        long rcvNxt;
        boolean responseSent;
        boolean finSent;
        long lastActive = System.currentTimeMillis();

        Flow(int clientIp, int clientPort, long ourIsn) {
            this.clientIp = clientIp;
            this.clientPort = clientPort;
            this.ourIsn = ourIsn;
            this.sndNxt = (ourIsn + 1) & 0xffffffffL;
        }
    }

    private static final class CacheEntry {
        final byte[] data;
        final long expires;

        CacheEntry(byte[] data, long expires) {
            this.data = data;
            this.expires = expires;
        }
    }

    private static final class Question {
        final String name;
        final int type;
        final int qclass;
        final int count;
        final int end;

        Question(String name, int type, int qclass, int count, int end) {
            this.name = name;
            this.type = type;
            this.qclass = qclass;
            this.count = count;
            this.end = end;
        }
    }

    public FakeVpnStack(VpnService service, ParcelFileDescriptor tun) throws IOException {
        this.service = service;
        this.tun = tun;
        this.in = new FileInputStream(tun.getFileDescriptor());
        this.out = new FileOutputStream(tun.getFileDescriptor());
    }

    public void stop() {
        running = false;
        try {
            in.close();
        } catch (IOException ignored) {
        }
        try {
            out.close();
        } catch (IOException ignored) {
        }
        try {
            tun.close();
        } catch (IOException ignored) {
        }
        dnsExecutor.shutdownNow();
        flows.clear();
        synchronized (dnsCache) {
            dnsCache.clear();
        }
    }

    @Override
    public void run() {
        byte[] buffer = new byte[MAX_PACKET];
        long packets = 0;
        while (running) {
            int len;
            try {
                len = in.read(buffer);
            } catch (IOException e) {
                break;
            }
            if (len < 20) {
                continue;
            }
            try {
                handle(buffer, len);
            } catch (Exception ignored) {
                // One malformed packet must never take the tunnel down.
            }
            if ((++packets & 1023) == 0) {
                pruneFlows();
            }
        }
    }

    private void handle(byte[] b, int len) {
        if ((b[0] & 0xf0) != 0x40) {
            return; // IPv6 is not routed here.
        }
        int ihl = (b[0] & 0x0f) * 4;
        if (ihl < 20 || len < ihl) {
            return;
        }
        int total = u16(b, 2);
        if (total < ihl || total > len) {
            return;
        }
        int proto = u8(b, 9);
        int src = (int) u32(b, 12);
        int dst = (int) u32(b, 16);
        if (proto == 6) {
            onTcp(b, ihl, total, src, dst);
        } else if (proto == 17) {
            onUdp(b, ihl, total, src, dst);
        }
    }

    // ------------------------------------------------------------------ TCP

    private void onTcp(byte[] b, int ihl, int total, int srcIp, int dstIp) {
        if (dstIp != FAKE) {
            return; // Canonical route should prevent this.
        }
        int sport = u16(b, ihl);
        int dport = u16(b, ihl + 2);
        long seq = u32(b, ihl + 4);
        long ack = u32(b, ihl + 8);
        int doff = ((b[ihl + 12] >> 4) & 0x0f) * 4;
        if (doff < 20) {
            return;
        }
        int flags = u8(b, ihl + 13);
        int payloadOff = ihl + doff;
        int payloadLen = total - payloadOff;
        if (payloadLen < 0) {
            return;
        }

        if (dport != 80) {
            sendReset(srcIp, sport, dport, seq, ack, flags, payloadLen);
            return;
        }

        String key = srcIp + ":" + sport;
        Flow flow = flows.get(key);

        if ((flags & SYN) != 0 && (flags & ACK) == 0) {
            if (flow != null) {
                // Retransmitted SYN: resend the same SYN-ACK.
                sendSynAck(flow);
                return;
            }
            if (flows.size() >= MAX_FLOWS) {
                pruneFlows();
            }
            if (flows.size() >= MAX_FLOWS) {
                sendReset(srcIp, sport, dport, seq, ack, flags, payloadLen);
                return;
            }
            flow = new Flow(srcIp, sport, random.nextInt() & 0xffffffffL);
            flow.rcvNxt = (seq + 1) & 0xffffffffL;
            flows.put(key, flow);
            sendSynAck(flow);
            return;
        }

        if (flow == null) {
            sendReset(srcIp, sport, dport, seq, ack, flags, payloadLen);
            return;
        }
        flow.lastActive = System.currentTimeMillis();

        if ((flags & RST) != 0) {
            flows.remove(key);
            return;
        }

        if (payloadLen > 0) {
            long end = (seq + payloadLen) & 0xffffffffL;
            if (seqLt(flow.rcvNxt, end)) {
                flow.rcvNxt = end;
            }
            if (!flow.responseSent) {
                byte[] body = (VpnState.getSimulatedIp(service) + "\n")
                        .getBytes(StandardCharsets.UTF_8);
                byte[] head = ("HTTP/1.1 200 OK\r\n"
                        + "Content-Type: text/plain\r\n"
                        + "Connection: close\r\n"
                        + "Content-Length: " + body.length + "\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1);
                byte[] response = new byte[head.length + body.length];
                System.arraycopy(head, 0, response, 0, head.length);
                System.arraycopy(body, 0, response, head.length, body.length);

                send(tcpPacket(FAKE, srcIp, 80, sport, flow.sndNxt, flow.rcvNxt,
                        PSH | ACK, 65535, null, 0, response, response.length, nextId()));
                flow.sndNxt = (flow.sndNxt + response.length) & 0xffffffffL;
                send(tcpPacket(FAKE, srcIp, 80, sport, flow.sndNxt, flow.rcvNxt,
                        FIN | ACK, 65535, null, 0, null, 0, nextId()));
                flow.sndNxt = (flow.sndNxt + 1) & 0xffffffffL;
                flow.responseSent = true;
                flow.finSent = true;
            } else {
                send(tcpPacket(FAKE, srcIp, 80, sport, flow.sndNxt, flow.rcvNxt,
                        ACK, 65535, null, 0, null, 0, nextId()));
            }
        }

        if ((flags & FIN) != 0) {
            flow.rcvNxt = (flow.rcvNxt + 1) & 0xffffffffL;
            send(tcpPacket(FAKE, srcIp, 80, sport, flow.sndNxt, flow.rcvNxt,
                    ACK, 65535, null, 0, null, 0, nextId()));
            flows.remove(key);
        } else if (flow.finSent && (flags & ACK) != 0 && !seqLt(ack, flow.sndNxt)) {
            flows.remove(key);
        }
    }

    private void sendSynAck(Flow flow) {
        byte[] options = {2, 4, 5, 120}; // MSS 1400
        send(tcpPacket(FAKE, flow.clientIp, 80, flow.clientPort, flow.ourIsn, flow.rcvNxt,
                SYN | ACK, 65535, options, options.length, null, 0, nextId()));
    }

    private void sendReset(int clientIp, int clientPort, int dport,
                           long seq, long ack, int flags, int payloadLen) {
        long rstSeq = (flags & ACK) != 0 ? ack : 0;
        long rstAck = (seq + payloadLen + ((flags & SYN) != 0 ? 1 : 0)) & 0xffffffffL;
        send(tcpPacket(FAKE, clientIp, dport, clientPort, rstSeq, rstAck, RST | ACK,
                0, null, 0, null, 0, nextId()));
    }

    private void pruneFlows() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Flow> e : flows.entrySet()) {
            if (now - e.getValue().lastActive > FLOW_IDLE_MS) {
                flows.remove(e.getKey());
            }
        }
    }

    // ------------------------------------------------------------------ UDP

    private void onUdp(byte[] b, int ihl, int total, int srcIp, int dstIp) {
        if (dstIp != DNS) {
            return;
        }
        int sport = u16(b, ihl);
        int dport = u16(b, ihl + 2);
        if (dport != 53) {
            return;
        }
        int payloadOff = ihl + 8;
        int payloadLen = total - payloadOff;
        if (payloadLen <= 0) {
            return;
        }
        byte[] query = new byte[payloadLen];
        System.arraycopy(b, payloadOff, query, 0, payloadLen);

        Question question = parseQuestion(query);
        if (question != null && question.count == 1 && question.qclass == 1) {
            if (VpnState.isIpEchoHost(question.name)) {
                if (question.type == 1) {
                    noteFake(question.name, "A");
                    byte[] reply = answerA(query, question);
                    send(udpPacket(DNS, srcIp, 53, sport, reply, reply.length, nextId()));
                    return;
                }
                if (question.type == 28) {
                    noteFake(question.name, "AAAA");
                    byte[] reply = answerEmpty(query, question);
                    send(udpPacket(DNS, srcIp, 53, sport, reply, reply.length, nextId()));
                    return;
                }
            }
        }
        String cacheKey = question != null && question.count == 1
                ? question.name + "|" + question.type : null;
        forwardDns(srcIp, sport, query, cacheKey);
    }

    private void forwardDns(final int srcIp, final int sport,
                            final byte[] query, final String cacheKey) {
        dnsExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] response = cacheGet(cacheKey);
                    if (response != null) {
                        response = response.clone();
                    } else {
                        noteForwarded();
                        response = resolveUpstream(query);
                        if (response != null) {
                            cachePut(cacheKey, response);
                        }
                    }
                    if (response == null) {
                        response = servFail(query);
                    }
                    if (response.length >= 2) {
                        response[0] = query[0];
                        response[1] = query[1];
                    }
                    send(udpPacket(DNS, srcIp, 53, sport, response, response.length, nextId()));
                } catch (Exception ignored) {
                }
            }
        });
    }

    private byte[] resolveUpstream(byte[] query) {
        long deadline = System.currentTimeMillis() + DNS_TIMEOUT_MS;
        for (String server : DNS_SERVERS) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                break;
            }
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket();
                service.protect(socket);
                socket.setSoTimeout((int) Math.min(remaining, Integer.MAX_VALUE));
                InetAddress target = InetAddress.getByName(server);
                socket.send(new DatagramPacket(query, query.length, target, 53));
                byte[] buf = new byte[4096];
                DatagramPacket reply = new DatagramPacket(buf, buf.length);
                socket.receive(reply);
                byte[] out = new byte[reply.getLength()];
                System.arraycopy(reply.getData(), reply.getOffset(), out, 0, out.length);
                return out;
            } catch (Exception ignored) {
                // Try the next resolver until the shared deadline runs out.
            } finally {
                if (socket != null) {
                    socket.close();
                }
            }
        }
        return null;
    }

    private static byte[] answerA(byte[] query, Question q) {
        int qlen = q.end - 12;
        byte[] resp = new byte[12 + qlen + 16];
        System.arraycopy(query, 0, resp, 0, 12 + qlen);
        resp[2] = (byte) (0x80 | (query[2] & 0x01));
        resp[3] = (byte) 0x80;
        w16(resp, 4, 1);
        w16(resp, 6, 1);
        w16(resp, 8, 0);
        w16(resp, 10, 0);
        int o = 12 + qlen;
        resp[o] = (byte) 0xC0;
        resp[o + 1] = 0x0C;
        w16(resp, o + 2, 1);
        w16(resp, o + 4, 1);
        w32(resp, o + 6, 60);
        w16(resp, o + 10, 4);
        w32(resp, o + 12, FAKE & 0xffffffffL);
        return resp;
    }

    private static byte[] answerEmpty(byte[] query, Question q) {
        int qlen = q.end - 12;
        byte[] resp = new byte[12 + qlen];
        System.arraycopy(query, 0, resp, 0, 12 + qlen);
        resp[2] = (byte) (0x80 | (query[2] & 0x01));
        resp[3] = (byte) 0x80;
        w16(resp, 4, 1);
        w16(resp, 6, 0);
        w16(resp, 8, 0);
        w16(resp, 10, 0);
        return resp;
    }

    private static byte[] servFail(byte[] query) {
        if (query.length < 12) {
            return new byte[0];
        }
        int end = questionSectionEnd(query);
        int qd = 0;
        if (end < 12 || end > query.length) {
            end = 12;
        } else {
            qd = u16(query, 4);
        }
        byte[] resp = new byte[end];
        System.arraycopy(query, 0, resp, 0, end);
        if (end >= 12) {
            resp[2] = (byte) (0x80 | (query[2] & 0x01));
            resp[3] = (byte) (0x80 | 0x02);
            w16(resp, 4, qd);
            w16(resp, 6, 0);
            w16(resp, 8, 0);
            w16(resp, 10, 0);
        }
        return resp;
    }

    private static Question parseQuestion(byte[] q) {
        if (q.length < 12) {
            return null;
        }
        int count = u16(q, 4);
        int i = 12;
        StringBuilder name = new StringBuilder();
        while (true) {
            if (i >= q.length) {
                return null;
            }
            int label = q[i] & 0xff;
            if (label == 0) {
                i++;
                break;
            }
            if ((label & 0xC0) != 0 || i + 1 + label > q.length) {
                return null;
            }
            if (name.length() > 0) {
                name.append('.');
            }
            name.append(new String(q, i + 1, label, StandardCharsets.ISO_8859_1));
            if (name.length() > 255) {
                return null;
            }
            i += 1 + label;
        }
        if (i + 4 > q.length) {
            return null;
        }
        int type = u16(q, i);
        int qclass = u16(q, i + 2);
        return new Question(name.toString().toLowerCase(Locale.US), type, qclass, count, i + 4);
    }

    private static int questionSectionEnd(byte[] q) {
        if (q.length < 12) {
            return 12;
        }
        int count = u16(q, 4);
        int i = 12;
        for (int k = 0; k < count; k++) {
            i = skipName(q, i);
            if (i < 0 || i + 4 > q.length) {
                return -1;
            }
            i += 4;
        }
        return i;
    }

    private static int skipName(byte[] b, int i) {
        while (i < b.length) {
            int c = b[i] & 0xff;
            if (c == 0) {
                return i + 1;
            }
            if ((c & 0xC0) == 0xC0) {
                return i + 2 <= b.length ? i + 2 : -1;
            }
            i += c + 1;
            if (i > b.length) {
                return -1;
            }
        }
        return -1;
    }

    // --------------------------------------------------------------- caching

    private byte[] cacheGet(String key) {
        if (key == null) {
            return null;
        }
        synchronized (dnsCache) {
            CacheEntry entry = dnsCache.get(key);
            if (entry == null) {
                return null;
            }
            if (entry.expires <= System.currentTimeMillis()) {
                dnsCache.remove(key);
                return null;
            }
            return entry.data;
        }
    }

    private void cachePut(String key, byte[] response) {
        if (key == null) {
            return;
        }
        long ttl = ttlMillis(response);
        if (ttl <= 0) {
            return;
        }
        synchronized (dnsCache) {
            dnsCache.put(key, new CacheEntry(response.clone(),
                    System.currentTimeMillis() + ttl));
        }
    }

    private static long ttlMillis(byte[] r) {
        if (r.length < 12) {
            return -1;
        }
        int qd = u16(r, 4);
        int an = u16(r, 6);
        if (an <= 0) {
            return -1;
        }
        int i = 12;
        for (int k = 0; k < qd; k++) {
            i = skipName(r, i);
            if (i < 0 || i + 4 > r.length) {
                return -1;
            }
            i += 4;
        }
        i = skipName(r, i);
        if (i < 0 || i + 10 > r.length) {
            return -1;
        }
        long ttl = u32(r, i + 4) * 1000L;
        if (ttl <= 0) {
            return -1;
        }
        return Math.min(ttl, MAX_TTL_MS);
    }

    // -------------------------------------------------------------- logging

    private void noteForwarded() {
        forwarded.incrementAndGet();
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (now - lastForwardedLog >= 60000L) {
                int n = forwarded.getAndSet(0);
                if (n > 0) {
                    lastForwardedLog = now;
                    Logger.i("vpn: dns forwarded n=" + n);
                }
            }
        }
    }

    private void noteFake(String name, String type) {
        long now = System.currentTimeMillis();
        Long previous = fakeLogs.get(name);
        if (previous == null || now - previous >= 300000L) {
            fakeLogs.put(name, now);
            Logger.i("vpn: dns fake q=" + name + " type=" + type);
        }
        if (fakeLogs.size() > 256) {
            fakeLogs.clear();
        }
    }

    private int nextId() {
        return packetId.incrementAndGet() & 0x7fff;
    }

    // ------------------------------------------------------------- packets

    private void send(byte[] packet) {
        synchronized (writeLock) {
            try {
                out.write(packet);
            } catch (IOException e) {
                running = false;
            }
        }
    }

    private static int ip(String address) {
        String[] parts = address.split("\\.");
        return ((Integer.parseInt(parts[0]) & 0xff) << 24)
                | ((Integer.parseInt(parts[1]) & 0xff) << 16)
                | ((Integer.parseInt(parts[2]) & 0xff) << 8)
                | (Integer.parseInt(parts[3]) & 0xff);
    }

    private static byte[] tcpPacket(int srcIp, int dstIp, int sport, int dport,
                                    long seq, long ack, int flags, int window,
                                    byte[] options, int optionLen,
                                    byte[] payload, int payloadLen, int id) {
        int tcpHeader = 20 + optionLen;
        int total = 20 + tcpHeader + payloadLen;
        byte[] p = new byte[total];
        ipHeader(p, total, 6, srcIp, dstIp, id);
        int t = 20;
        w16(p, t, sport);
        w16(p, t + 2, dport);
        w32(p, t + 4, seq);
        w32(p, t + 8, ack);
        p[t + 12] = (byte) ((tcpHeader / 4) << 4);
        p[t + 13] = (byte) flags;
        w16(p, t + 14, window);
        if (optionLen > 0 && options != null) {
            System.arraycopy(options, 0, p, t + 20, optionLen);
        }
        if (payloadLen > 0 && payload != null) {
            System.arraycopy(payload, 0, p, t + tcpHeader, payloadLen);
        }
        long sum = pseudo(srcIp, dstIp, 6, tcpHeader + payloadLen);
        w16(p, t + 16, checksum(p, t, tcpHeader + payloadLen, sum));
        return p;
    }

    private static byte[] udpPacket(int srcIp, int dstIp, int sport, int dport,
                                    byte[] payload, int payloadLen, int id) {
        int total = 20 + 8 + payloadLen;
        byte[] p = new byte[total];
        ipHeader(p, total, 17, srcIp, dstIp, id);
        int u = 20;
        w16(p, u, sport);
        w16(p, u + 2, dport);
        w16(p, u + 4, 8 + payloadLen);
        if (payloadLen > 0 && payload != null) {
            System.arraycopy(payload, 0, p, u + 8, payloadLen);
        }
        long sum = pseudo(srcIp, dstIp, 17, 8 + payloadLen);
        int ck = checksum(p, u, 8 + payloadLen, sum);
        if (ck == 0) {
            ck = 0xffff; // Zero means "no checksum" for UDP.
        }
        w16(p, u + 6, ck);
        return p;
    }

    private static void ipHeader(byte[] p, int total, int proto,
                                 int srcIp, int dstIp, int id) {
        p[0] = 0x45;
        p[1] = 0;
        w16(p, 2, total);
        w16(p, 4, id & 0xffff);
        w16(p, 6, 0x4000);
        p[8] = 64;
        p[9] = (byte) proto;
        w32(p, 12, srcIp & 0xffffffffL);
        w32(p, 16, dstIp & 0xffffffffL);
        w16(p, 10, checksum(p, 0, 20, 0));
    }

    private static long pseudo(int srcIp, int dstIp, int proto, int len) {
        long sum = 0;
        sum += ((long) srcIp >>> 16) & 0xffff;
        sum += srcIp & 0xffff;
        sum += ((long) dstIp >>> 16) & 0xffff;
        sum += dstIp & 0xffff;
        sum += proto;
        sum += len;
        return sum;
    }

    private static int checksum(byte[] b, int off, int len, long sum) {
        int i = 0;
        for (; i + 1 < len; i += 2) {
            sum += ((b[off + i] & 0xff) << 8) | (b[off + i + 1] & 0xff);
        }
        if ((len & 1) != 0) {
            sum += (b[off + i] & 0xff) << 8;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xffff) + (sum >> 16);
        }
        return (int) (~sum) & 0xffff;
    }

    private static boolean seqLt(long a, long b) {
        return ((a - b) & 0xffffffffL) > 0x7fffffffL;
    }

    private static int u8(byte[] b, int o) {
        return b[o] & 0xff;
    }

    private static int u16(byte[] b, int o) {
        return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff);
    }

    private static long u32(byte[] b, int o) {
        return ((long) (b[o] & 0xff) << 24)
                | ((long) (b[o + 1] & 0xff) << 16)
                | ((long) (b[o + 2] & 0xff) << 8)
                | (long) (b[o + 3] & 0xff);
    }

    private static void w16(byte[] b, int o, int v) {
        b[o] = (byte) (v >> 8);
        b[o + 1] = (byte) v;
    }

    private static void w32(byte[] b, int o, long v) {
        b[o] = (byte) (v >> 24);
        b[o + 1] = (byte) (v >> 16);
        b[o + 2] = (byte) (v >> 8);
        b[o + 3] = (byte) v;
    }
}
