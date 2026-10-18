package aibridge;

/*
 * Pure-logic tests for the synthetic-URL builder used by the "In scope only"
 * filter. The proxy().history() rows surface host/port/tls separately, so the
 * URL handed to Burp's isInScope(String) must be assembled consistently.
 *
 * Run: java -cp montoya.jar:build:build-test aibridge.UrlForTest
 */

public final class UrlForTest {

    static int checks = 0;
    static int fails = 0;

    public static void main(String[] args) {
        simpleHttp();
        simpleHttps();
        customPort();
        defaultPortNormalization();
        rootPath();
        emptyPath();

        System.out.println("checks=" + checks + " fails=" + fails);
        if (fails > 0) {
            System.out.println("FAIL");
            System.exit(1);
        }
        System.out.println("PASS");
    }

    static AiAgentBridge.Item item(boolean tls, String host, int port, String path) {
        AiAgentBridge.Item it = new AiAgentBridge.Item();
        it.svc = fakeService(tls, host, port);
        it.req = fakeRequest(path);
        return it;
    }

    static void run(boolean tls, String host, int port, String path, String expected, String label) {
        checks++;
        String got = AiAgentBridge.urlFor(item(tls, host, port, path));
        if (!expected.equals(got)) {
            fails++;
            System.out.println("[" + label + "] expected=\"" + expected + "\" got=\"" + got + "\"");
        }
    }

    static void simpleHttp() {
        run(false, "api.example.com", 80, "/v1/users?id=9", "http://api.example.com/v1/users?id=9", "simple http");
    }

    static void simpleHttps() {
        run(true, "api.example.com", 443, "/v1/users", "https://api.example.com/v1/users", "simple https");
    }

    static void customPort() {
        run(true, "api.example.com", 8443, "/admin/panel", "https://api.example.com:8443/admin/panel", "custom port 8443");
        run(false, "api.example.com", 8081, "/x", "http://api.example.com:8081/x", "custom port 8081");
    }

    static void defaultPortNormalization() {
        run(true, "api.example.com", 443, "/x", "https://api.example.com/x", "https keeps :443 implicit");
        run(false, "api.example.com", 80, "/x", "http://api.example.com/x", "http keeps :80 implicit");
    }

    static void rootPath() {
        run(true, "api.example.com", 443, "/", "https://api.example.com/", "root path");
    }

    static void emptyPath() {
        run(false, "api.example.com", 80, "", "http://api.example.com/", "empty path");

        // hostile service: null path and null host must not blow up
        checks++;
        try {
            String got = AiAgentBridge.urlFor(item(true, null, 443, null));
            if (!"https://?/".equals(got)) {
                fails++;
                System.out.println("[null host/path] got=\"" + got + "\"");
            }
        } catch (Throwable t) {
            fails++;
            System.out.println("[null host/path] threw: " + t);
        }
    }

    // fake HttpService / HttpRequest via reflection proxies
    static burp.api.montoya.http.HttpService fakeService(final boolean tls, final String host, final int port) {
        return (burp.api.montoya.http.HttpService) java.lang.reflect.Proxy.newProxyInstance(
                UrlForTest.class.getClassLoader(),
                new Class<?>[]{burp.api.montoya.http.HttpService.class},
                (p, m, a) -> {
                    switch (m.getName()) {
                        case "secure": return tls;
                        case "host": return host;
                        case "port": return port;
                        default: throw new UnsupportedOperationException(m.getName());
                    }
                });
    }

    static burp.api.montoya.http.message.requests.HttpRequest fakeRequest(final String path) {
        return (burp.api.montoya.http.message.requests.HttpRequest) java.lang.reflect.Proxy.newProxyInstance(
                UrlForTest.class.getClassLoader(),
                new Class<?>[]{burp.api.montoya.http.message.requests.HttpRequest.class},
                (p, m, a) -> {
                    if ("path".equals(m.getName())) return path;
                    throw new UnsupportedOperationException(m.getName());
                });
    }
}
