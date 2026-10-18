/*
 * AI Agent Bridge - Burp Suite extension
 *
 * Handsoff selected requests (from Proxy history or any message editor)
 * to a filesystem "inbox" that any AI agent running on this machine can
 * read: ~/.ai-inbox/burp/
 *
 * Protocol for agents:
 *   - pending items = *.request.http files in the inbox root
 *   - manifest.jsonl (JSON lines) carries structured metadata per item
 *   - consumed items get moved into archive/ by the agent
 */
package aibridge;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.extension.ExtensionUnloadingHandler;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public final class AiAgentBridge implements BurpExtension {

    static final String TAB_NAME = "AI Bridge";
    static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    static final DateTimeFormatter DISPLAY_TS = DateTimeFormatter.ofPattern("HH:mm:ss");
    static final AtomicLong SEQ = new AtomicLong(1000);

    private MontoyaApi api;
    private Path inboxDir;
    private volatile UiPanel ui;
    private Timer timer;

    // ------------------------------------------------------------------ init

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        api.extension().setName("AI Agent Bridge");

        String home = System.getProperty("user.home");
        inboxDir = Paths.get(setting("inboxDir", home + File.separator + ".ai-inbox" + File.separator + "burp"));
        ensureDirs();
        writeProtocolDoc();
        logEvent("bridge_loaded", "AI Agent Bridge initialized. Inbox: " + inboxDir);

        try {
            api.userInterface().registerContextMenuItemsProvider(new EditorMenu(this));
        } catch (Throwable t) {
            api.logging().logToError("context menu registration failed: " + t);
        }

        final UiPanel panel = new UiPanel(this);
        this.ui = panel;
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                api.userInterface().registerSuiteTab(TAB_NAME, panel.root);
                timer = new Timer(2000, e -> panel.updatePending());
                timer.start();
                panel.refresh();
            }
        });

        api.extension().registerUnloadingHandler(new ExtensionUnloadingHandler() {
            @Override public void extensionUnloaded() {
                if (timer != null) timer.stop();
            }
        });
        api.logging().logToOutput("AI Agent Bridge loaded. Inbox: " + inboxDir);
    }

    // -------------------------------------------------------------- settings

    String setting(String key, String fallback) {
        try {
            String v = api.persistence().extensionData().getString(key);
            return (v == null || v.isEmpty()) ? fallback : v;
        } catch (Throwable t) {
            return fallback;
        }
    }

    Path inbox() { return inboxDir; }

    void setInbox(String p) {
        try {
            Path np = Paths.get(p.trim());
            Files.createDirectories(np);
            Files.createDirectories(np.resolve("archive"));
            inboxDir = np;
            try {
                api.persistence().extensionData().setString("inboxDir", np.toString());
            } catch (Throwable ignored) { }
            writeProtocolDoc();
            logEvent("inbox_moved", np.toString());
            api.logging().logToOutput("inbox moved to " + np);
        } catch (Exception e) {
            api.logging().logToError("setInbox failed: " + e);
        }
    }

    private void ensureDirs() {
        try {
            Files.createDirectories(inboxDir);
            Files.createDirectories(inboxDir.resolve("archive"));
        } catch (IOException e) {
            api.logging().logToError("cannot create inbox dir: " + e);
        }
    }

    // ------------------------------------------------------------ protocol doc

    private void writeProtocolDoc() {
        Path readme = inboxDir.resolve("README.md");
        if (Files.notExists(readme)) {
            try {
                Files.write(readme, PROTOCOL_MD.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) { }
        }
    }

    // ---------------------------------------------------------------- pending

    int pendingCount() {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(inboxDir, "*.request.http")) {
            int n = 0;
            for (Path p : ds) n++;
            return n;
        } catch (Exception e) {
            return -1;
        }
    }

    void archiveAll() {
        File[] files = inboxDir.toFile().listFiles((d, name) ->
                name.endsWith(".request.http") || name.endsWith(".response.http"));
        if (files == null) return;
        int n = 0;
        for (File f : files) {
            try {
                Files.move(f.toPath(), inboxDir.resolve("archive").resolve(f.getName()),
                        StandardCopyOption.REPLACE_EXISTING);
                n++;
            } catch (IOException ignored) { }
        }
        if (ui != null) {
            ui.updatePending();
            ui.setStatus("Archived " + n + " file(s) (marked as read).");
        }
        logEvent("archived", "archived " + n + " item(s) manually from Burp");
    }

    // ------------------------------------------------------------------ send

    void send(List<Item> items, String note) {
        ensureDirs();
        String now = LocalDateTime.now().format(FILE_TS);
        List<String> lines = new ArrayList<String>();
        int ok = 0, fail = 0;
        for (Item it : items) {
            try {
                String seq = String.valueOf(SEQ.incrementAndGet());
                String base = now + "_" + seq + "_" + sanitize(hostOf(it));
                Path reqFile = inboxDir.resolve(base + ".request.http");
                Files.write(reqFile, bytesOf(it.req));

                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("ts", Instant.now().toString());
                m.put("id", it.id > 0 ? it.id : null);
                m.put("file", reqFile.getFileName().toString());
                m.put("host", hostOf(it));
                m.put("port", portOf(it));
                m.put("tls", isTls(it));
                m.put("method", safeMethod(it));
                m.put("path", safePath(it));
                m.put("httpVersion", safeVersion(it));
                m.put("proxyTime", it.time != null ? it.time.toString() : null);

                int respStatus = -1;
                if (it.resp != null) {
                    try { respStatus = it.resp.statusCode(); } catch (Throwable ignored) { }
                    if (includeResponse()) {
                        Path respFile = inboxDir.resolve(base + ".response.http");
                        Files.write(respFile, it.resp.toByteArray().getBytes());
                        m.put("responseFile", respFile.getFileName().toString());
                    }
                }
                m.put("status", respStatus);
                m.put("source", it.source);
                if (note != null && !note.trim().isEmpty()) m.put("note", note.trim());

                lines.add(toJson(m));
                ok++;
            } catch (Throwable t) {
                api.logging().logToError("send failed: " + t);
                fail++;
            }
        }
        appendManifest(lines);
        try {
            Toolkit.getDefaultToolkit().beep();
        } catch (Throwable ignored) { }
        if (ui != null) {
            ui.updatePending();
            ui.setStatus("Sent " + ok + " item(s) to " + inboxDir
                    + (fail > 0 ? " (" + fail + " failed)" : "")
                    + " - now tell your agent: check the burp inbox");
        }
    }

    private boolean includeResponse() {
        return ui == null || ui.includeResp.isSelected();
    }

    // ------------------------------------------------------------- item logic

    /** Proxy history row id exists on newer Burps; fall back gracefully. */
    static int historyId(Object proxyItem) {
        try {
            java.lang.reflect.Method m = proxyItem.getClass().getMethod("id");
            Object r = m.invoke(proxyItem);
            return (Integer) r;
        } catch (Throwable t) {
            return -1;
        }
    }

    Item itemFrom(ProxyHttpRequestResponse h) {
        Item it = new Item();
        it.id = historyId(h);
        try { it.time = h.time(); } catch (Throwable ignored) { }
        try { it.req = h.request(); } catch (Throwable ignored) { }
        try { it.resp = h.response(); } catch (Throwable ignored) { }
        try { it.svc = h.httpService(); } catch (Throwable ignored) { }
        it.source = "proxy-history";
        return it;
    }

    Item itemFrom(HttpRequestResponse h) {
        Item it = new Item();
        it.id = -1;
        try { it.req = h.request(); } catch (Throwable ignored) { }
        try { it.resp = h.response(); } catch (Throwable ignored) { }
        try { it.svc = h.httpService(); } catch (Throwable ignored) { }
        it.source = "message-editor";
        return it;
    }

    String hostOf(Item it) {
        try { return it.svc.host(); } catch (Throwable t) { return "unknown"; }
    }

    int portOf(Item it) {
        try { return it.svc.port(); } catch (Throwable t) { return 0; }
    }

    boolean isTls(Item it) {
        try { return it.svc.secure(); } catch (Throwable t) { return false; }
    }

    String safeMethod(Item it) {
        try { return it.req.method(); } catch (Throwable t) { return "?"; }
    }

    String safePath(Item it) {
        try { String p = it.req.path(); return p == null ? "" : p; } catch (Throwable t) { return "?"; }
    }

    String safeVersion(Item it) {
        try { return it.req.httpVersion(); } catch (Throwable t) { return "?"; }
    }

    byte[] bytesOf(HttpRequest req) {
        if (req == null) return new byte[0];
        try { return req.toByteArray().getBytes(); } catch (Throwable t) { return new byte[0]; }
    }

    int requestLength(Item it) {
        try { return it.req.toByteArray().length(); } catch (Throwable t) { return 0; }
    }

    private static String sanitize(String s) {
        String clean = s.replaceAll("[^A-Za-z0-9.-]", "_");
        if (clean.length() > 60) clean = clean.substring(0, 60);
        return clean;
    }

    // ---------------------------------------------------------------- manifest

    private synchronized void appendManifest(List<String> lines) {
        if (lines.isEmpty()) return;
        try {
            List<String> body = new ArrayList<String>();
            for (String l : lines) body.add(l);
            body.add(""); // trailing newline
            Files.write(inboxDir.resolve("manifest.jsonl"),
                    String.join("\n", body).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            api.logging().logToError("manifest append failed: " + e);
        }
    }

    void logEvent(String type, String message) {
        ensureDirs();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ts", Instant.now().toString());
        m.put("event", type);
        m.put("message", message);
        List<String> one = new ArrayList<String>();
        one.add(toJson(m));
        appendManifest(one);
    }

    // -------------------------------------------------------------- json util

    static String jstr(String s) {
        StringBuilder b = new StringBuilder("\"");
        if (s != null) {
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"':  b.append("\\\""); break;
                    case '\\': b.append("\\\\"); break;
                    case '\n': b.append("\\n");  break;
                    case '\r': b.append("\\r");  break;
                    case '\t': b.append("\\t");  break;
                    default:
                        if (c < 0x20) {
                            b.append(String.format("\\u%04x", (int) c));
                        } else {
                            b.append(c);
                        }
                }
            }
        }
        return b.append('"').toString();
    }

    static String toJson(Map<String, Object> map) {
        StringBuilder b = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) b.append(',');
            first = false;
            b.append(jstr(e.getKey())).append(':');
            Object v = e.getValue();
            if (v == null) {
                b.append("null");
            } else if (v instanceof Number || v instanceof Boolean) {
                b.append(v.toString());
            } else {
                b.append(jstr(v.toString()));
            }
        }
        return b.append('}').toString();
    }

    // ------------------------------------------------------------------ model

    static final class Item {
        int id = -1;
        ZonedDateTime time;
        HttpRequest req;
        HttpResponse resp;
        HttpService svc;
        String source = "proxy-history";
    }

    static final class HistoryModel extends AbstractTableModel {
        final List<Item> rows = new ArrayList<Item>();
        private final String[] cols = {"ID", "Time", "Method", "Host", "Path", "Status", "Len"};

        void set(List<Item> items) {
            synchronized (rows) {
                rows.clear();
                rows.addAll(items);
            }
            fireTableDataChanged();
        }

        Item at(int modelRow) {
            synchronized (rows) {
                return rows.get(modelRow);
            }
        }

        @Override public int getRowCount() { synchronized (rows) { return rows.size(); } }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }

        @Override public Object getValueAt(int r, int c) {
            Item it = at(r);
            switch (c) {
                case 0: return it.id;
                case 1: return it.time != null ? DISPLAY_TS.format(it.time) : "";
                case 2: return safeMethodStatic(it);
                case 3: try { return it.svc.host(); } catch (Throwable t) { return "?"; }
                case 4: return safePathStatic(it);
                case 5: {
                    try {
                        return it.resp != null ? it.resp.statusCode() : "";
                    } catch (Throwable t) { return ""; }
                }
                case 6: {
                    try {
                        int len = it.req.toByteArray().length();
                        if (it.resp != null) len += it.resp.toByteArray().length();
                        return len;
                    } catch (Throwable t) { return 0; }
                }
                default: return "";
            }
        }

        static String safeMethodStatic(Item it) {
            try { return it.req.method(); } catch (Throwable t) { return "?"; }
        }

        static String safePathStatic(Item it) {
            try { String p = it.req.path(); return p == null ? "" : p; } catch (Throwable t) { return "?"; }
        }
    }

    // -------------------------------------------------------------------- UI

    static final class UiPanel {
        final JPanel root = new JPanel(new BorderLayout(6, 6));
        final HistoryModel model = new HistoryModel();
        final JTable table = new JTable(model);
        final JTextField filter = new JTextField(20);
        final JTextField maxRows = new JTextField("3000", 6);
        final JTextField note = new JTextField(18);
        final JCheckBox includeResp = new JCheckBox("Include response", true);
        final JLabel status = new JLabel("Select rows and click Send.");
        final JLabel pending = new JLabel("Pending unread: 0");
        final JTextField pathField;
        final AiAgentBridge bridge;

        UiPanel(AiAgentBridge bridge) {
            this.bridge = bridge;
            pathField = new JTextField(bridge.inbox().toString());

            JToolBar bar = new JToolBar();
            bar.setFloatable(false);
            JButton refresh = new JButton("Refresh history");
            JButton send = new JButton("Send selected");
            JButton open = new JButton("Open inbox");
            JButton archive = new JButton("Mark all read (archive)");
            refresh.addActionListener(e -> refresh());
            send.addActionListener(e -> sendSelected());
            open.addActionListener(e -> openInbox());
            archive.addActionListener(e -> bridge.archiveAll());
            bar.add(refresh);
            bar.add(send);
            bar.addSeparator();
            bar.add(new JLabel("Filter:"));
            bar.add(filter);
            bar.add(new JLabel("Max rows:"));
            bar.add(maxRows);
            bar.add(includeResp);
            bar.addSeparator();
            bar.add(new JLabel("Note:"));
            bar.add(note);
            bar.addSeparator();
            bar.add(open);
            bar.add(archive);

            table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            table.setAutoCreateRowSorter(true);
            table.setFillsViewportHeight(true);
            root.add(bar, BorderLayout.NORTH);

            JPanel pathRow = new JPanel(new BorderLayout(4, 0));
            pathRow.add(new JLabel("Inbox dir:"), BorderLayout.WEST);
            pathRow.add(pathField, BorderLayout.CENTER);
            JButton apply = new JButton("Set");
            apply.addActionListener(e -> {
                bridge.setInbox(pathField.getText());
                pathField.setText(bridge.inbox().toString());
                updatePending();
            });
            pathRow.add(apply, BorderLayout.EAST);

            JPanel south = new JPanel(new BorderLayout(4, 4));
            south.add(pathRow, BorderLayout.NORTH);
            JPanel statusRow = new JPanel(new BorderLayout());
            statusRow.add(status, BorderLayout.WEST);
            statusRow.add(pending, BorderLayout.EAST);
            south.add(statusRow, BorderLayout.SOUTH);

            root.add(new JScrollPane(table), BorderLayout.CENTER);
            root.add(south, BorderLayout.SOUTH);

            // initial column widths
            table.getColumnModel().getColumn(0).setPreferredWidth(50);
            table.getColumnModel().getColumn(1).setPreferredWidth(70);
            table.getColumnModel().getColumn(2).setPreferredWidth(70);
            table.getColumnModel().getColumn(3).setPreferredWidth(180);
            table.getColumnModel().getColumn(4).setPreferredWidth(420);
            table.getColumnModel().getColumn(5).setPreferredWidth(60);
            table.getColumnModel().getColumn(6).setPreferredWidth(80);

            filter.addActionListener(e -> refresh());
        }

        void refresh() {
            final AiAgentBridge b = this.bridge;
            int maxRaw = 3000;
            try { maxRaw = Math.max(1, Integer.parseInt(maxRows.getText().trim())); }
            catch (NumberFormatException ignored) { }
            final int max = maxRaw;
            final String f = filter.getText().trim().toLowerCase();
            new Thread(new Runnable() {
                @Override public void run() {
                    List<Item> out = new ArrayList<Item>();
                    int total = 0;
                    try {
                        List<ProxyHttpRequestResponse> hist = b.api.proxy().history();
                        total = hist.size();
                        int start = Math.max(0, total - max);
                        for (int i = total - 1; i >= start; i--) {
                            ProxyHttpRequestResponse h = hist.get(i);
                            try {
                                Item it = b.itemFrom(h);
                                String hay = safeText(b.hostOf(it)) + " " + safeText(b.safePath(it))
                                        + " " + safeText(b.safeMethod(it));
                                if (f.isEmpty() || hay.toLowerCase().contains(f)) out.add(it);
                            } catch (Throwable ignored) { }
                        }
                    } catch (Throwable t) {
                        b.api.logging().logToError("refresh: " + t);
                    }
                    final List<Item> res = out;
                    final int tot = total;
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override public void run() {
                            model.set(res);
                            setStatus("Loaded " + res.size() + " entry/entries (of " + tot + " in history). Newest first.");
                            updatePending();
                        }
                    });
                }
            }, "ai-bridge-refresh").start();
        }

        String safeText(String s) { return s == null ? "" : s; }

        void sendSelected() {
            int[] viewRows = table.getSelectedRows();
            if (viewRows.length == 0) {
                setStatus("Select at least one row first.");
                return;
            }
            List<Item> items = new ArrayList<Item>();
            for (int v : viewRows) {
                int m = table.convertRowIndexToModel(v);
                items.add(model.at(m));
            }
            String n = note.getText().trim();
            bridge.send(items, n);
            note.setText("");
        }

        void openInbox() {
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(bridge.inbox().toFile());
                    return;
                }
            } catch (Throwable ignored) { }
            try {
                new ProcessBuilder("xdg-open", bridge.inbox().toString()).start();
            } catch (Throwable t) {
                setStatus("Could not open folder: " + bridge.inbox());
            }
        }

        void updatePending() {
            pending.setText("Pending unread: " + bridge.pendingCount());
        }

        void setStatus(String s) { status.setText(" " + s); }
    }

    // ---------------------------------------------------------- context menu

    static final class EditorMenu implements ContextMenuItemsProvider {
        private final AiAgentBridge bridge;

        EditorMenu(AiAgentBridge bridge) { this.bridge = bridge; }

        @Override
        public List<Component> provideMenuItems(ContextMenuEvent event) {
            try {
                List<Component> items = new ArrayList<Component>();
                Optional<MessageEditorHttpRequestResponse> me = event.messageEditorRequestResponse();
                List<HttpRequestResponse> selected;
                try { selected = event.selectedRequestResponses(); }
                catch (Throwable t) { selected = Collections.emptyList(); }
                if (selected == null) selected = Collections.emptyList();

                if (selected.size() > 1) {
                    JMenuItem all = new JMenuItem("Send " + selected.size()
                            + " selected request(s) to AI inbox");
                    final List<HttpRequestResponse> sel = selected;
                    all.addActionListener(e -> bridge.send(toItems(sel), "context-menu"));
                    items.add(all);
                }
                if (me != null && me.isPresent() && me.get().requestResponse() != null) {
                    final HttpRequestResponse hrr = me.get().requestResponse();
                    JMenuItem one = new JMenuItem("Send request to AI inbox");
                    one.addActionListener(e -> {
                        List<Item> single = new ArrayList<Item>();
                        single.add(bridge.itemFrom(hrr));
                        bridge.send(single, "context-menu");
                    });
                    items.add(one);
                }
                return items;
            } catch (Throwable t) {
                bridge.api.logging().logToError("context menu: " + t);
                return Collections.emptyList();
            }
        }

        List<Item> toItems(List<HttpRequestResponse> hrrs) {
            List<Item> out = new ArrayList<Item>();
            for (HttpRequestResponse h : hrrs) {
                try { out.add(bridge.itemFrom(h)); } catch (Throwable ignored) { }
            }
            return out;
        }
    }

    // -------------------------------------------------------------- protocol

    private static final String PROTOCOL_MD =
        "# Burp -> AI Agent Inbox\n\n"
        + "Handoff point between Burp Suite (extension \"AI Agent Bridge\") and any AI agent\n"
        + "running on this machine (OpenCode / Claude / any model with filesystem access).\n"
        + "No network, no daemon - just files, so every session and every model can read it.\n\n"
        + "## For the human\n"
        + "1. In Burp: open the \"AI Bridge\" tab, refresh, select rows, \"Send selected\".\n"
        + "   (Or right-click any request viewer -> \"Send request to AI inbox\".)\n"
        + "2. In the agent chat: \"check the burp inbox and test what's there\".\n\n"
        + "## For any AI agent (you)\n"
        + "1. Pending items = `*.request.http` files in THIS directory (not in `archive/`).\n"
        + "   `manifest.jsonl` (JSON lines, appended oldest->newest) has per-item metadata:\n"
        + "   host, port, tls, method, path, httpVersion, status, responseFile, note.\n"
        + "2. Read the manifest, then read the referenced request files (raw HTTP as Burp\n"
        + "   saw it; HTTP/2 messages are tagged with httpVersion in the manifest).\n"
        + "3. Then act: analyze, replay, fuzz. If Burp MCP tools are available use\n"
        + "   send_http1_request/send_http2_request with host/port/tls from the manifest,\n"
        + "   otherwise curl works too.\n"
        + "4. When done with an item, move BOTH its `.request.http` and `.response.http`\n"
        + "   into `archive/` and do NOT edit manifest.jsonl. The extension counts\n"
        + "   pending = request files at the root, so archiving drops the counter.\n"
        + "5. Files contain the complete raw messages, no truncation. Binary bodies stay raw.\n";
}
