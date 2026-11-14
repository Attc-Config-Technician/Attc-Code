import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Off-device contract test for the plugin refresh retry policy.
 *
 * <p>Pins the timeouts and throttle windows measured on the target device, and
 * the semver comparison used to decide whether the plugin cache is stale.</p>
 */
public class PluginsTest {

    private static int failures;
    private static Class<?> plugins;

    public static void main(String[] args) throws Exception {
        plugins = Class.forName("com.attc.opencode.Plugins");

        check("connect timeout 8000", constant("CONNECT_TIMEOUT_MS") == 8000);
        check("read timeout 15000", constant("READ_TIMEOUT_MS") == 15000);
        check("join cap 25000", constant("LOOKUP_CAP_MS") == 25000);
        check("success throttle 6h", constant("THROTTLE_MS") == 6L * 60 * 60 * 1000);
        check("failure retry 30m", constant("RETRY_MS") == 30L * 60 * 1000);

        check("semver newer patch", compare("1.2.4", "1.2.3") > 0);
        check("semver newer minor", compare("1.10.0", "1.9.9") > 0);
        check("semver newer major", compare("2.0.0", "1.99.99") > 0);
        check("semver equal", compare("3.1.4", "3.1.4") == 0);
        check("semver suffix ignored", compare("3.1.4-beta.2", "3.1.4") == 0);
        check("semver shorter", compare("3.2", "3.1.9") > 0);

        if (failures == 0) {
            System.out.println("ALL PLUGINS TESTS PASSED");
        } else {
            System.out.println(failures + " FAILURE(S)");
            System.exit(1);
        }
    }

    private static long constant(String name) throws Exception {
        Field f = plugins.getDeclaredField(name);
        f.setAccessible(true);
        return f.getLong(null);
    }

    private static int compare(String a, String b) throws Exception {
        Method m = plugins.getDeclaredMethod("compareSemver", String.class, String.class);
        m.setAccessible(true);
        return (Integer) m.invoke(null, a, b);
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
