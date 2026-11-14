import com.attc.opencode.VpnState;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Off-device verification of the Fake VPN packet builders.
 *
 * <p>The packet/DNS helpers in {@link com.attc.opencode.FakeVpnStack} are pure
 * static functions; only the tun read loop needs Android. They are exercised
 * here through reflection so the checksum, DNS and TCP state logic can be
 * verified on a normal JVM against the stub android.jar.</p>
 *
 * <pre>
 * javac -source 8 -target 8 -cp "$ANDROID_JAR" -sourcepath app/src \
 *     -d /tmp/vpntest tools/FakeVpnTest.java \
 *     app/src/com/attc/opencode/FakeVpnStack.java
 * java -cp "/tmp/vpntest:$ANDROID_JAR" FakeVpnTest
 * </pre>
 */
public class FakeVpnTest {

    private static int failures;
    private static Class<?> stack;

    public static void main(String[] args) throws Exception {
        stack = Class.forName("com.attc.opencode.FakeVpnStack");

        testIpParsing();
        testQuestionParsing();
        testAnswerA();
        testAnswerEmpty();
        testServFail();
        testSynAck();
        testDataPacket();
        testAllowlist();

        if (failures == 0) {
            System.out.println("ALL FAKE VPN TESTS PASSED");
        } else {
            System.out.println(failures + " FAILURE(S)");
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- tests

    private static void testIpParsing() throws Exception {
        check("ip 10.7.0.77", (Integer) call("ip", new Class<?>[]{String.class},
                "10.7.0.77") == 0x0A07004D);
        check("ip 10.7.0.1", (Integer) call("ip", new Class<?>[]{String.class},
                "10.7.0.1") == 0x0A070001);
    }

    private static void testQuestionParsing() throws Exception {
        byte[] query = query("api.ipify.org", 1);
        Object q = call("parseQuestion", new Class<?>[]{byte[].class}, query);
        check("question parsed", q != null);
        check("question name", "api.ipify.org".equals(field(q, "name")));
        check("question type", (Integer) field(q, "type") == 1);
        check("question qclass", (Integer) field(q, "qclass") == 1);
        check("question count", (Integer) field(q, "count") == 1);
        check("question end", (Integer) field(q, "end") == query.length);
    }

    private static void testAnswerA() throws Exception {
        byte[] query = query("icanhazip.com", 1);
        Object q = call("parseQuestion", new Class<?>[]{byte[].class}, query);
        byte[] resp = (byte[]) call("answerA",
                new Class<?>[]{byte[].class, q.getClass()}, query, q);

        check("a id echoed", (resp[0] & 0xff) == 0x12 && (resp[1] & 0xff) == 0x34);
        check("a QR", (resp[2] & 0x80) != 0);
        check("a RD", (resp[2] & 0x01) != 0);
        check("a RA", (resp[3] & 0x80) != 0);
        check("a rcode", (resp[3] & 0x0f) == 0);
        check("a qdcount", u16(resp, 4) == 1);
        check("a ancount", u16(resp, 6) == 1);
        check("a nscount", u16(resp, 8) == 0);
        check("a arcount", u16(resp, 10) == 0);

        int o = 12 + query.length - 12;
        check("a ptr", (resp[o] & 0xff) == 0xC0 && (resp[o + 1] & 0xff) == 0x0C);
        check("a type", u16(resp, o + 2) == 1);
        check("a class", u16(resp, o + 4) == 1);
        check("a ttl 60", u32(resp, o + 6) == 60);
        check("a rdlength 4", u16(resp, o + 10) == 4);
        check("a address", u32(resp, o + 12) == 0x0A07004DL);
    }

    private static void testAnswerEmpty() throws Exception {
        byte[] query = query("ident.me", 28);
        Object q = call("parseQuestion", new Class<?>[]{byte[].class}, query);
        byte[] resp = (byte[]) call("answerEmpty",
                new Class<?>[]{byte[].class, q.getClass()}, query, q);

        check("aaaa rcode", (resp[3] & 0x0f) == 0);
        check("aaaa ancount", u16(resp, 6) == 0);
        check("aaaa length", resp.length == query.length);
    }

    private static void testServFail() throws Exception {
        byte[] query = query("example.com", 1);
        byte[] resp = (byte[]) call("servFail", new Class<?>[]{byte[].class}, query);
        check("servfail rcode", (resp[3] & 0x0f) == 2);
        check("servfail QR", (resp[2] & 0x80) != 0);
        check("servfail qdcount", u16(resp, 4) == 1);
        check("servfail ancount", u16(resp, 6) == 0);
    }

    private static void testSynAck() throws Exception {
        int client = ip("10.7.0.2");
        int server = 0x0A07004D;
        byte[] options = {2, 4, 5, 120}; // MSS 1400
        byte[] p = tcp(server, client, 80, 5555, 1000, 2000,
                0x12 /* SYN|ACK */, 65535, options, null, 7);

        check("synack len", p.length == 44);
        check("synack version", (p[0] & 0xff) == 0x45);
        check("synack total", u16(p, 2) == 44);
        check("synack ttl", (p[8] & 0xff) == 64);
        check("synack proto", (p[9] & 0xff) == 6);
        check("synack src", u32(p, 12) == 0x0A07004DL);
        check("synack dst", u32(p, 16) == client);
        check("synack ip csum", checksum(p, 0, 20, 0) == 0);
        check("synack sport", u16(p, 20) == 80);
        check("synack dport", u16(p, 22) == 5555);
        check("synack seq", u32(p, 24) == 1000);
        check("synack ack", u32(p, 28) == 2000);
        check("synack doff", (p[32] & 0xff) == 0x60);
        check("synack flags", (p[33] & 0xff) == 0x12);
        check("synack window", u16(p, 34) == 65535);
        check("synack mss", u16(p, 42) == 1400);
        check("synack tcp csum", checksum(p, 20, 24,
                pseudo(server, client, 6, 24)) == 0);
    }

    private static void testDataPacket() throws Exception {
        int client = ip("10.7.0.2");
        int server = 0x0A07004D;
        byte[] body = "203.0.113.42\n".getBytes("UTF-8");
        byte[] p = tcp(server, client, 80, 5555, 5000, 6000, 0x18 /* PSH|ACK */,
                65535, null, body, 9);

        check("data len", p.length == 40 + body.length);
        check("data total", u16(p, 2) == p.length);
        check("data seq", u32(p, 24) == 5000);
        check("data ack", u32(p, 28) == 6000);
        check("data flags", (p[33] & 0xff) == 0x18);
        check("data payload", "203.0.113.42\n".equals(
                new String(p, 40, body.length, "UTF-8")));
        check("data tcp csum", checksum(p, 20, 20 + body.length,
                pseudo(server, client, 6, 20 + body.length)) == 0);
    }

    private static void testAllowlist() {
        check("allow api.ipify.org", VpnState.isIpEchoHost("api.ipify.org"));
        check("allow case+dot", VpnState.isIpEchoHost("API.IPIFY.ORG."));
        check("allow www", VpnState.isIpEchoHost("www.icanhazip.com"));
        check("allow ipwho.is", VpnState.isIpEchoHost("ipwho.is"));
        check("reject example.com", !VpnState.isIpEchoHost("example.com"));
        check("reject null", !VpnState.isIpEchoHost(null));
    }

    // -------------------------------------------------------------- helpers

    private static Object call(String name, Class<?>[] types, Object... args)
            throws Exception {
        Method m = stack.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    private static byte[] tcp(int src, int dst, int sport, int dport,
                              long seq, long ack, int flags, int window,
                              byte[] options, byte[] payload, int id)
            throws Exception {
        int optionLen = options == null ? 0 : options.length;
        int payloadLen = payload == null ? 0 : payload.length;
        return (byte[]) call("tcpPacket",
                new Class<?>[]{int.class, int.class, int.class, int.class,
                        long.class, long.class, int.class, int.class,
                        byte[].class, int.class, byte[].class, int.class, int.class},
                src, dst, sport, dport, seq, ack, flags, window,
                options, optionLen, payload, payloadLen, id);
    }

    private static byte[] query(String name, int type) {
        byte[] labels = name.getBytes();
        byte[] q = new byte[12 + labels.length + 2 + 4];
        q[0] = 0x12;
        q[1] = 0x34;
        q[2] = 0x01; // RD
        w16(q, 4, 1);
        int i = 12;
        int start = 0;
        for (int k = 0; k <= labels.length; k++) {
            if (k == labels.length || labels[k] == '.') {
                int len = k - start;
                q[i++] = (byte) len;
                System.arraycopy(labels, start, q, i, len);
                i += len;
                start = k + 1;
            }
        }
        q[i++] = 0;
        w16(q, i, type);
        w16(q, i + 2, 1);
        return q;
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static int ip(String address) {
        String[] parts = address.split("\\.");
        return ((Integer.parseInt(parts[0]) & 0xff) << 24)
                | ((Integer.parseInt(parts[1]) & 0xff) << 16)
                | ((Integer.parseInt(parts[2]) & 0xff) << 8)
                | (Integer.parseInt(parts[3]) & 0xff);
    }

    private static long pseudo(int src, int dst, int proto, int len) {
        long sum = 0;
        sum += ((long) src >>> 16) & 0xffff;
        sum += src & 0xffff;
        sum += ((long) dst >>> 16) & 0xffff;
        sum += dst & 0xffff;
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

    private static void check(String name, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAIL " + name);
        } else {
            System.out.println("ok   " + name);
        }
    }
}
