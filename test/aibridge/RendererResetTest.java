package aibridge;

/*
 * Regression tests for BridgeTable cell coloring.
 *
 * Swing shares ONE renderer component per column across rows. A renderer that
 * only sets the background for colored rows but skips it everywhere else leaks
 * the previous batch color onto arbitrary later rows - exactly the "colors most
 * of the page, depends on where the mouse was" bug reported in the field.
 *
 * Stage A locks correct behavior with the stock Metal renderer.
 * Stage B reproduces the leak under a renderer that does not reset backgrounds
 * for unselected rows (simulating Burp's look-and-feel) and asserts the fix.
 * Stage C locks that selection coloring is never painted over.
 *
 * Headless-safe: prepareRenderer only configures a component, it does not paint.
 * Run: java -cp montoya.jar:build:build-test aibridge.RendererResetTest
 */

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.requests.HttpRequest;

import javax.swing.*;
import javax.swing.table.TableCellRenderer;
import java.awt.Color;
import java.awt.Component;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public final class RendererResetTest {

    static int checks = 0;
    static int fails = 0;

    public static void main(String[] args) throws Throwable {
        System.setProperty("java.awt.headless", "true");
        stageA_stockRenderer();
        stageB_leakyRenderer();
        stageC_selectionUntouched();
        System.out.println("checks=" + checks + " fails=" + fails);
        if (fails > 0) {
            System.out.println("FAIL");
            System.exit(1);
        }
        System.out.println("PASS");
    }

    // ------------------------------------------------------------------ fakes

    /** HttpRequest stub whose toByteArray().getBytes() carries the content. */
    static HttpRequest fakeReq(String content) throws Throwable {
        final byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        java.lang.reflect.InvocationHandler byteArrayHandler = (p, m, a) -> {
            if ("getBytes".equals(m.getName())) return bytes;
            if ("length".equals(m.getName())) return bytes.length;
            throw new UnsupportedOperationException(m.getName());
        };
        final ByteArray ba = (ByteArray) ProxyExecutor.proxy(ByteArray.class, byteArrayHandler);
        java.lang.reflect.InvocationHandler requestHandler = (p, m, a) -> {
            if ("toByteArray".equals(m.getName())) return ba;
            throw new UnsupportedOperationException(m.getName());
        };
        return (HttpRequest) ProxyExecutor.proxy(HttpRequest.class, requestHandler);
    }

    /** tiny indirection so both handlers live in one place */
    static final class ProxyExecutor {
        static Object proxy(Class<?> iface, java.lang.reflect.InvocationHandler h) {
            return java.lang.reflect.Proxy.newProxyInstance(
                    StateLoader.loader(),
                    new Class<?>[]{iface}, h);
        }
    }

    static final class StateLoader {
        static ClassLoader loader() { return RendererResetTest.class.getClassLoader(); }
    }

    static AiAgentBridge.Item rowItem(String content) {
        AiAgentBridge.Item it = new AiAgentBridge.Item();
        try {
            it.req = fakeReq(content);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
        return it;
    }

    static void eq(Object expect, Object got, String label) {
        checks++;
        if (expect == null ? got != null : !expect.equals(got)) {
            fails++;
            System.out.println("[" + label + "] expected=\"" + expect + "\" got=\"" + got + "\"");
        }
    }

    /** builds the tab scene: 4 rows, rows 0/2 sent in two batches */
    static Scene scene() {
        Scene s = new Scene();
        s.bridge = new AiAgentBridge();
        s.bridge.batchPalette = AiAgentBridge.PALETTE_LIGHT;
        s.model = new AiAgentBridge.HistoryModel(s.bridge);
        AiAgentBridge.Item s0 = rowItem("req-alpha");
        AiAgentBridge.Item u1 = rowItem("req-beta");
        AiAgentBridge.Item s2 = rowItem("req-gamma");
        AiAgentBridge.Item u3 = rowItem("req-delta");
        s.table = new AiAgentBridge.BridgeTable(s.model, s.bridge);
        s.table.setAutoCreateRowSorter(false);
        List<AiAgentBridge.Item> rows = Arrays.asList(s0, u1, s2, u3);
        s.model.set(rows);
        s.bridge.tagSent(s0, 0, 1, "batch one", "20261018-080000-001_api.example.com");
        s.bridge.tagSent(s2, 1, 2, "batch two", "20261018-080000-002_api.example.com");
        s.defaultBg = s.table.getBackground();
        s.first = AiAgentBridge.PALETTE_LIGHT[0];
        s.second = AiAgentBridge.PALETTE_LIGHT[1];
        return s;
    }

    static final class Scene {
        AiAgentBridge bridge;
        AiAgentBridge.HistoryModel model;
        AiAgentBridge.BridgeTable table;
        Color defaultBg;
        Color first;
        Color second;
    }

    // ----------------------------------------------------------------- stages

    static void stageA_stockRenderer() {
        Scene s = scene();
        TableCellRenderer shared = s.table.getDefaultRenderer(Object.class);
        Color[] snap = new Color[4];
        for (int row = 0; row < 4; row++) {
            // snapshot IMMEDIATELY: the shared renderer component is reused per
            // cell, so its state after later rows does not describe earlier rows
            snap[row] = s.table.prepareRenderer(shared, row, 3).getBackground();
        }
        eq(s.first, snap[0], "A row0 SENT -> batch color 1");
        eq(s.defaultBg, snap[1], "A row1 unsent -> default (no stale leaks)");
        eq(s.second, snap[2], "A row2 SENT -> batch color 2");
        eq(s.defaultBg, snap[3], "A row3 unsent -> default (no stale leaks)");
    }

    /**
     * A renderer that never resets backgrounds between cells, like some L&amp;Fs.
     * Pre-fix (skip-on-null) this leaks MAGENTA onto every later row; post-fix
     * every unselected cell gets an explicit background.
     */
    static void stageB_leakyRenderer() {
        Scene s = scene();
        final JLabel dirty = new JLabel("const-dirty-canvas");
        dirty.setOpaque(true);
        dirty.setBackground(Color.MAGENTA);
        TableCellRenderer leaky = new TableCellRenderer() {
            @Override public Component getTableCellRendererComponent(
                    JTable t, Object value, boolean sel, boolean foc, int r, int c) {
                dirty.setText(value == null ? "" : value.toString());
                return dirty; // backgrounds intentionally never updated
            }
        };
        Color[] snap = new Color[4];
        for (int row = 0; row < 4; row++) {
            snap[row] = s.table.prepareRenderer(leaky, row, 3).getBackground();
        }
        eq(s.first, snap[0], "B row0 SENT -> batch color 1 (overrides dirty canvas)");
        eq(s.defaultBg, snap[1], "B row1 unsent -> default (leaky renderer neutralized)");
        eq(s.second, snap[2], "B row2 SENT -> batch color 2");
        eq(s.defaultBg, snap[3], "B row3 unsent -> default (leaky renderer neutralized)");
    }

    static void stageC_selectionUntouched() {
        Scene s = scene();
        TableCellRenderer shared = s.table.getDefaultRenderer(Object.class);
        s.table.clearSelection();
        s.table.setRowSelectionInterval(0, 0);
        Component sel = s.table.prepareRenderer(shared, 0, 3);
        eq(s.table.getSelectionBackground(), sel.getBackground(), "C selected row keeps selection color");
        s.table.clearSelection();
        Component after = s.table.prepareRenderer(shared, 0, 3);
        eq(s.first, after.getBackground(), "C cleared selection shows batch color again");
    }
}
