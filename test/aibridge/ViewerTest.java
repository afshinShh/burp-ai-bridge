package aibridge;

/*
 * The row detail viewer and the full-text tooltips for truncated cells.
 * - ItemViewer: bottom preview pane; selection fills the Request/Response tabs
 *   with the FULL raw messages (display-capped for huge bodies, since the disk
 *   file always stays complete).
 * - tooltipFor: the hover text for Note and Agent cells must be the full
 *   stored string, not the truncated cell.
 *
 * Run: java -cp montoya.jar:build:build-test aibridge.ViewerTest
 */

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.responses.HttpResponse;




import java.nio.charset.StandardCharsets;

public final class ViewerTest {

    static int checks = 0;
    static int fails = 0;

    public static void main(String[] args) throws Throwable {
        System.setProperty("java.awt.headless", "true");
        previewShowsFullRawText();
        previewWithoutResponse();
        previewCapsHugeBodies();
        noteTooltipIsFullText();
        agentTooltipIsFullText();
        otherColumnsDoNotStealTooltips();

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

    // ------------------------------------------------------------------ fakes

    static HttpRequestProxy fakeReq(String content) throws Throwable {
        return new HttpRequestProxy(content.getBytes(StandardCharsets.UTF_8),
                burp.api.montoya.http.message.requests.HttpRequest.class);
    }

    static HttpRequestProxy fakeResp(String content) throws Throwable {
        return new HttpRequestProxy(content.getBytes(StandardCharsets.UTF_8),
                HttpResponse.class);
    }

    /** ByteArray + target-interface proxy chain sharing one byte payload */
    static final class HttpRequestProxy {
        final Object proxied;
        HttpRequestProxy(byte[] bytes, Class<?> iface) {
            final byte[] payload = bytes;
            java.lang.reflect.InvocationHandler ba = (p, m, a) -> {
                if ("getBytes".equals(m.getName())) return payload;
                if ("length".equals(m.getName())) return payload.length;
                throw new UnsupportedOperationException(m.getName());
            };
            ByteArray byteArray = (ByteArray) java.lang.reflect.Proxy.newProxyInstance(
                    ViewerTest.class.getClassLoader(),
                    new Class<?>[]{ByteArray.class}, ba);
            java.lang.reflect.InvocationHandler handler = (p, m, a) -> {
                if ("toByteArray".equals(m.getName())) return byteArray;
                throw new UnsupportedOperationException(m.getName());
            };
            this.proxied = java.lang.reflect.Proxy.newProxyInstance(
                    ViewerTest.class.getClassLoader(),
                    new Class<?>[]{iface}, handler);
        }
    }

    static AiAgentBridge.Item item(String reqRaw, String respRaw) throws Throwable {
        AiAgentBridge.Item it = new AiAgentBridge.Item();
        it.req = (burp.api.montoya.http.message.requests.HttpRequest) fakeReq(reqRaw).proxied;
        if (respRaw != null) {
            it.resp = (HttpResponse) fakeResp(respRaw).proxied;
        }
        return it;
    }

    // ------------------------------------------------------------------ tests

    static void previewShowsFullRawText() throws Throwable {
        AiAgentBridge.ItemViewer viewer = new AiAgentBridge.ItemViewer();
        String reqRaw = "GET /api/v1/users HTTP/1.1\r\nHost: api.example.com\r\n"
                + "Cookie: session=secret-token-value\r\nX-Debug: 1\r\n\r\n";
        String respRaw = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n"
                + "{\"users\":[{\"id\":1},{\"id\":2}]}";
        AiAgentBridge.Item it = item(reqRaw, respRaw);

        viewer.showItem(it);
        eq(reqRaw, viewer.requestText(), "request pane shows the full raw request");
        eq(respRaw, viewer.responseText(), "response pane shows the full raw response");
    }

    static void previewWithoutResponse() throws Throwable {
        AiAgentBridge.ItemViewer viewer = new AiAgentBridge.ItemViewer();
        String reqRaw = "GET /x HTTP/1.1\r\nHost: api.example.com\r\n\r\n";
        AiAgentBridge.Item it = item(reqRaw, null);

        viewer.showItem(it);
        eq(reqRaw, viewer.requestText(), "request pane still fills without a response");
        eq("(response not included)", viewer.responseText(), "response pane says why it is empty");

        viewer.clear();
        eq("", viewer.requestText(), "clear empties the request pane");
        eq("", viewer.responseText(), "clear empties the response pane");
    }

    /** display cap keeps the EDT quick; the disk file itself is never truncated */
    static void previewCapsHugeBodies() throws Throwable {
        AiAgentBridge.ItemViewer viewer = new AiAgentBridge.ItemViewer();
        StringBuilder huge = new StringBuilder("GET /big HTTP/1.1\r\n\r\n");
        while (huge.length() < 900_000) huge.append("A");
        AiAgentBridge.Item it = item(huge.toString(), null);

        viewer.showItem(it);
        checks++;
        if (!(viewer.requestText().length() > AiAgentBridge.ItemViewer.MAX_CHARS - 1
                && viewer.requestText().length() <= AiAgentBridge.ItemViewer.MAX_CHARS + 100)) {
            fails++;
            System.out.println("[cap size] length=" + viewer.requestText().length()
                    + " should be MAX_CHARS + marker only");
        }
        checks++;
        if (!viewer.requestText().startsWith("GET /big")) {
            fails++;
            System.out.println("[cap prefix] capped text lost the raw start");
        }
        checks++;
        if (!viewer.requestText().endsWith("[display truncated]")) {
            fails++;
            System.out.println("[cap marker] capped text does not mark the truncation");
        }
    }

    static void noteTooltipIsFullText() throws Throwable {
        AiAgentBridge bridge = new AiAgentBridge();
        String longNote = "cross-account idor suspected on /orders/{id}; "
                + "try 999999, negative ids, and uuid substitution before "
                + "any fuzzing; keep rate low, this host throttles fast; "
                + "probe nested /orders/{id}/items ids in a second pass";
        AiAgentBridge.Item it = item("GET /o HTTP/1.1\r\nHost: api.example.com\r\n\r\n", null);
        bridge.tagSent(it, 2, 9, longNote, "fb");

        String stored = bridge.noteFor(it);           // what the cell shows (truncated visually)
        eq(longNote, stored, "stored note keeps the full text after NOTE_MAX raise");
        eq(longNote, bridge.tooltipFor(it, 7), "note tooltip is the full stored note");

        AiAgentBridge.Item untagged = item("GET /u HTTP/1.1\r\nHost: api.example.com\r\n\r\n", null);
        eq(null, bridge.tooltipFor(untagged, 7), "no note -> no tooltip");
    }

    static void agentTooltipIsFullText() throws Throwable {
        AiAgentBridge bridge = new AiAgentBridge();
        AiAgentBridge.Item it = item("GET /o HTTP/1.1\r\nHost: api.example.com\r\n\r\n", null);
        bridge.tagSent(it, 1, 3, "check this", "20261018-120000-001_api.example.com");

        eq("sent", bridge.tooltipFor(it, 8), "agent tooltip mirrors the agent state");

        bridge.archived.put("20261018-120000-001_api.example.com", Boolean.TRUE);
        bridge.agentReplies.put(3L, "idor confirmed on /orders; details in chat");
        eq("✓ idor confirmed on /orders; details in chat",
                bridge.tooltipFor(it, 8), "agent tooltip is the full reply after consumption");
    }

    static void otherColumnsDoNotStealTooltips() throws Throwable {
        AiAgentBridge bridge = new AiAgentBridge();
        AiAgentBridge.Item it = item("GET /o HTTP/1.1\r\nHost: api.example.com\r\n\r\n", null);
        eq(null, bridge.tooltipFor(it, 0), "ID column leaves default tooltips");
        eq(null, bridge.tooltipFor(it, 4), "Path column leaves default tooltips");
    }
}
