package aibridge;

/*
 * Checkbox persistence across restarts: Include response / Hide sent /
 * In scope only. saveOptions()/loadOptions() talk to Burp's PersistedObject;
 * here the API is a reflection proxy backed by a plain HashMap.
 *
 * Run: java -cp montoya.jar:build:build-test aibridge.PersistOptionsTest
 */

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

public final class PersistOptionsTest {

    static int checks = 0;
    static int fails = 0;

    public static void main(String[] args) throws Throwable {
        roundTrip();
        defaultsWhenAbsent();
        survivesNullApi();

        System.out.println("checks=" + checks + " fails=" + fails);
        if (fails > 0) {
            System.out.println("FAIL");
            System.exit(1);
        }
        System.out.println("PASS");
    }

    static void eq(Object expect, Object got, String label) {
        checks++;
        if (expect == null ? got != null : !expect.equals(got)) {
            fails++;
            System.out.println("[" + label + "] expected=\"" + expect + "\" got=\"" + got + "\"");
        }
    }

    /** map-backed fake of persistence().extensionData() */
    @SuppressWarnings("unchecked")
    static burp.api.montoya.MontoyaApi fakeApi(Map<String, Object> store) {
        burp.api.montoya.persistence.PersistedObject po =
                (burp.api.montoya.persistence.PersistedObject) Proxy.newProxyInstance(
                        PersistOptionsTest.class.getClassLoader(),
                        new Class<?>[]{burp.api.montoya.persistence.PersistedObject.class},
                        (p, m, a) -> {
                            switch (m.getName()) {
                                case "getString": return (String) store.get((String) a[0]);
                                case "setString": store.put((String) a[0], (String) a[1]); return null;
                                case "getBoolean": return (Boolean) store.get((String) a[0]);
                                case "setBoolean": store.put((String) a[0], (Boolean) a[1]); return null;
                                case "deleteString": case "deleteBoolean":
                                    store.remove((String) a[0]); return null;
                                default: throw new UnsupportedOperationException(m.getName());
                            }
                        });
        burp.api.montoya.persistence.Persistence persistence =
                (burp.api.montoya.persistence.Persistence) Proxy.newProxyInstance(
                        PersistOptionsTest.class.getClassLoader(),
                        new Class<?>[]{burp.api.montoya.persistence.Persistence.class},
                        (p, m, a) -> po);
        return (burp.api.montoya.MontoyaApi) Proxy.newProxyInstance(
                PersistOptionsTest.class.getClassLoader(),
                new Class<?>[]{burp.api.montoya.MontoyaApi.class},
                (p, m, a) -> m.getName().equals("persistence") ? persistence : null);
    }

    static void roundTrip() throws Exception {
        Map<String, Object> store = new HashMap<String, Object>();
        AiAgentBridge saved = new AiAgentBridge();
        saved.api = fakeApi(store);
        saved.optIncludeResp = false;
        saved.optHideSent = true;
        saved.optInScopeOnly = true;
        saved.saveOptions();

        AiAgentBridge loaded = new AiAgentBridge();
        loaded.api = fakeApi(store);
        loaded.loadOptions();
        eq(Boolean.FALSE, loaded.optIncludeResp, "include response persisted false");
        eq(Boolean.TRUE, loaded.optHideSent, "hide sent persisted true");
        eq(Boolean.TRUE, loaded.optInScopeOnly, "in scope only persisted true");
    }

    static void defaultsWhenAbsent() throws Exception {
        AiAgentBridge loaded = new AiAgentBridge();
        loaded.api = fakeApi(new HashMap<String, Object>());
        loaded.loadOptions();
        eq(Boolean.TRUE, loaded.optIncludeResp, "include response defaults on");
        eq(Boolean.FALSE, loaded.optHideSent, "hide sent defaults off");
        eq(Boolean.FALSE, loaded.optInScopeOnly, "in scope only defaults off");
    }

    static void survivesNullApi() {
        checks++;
        try {
            AiAgentBridge naked = new AiAgentBridge();
            naked.optInScopeOnly = true;
            naked.saveOptions();
            naked.loadOptions();
        } catch (Throwable t) {
            fails++;
            System.out.println("[null api no-op] threw: " + t);
        }
    }
}
