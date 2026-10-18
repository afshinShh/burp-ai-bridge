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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class AiAgentBridge implements BurpExtension {

    static final String TAB_NAME = "AI Bridge";
    static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    static final DateTimeFormatter DISPLAY_TS = DateTimeFormatter.ofPattern("HH:mm:ss");
    static final AtomicLong SEQ = new AtomicLong(1000);

    MontoyaApi api; // package-visible: headless tests inject fakes
    Path inboxDir; // package-visible: headless tests wire a temp directory in
    private volatile UiPanel ui;
    private Timer timer;

    // --------------------------------------------------- sent-state tracking

    /** One row that has been handed to the inbox, keyed by request-content hash. */
    static final class SentRec {
        final String hash;
        final int colorIdx;
        final long batch;
        final String note;
        /** inbox file base name (no extension) written for this item; v1 state left it blank. */
        final String file;
        SentRec(String hash, int colorIdx, long batch, String note, String file) {
            this.hash = hash;
            this.colorIdx = colorIdx;
            this.batch = batch;
            this.note = note == null ? "" : note;
            this.file = file == null ? "" : file;
        }
    }

    static final String STATE_KEY = "sentState-v2";
    static final int STATE_CAP = 1500;
    static final int NOTE_MAX = 1000;

    /** hash -> record, for filter lookups on EDT (refresh thread) and render. */
    final ConcurrentHashMap<String, SentRec> sentIndex = new ConcurrentHashMap<String, SentRec>();
    /** insertion order, oldest first; powers the cap trim. */
    final List<SentRec> sentOrder = Collections.synchronizedList(new ArrayList<SentRec>());
    /** base names (no extension) whose request file now lives in archive/ — consumed. */
    final ConcurrentHashMap<String, Boolean> archived = new ConcurrentHashMap<String, Boolean>();
    /** incremented once per send action: items in one action share a batch/color. */
    final AtomicLong batchSeq = new AtomicLong(1);
    final AtomicInteger colorSeq = new AtomicInteger(0);

    private static Color[] hexColors(String... hexes) {
        Color[] out = new Color[hexes.length];
        for (int i = 0; i < hexes.length; i++) out[i] = Color.decode(hexes[i]);
        return out;
    }

    /** Hand-picked pastels that stay readable on the light Burp theme. */
    static final Color[] PALETTE_LIGHT = hexColors(
            "#FFF3BF", "#D7F0FA", "#DCF5DC", "#FFE4D6",
            "#E8E0F7", "#FFE1E1", "#E6F0FF", "#F5F0DB");
    /** Dark earthy tints for the dark Burp theme (white text stays legible). */
    static final Color[] PALETTE_DARK = hexColors(
            "#463B15", "#14424F", "#16401F", "#4A2418",
            "#2C2450", "#4A1A24", "#1A2A50", "#3D3A19");

    volatile Color[] batchPalette = PALETTE_LIGHT;
    /** batch -> agent summary from results.jsonl; bounded, access-ordered LRU. */
    final Map<Long, String> agentReplies = new LinkedHashMap<Long, String>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, String> eldest) {
            return size() > RESULTS_CAP;
        }
    };

    // -------------------------------------------------------------- agent replies

    static final String RESULTS_FILE = "results.jsonl";
    static final int RESULTS_CAP = 50;
    /** byte offset (parse position) of the last fully-considered results content */
    volatile long resultsOffset = 0;
    /** byte index just after the last COMPLETE newline parsed — anchor for replacement detection */
    volatile long resultsAnchorPos = 0;
    /** the up-to-16 bytes just before that anchor; changed bytes mean the file was replaced */
    volatile byte[] resultsAnchorBytes = new byte[0];

    // ----------------------------------------------------------------- options

    static final String OPT_INCLUDE_RESP = "opt-includeResp";
    static final String OPT_HIDE_SENT = "opt-hideSent";
    static final String OPT_IN_SCOPE_ONLY = "opt-inScopeOnly";

    volatile boolean optIncludeResp = true;
    volatile boolean optHideSent = false;
    volatile boolean optInScopeOnly = false;

    /**
     * Agents append JSON lines {ts, batch, summary, by} to results.jsonl; this
     * tails them. Torn or malformed lines are skipped without crashing. If the
     * historical bytes no longer match the anchor region, the file was replaced
     * or truncated: reset the watermark and drop stale batch summaries.
     */
    boolean readResults() {
        Path f = inboxDir.resolve(RESULTS_FILE);
        byte[] tail;
        try {
            tail = Files.readAllBytes(f);
        } catch (Throwable t) {
            return false; // no file yet: leave watermark, wait for creation
        }
        long off = resultsOffset;
        boolean replaced = false;
        if (off > tail.length || resultsAnchorPos > tail.length) {
            replaced = true; // file shrank under us
        } else if (off > 0 && resultsAnchorBytes.length > 0) {
            byte[] probe = new byte[resultsAnchorBytes.length];
            int a = (int) Math.max(0, resultsAnchorPos - probe.length);
            System.arraycopy(tail, a, probe, 0, probe.length);
            replaced = !java.util.Arrays.equals(probe, resultsAnchorBytes);
        }
        if (replaced) {
            synchronized (agentReplies) {
                agentReplies.clear();
            }
            off = 0;
            resultsAnchorPos = 0;
            resultsAnchorBytes = new byte[0];
        }

        int searchFrom = (int) Math.min(off, (long) Integer.MAX_VALUE);
        long anchor = resultsAnchorPos;
        int consumed = searchFrom;
        Map<Long, String> additions = new LinkedHashMap<Long, String>();
        for (int i = searchFrom; i <= tail.length; ) {
            int nl = indexOfNewline(tail, i);
            int end = nl < 0 ? tail.length : nl;
            String line = new String(tail, i, end - i, StandardCharsets.UTF_8).trim();
            if (nl < 0) {
                if (!line.isEmpty()) {
                    consumed = i; // incomplete tail line: retry from here next tick
                } else {
                    consumed = tail.length;
                    anchor = tail.length;
                }
                break;
            }
            Long b = extractBatchNumber(line);
            if (b != null) {
                String sum = compactNoteStatic(extractField(line, "summary"));
                if (!sum.isEmpty()) additions.put(b, sum);
            }
            consumed = end + 1;
            anchor = end + 1;
            i = nl + 1;
        }
        if (!additions.isEmpty()) {
            synchronized (agentReplies) {
                for (Map.Entry<Long, String> e : additions.entrySet()) {
                    agentReplies.put(e.getKey(), e.getValue());
                }
            }
        }
        resultsOffset = (long) consumed;
        if (anchor > 0) {
            int len = (int) Math.min(16L, anchor);
            byte[] region = new byte[len];
            System.arraycopy(tail, (int) (anchor - len), region, 0, len);
            resultsAnchorPos = anchor;
            resultsAnchorBytes = region;
        }
        return !additions.isEmpty();
    }

    private static int indexOfNewline(byte[] b, int from) {
        for (int i = from; i < b.length; i++) {
            if (b[i] == '\n') return i;
        }
        return -1;
    }

    /** numeric batch: e.g. "batch":911 parses as 911; absent/invalid -> null */
    static Long extractBatchNumber(String line) {
        try {
            int at = line.indexOf("\"batch\"");
            if (at < 0) return null;
            int colon = line.indexOf(':', at + 7);
            if (colon < 0) return null;
            int s = colon + 1;
            while (s < line.length() && line.charAt(s) == ' ') s++;
            int e = s;
            while (e < line.length() && (Character.isDigit(line.charAt(e)) || line.charAt(e) == '-')) e++;
            if (e == s) return null;
            return Long.parseLong(line.substring(s, e));
        } catch (Throwable t) {
            return null;
        }
    }

    /** values are plain, agent-controlled strings; extractor tolerates escapes minimally */
    static String extractField(String line, String key) {
        try {
            int at = line.indexOf("\"" + key + "\"");
            if (at < 0) return null;
            int colon = line.indexOf(':', at + key.length() + 2);
            if (colon < 0) return null;
            int q1 = line.indexOf('"', colon + 1);
            if (q1 < 0) return null; // numeric field
            int q2 = line.indexOf('"', q1 + 1);
            return line.substring(q1 + 1, q2 < 0 ? line.length() : q2);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ init

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        api.extension().setName("AI Agent Bridge");

        String home = System.getProperty("user.home");
        inboxDir = Paths.get(setting("inboxDir", home + File.separator + ".ai-inbox" + File.separator + "burp"));
        ensureDirs();
        hardenInbox();
        writeProtocolDoc();
        detectPalette();
        loadOptions();
        loadSentState();
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
                timer = new Timer(2000, e -> {
                    boolean consumed = refreshArchived();
                    boolean replied = readResults();
                    panel.updatePending();
                    if (consumed || replied) panel.repaintRows();
                });
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
            hardenInbox();
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

    // ------------------------------------------------------------ sent-state

    /** Pick the highlight palette that fits the current Burp theme. */
    void detectPalette() {
        try {
            Color bg = UIManager.getColor("Table.background");
            boolean dark = false;
            if (bg != null) {
                double lum = 0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue();
                dark = lum < 128;
            }
            batchPalette = dark ? PALETTE_DARK : PALETTE_LIGHT;
        } catch (Throwable t) {
            batchPalette = PALETTE_LIGHT;
        }
    }

    /** SHA-1 of the raw request bytes; stable across restarts, cached on the item. */
    String hashOf(Item it) {
        if (it == null || it.req == null) return null;
        if (it.hash != null) return it.hash;
        try {
            byte[] raw = it.req.toByteArray().getBytes();
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(raw);
            StringBuilder b = new StringBuilder(40);
            for (byte x : d) b.append(String.format("%02x", x));
            it.hash = b.toString();
        } catch (Throwable t) {
            return null;
        }
        return it.hash;
    }

    /** Batch highlight color for a row, or null if it was never sent. */
    Color colorFor(Item it) {
        String h = hashOf(it);
        if (h == null) return null;
        SentRec r = sentIndex.get(h);
        if (r == null) return null;
        Color[] pal = batchPalette;
        if (pal.length == 0) return null;
        return pal[Math.floorMod(r.colorIdx, pal.length)];
    }

    String noteFor(Item it) {
        String h = hashOf(it);
        if (h == null) return "";
        SentRec r = sentIndex.get(h);
        return r == null ? "" : r.note;
    }

    /** Mark an item as handed off; called once per sent item inside send(). */
    void tagSent(Item it, int colorIdx, long batch, String note, String base) {
        String h = hashOf(it);
        if (h == null) return;
        SentRec r = new SentRec(h, colorIdx, batch, compactNoteStatic(note), base == null ? "" : base);
        sentIndex.put(h, r);
        sentOrder.add(r);
        persistSentState();
    }

    void loadSentState() {
        String blob = null;
        try {
            blob = api.persistence().extensionData().getString(STATE_KEY);
        } catch (Throwable ignored) { }
        List<String[]> es = decodeSentState(blob);
        synchronized (sentOrder) {
            for (String[] e : es) {
                SentRec r = recFrom(e);
                if (r != null) {
                    sentOrder.add(r);
                    sentIndex.put(r.hash, r);
                }
            }
        }
        if (!es.isEmpty()) {
            api.logging().logToOutput("AI Bridge: restored " + es.size() + " sent-state entries");
        }
    }

    static SentRec recFrom(String[] e) {
        try {
            String file = e.length > 4 ? e[4] : ""; // v1 state had no file field
            return new SentRec(e[0], Integer.parseInt(e[1]), Long.parseLong(e[2]),
                    compactNoteStatic(e[3]), file);
        } catch (Throwable t) {
            return null;
        }
    }

    void persistSentState() {
        if (api == null) return; // headless/unit-test construction
        List<String[]> snap;
        synchronized (sentOrder) {
            snap = new ArrayList<String[]>(sentOrder.size());
            for (SentRec r : sentOrder) {
                snap.add(new String[]{r.hash, String.valueOf(r.colorIdx),
                        String.valueOf(r.batch), r.note, r.file});
            }
        }
        List<String[]> capped = capEntries(snap, STATE_CAP);
        try {
            api.persistence().extensionData().setString(STATE_KEY, encodeSentState(capped));
        } catch (Throwable t) {
            api.logging().logToError("persist sent-state failed: " + t);
            return;
        }
        if (capped.size() < snap.size()) {
            synchronized (sentOrder) {
                sentOrder.clear();
                sentIndex.clear();
                for (String[] e : capped) {
                    SentRec r = recFrom(e);
                    if (r != null) {
                        sentOrder.add(r);
                        sentIndex.put(r.hash, r);
                    }
                }
            }
        }
    }

    /** Clear everything: colors, notes and the hide-sent filter history. */
    void forgetSent() {
        synchronized (sentOrder) {
            sentOrder.clear();
            sentIndex.clear();
        }
        archived.clear();
        try {
            api.persistence().extensionData().setString(STATE_KEY, "");
        } catch (Throwable ignored) { }
        logEvent("sent_state_cleared", "sent-state cleared from tab");
        if (ui != null) {
            ui.refresh();
            ui.setStatus("Sent-state cleared - previously sent rows are untagged again.");
        }
    }

    // -------------------------------------------------------------- consumption

    /**
     * One directory listing of archive/ per tick: every base name present there
     * as *.request.http was consumed by an agent. Cheap regardless of history
     * size; the timer repaints only when the set changed.
     */
    boolean refreshArchived() {
        ConcurrentHashMap<String, Boolean> next = new ConcurrentHashMap<String, Boolean>();
        try (DirectoryStream<Path> ds =
                     Files.newDirectoryStream(inboxDir.resolve("archive"), "*.request.http")) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                next.put(name.substring(0, name.length() - ".request.http".length()), Boolean.TRUE);
            }
        } catch (Throwable ignored) { }
        boolean changed = next.size() != archived.size();
        if (!changed) {
            for (String k : next.keySet()) {
                if (!archived.containsKey(k)) {
                    changed = true;
                    break;
                }
            }
        }
        archived.clear();
        archived.putAll(next);
        return changed;
    }

    boolean isConsumed(String base) {
        return base != null && !base.isEmpty() && archived.containsKey(base);
    }

    // ------------------------------------------------------------------ options

    /** persist the three checkbox states so a restart keeps the workflow */
    void saveOptions() {
        if (api == null) return;
        try {
            burp.api.montoya.persistence.PersistedObject po = api.persistence().extensionData();
            po.setBoolean(OPT_INCLUDE_RESP, optIncludeResp);
            po.setBoolean(OPT_HIDE_SENT, optHideSent);
            po.setBoolean(OPT_IN_SCOPE_ONLY, optInScopeOnly);
        } catch (Throwable t) {
            api.logging().logToError("saveOptions failed: " + t);
        }
    }

    void loadOptions() {
        if (api == null) return;
        try {
            burp.api.montoya.persistence.PersistedObject po = api.persistence().extensionData();
            Boolean a = po.getBoolean(OPT_INCLUDE_RESP);
            if (a != null) optIncludeResp = a;
            Boolean b = po.getBoolean(OPT_HIDE_SENT);
            if (b != null) optHideSent = b;
            Boolean c = po.getBoolean(OPT_IN_SCOPE_ONLY);
            if (c != null) optInScopeOnly = c;
        } catch (Throwable t) {
            api.logging().logToError("loadOptions failed: " + t);
        }
    }

    /** restrict the inbox to its owner where the filesystem supports POSIX modes */
    void hardenInbox() {
        try {
            if (!inboxDir.getFileSystem().supportedFileAttributeViews().contains("posix")) return;
            Files.setPosixFilePermissions(inboxDir,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        } catch (Throwable t) {
            if (api != null) api.logging().logToError("hardenInbox failed: " + t);
        }
    }

    /**
     * Agent-loop text for the Agent column: what happened after the hand-off.
     * "sent" while pending, "✓" once consumed, and rich when the agent replied:
     * "✓ <summary>" (summary implies reply; ✓ prefix communicates consumption).
     */
    String agentStateFor(Item it) {
        String h = hashOf(it);
        if (h == null) return "";
        SentRec r = sentIndex.get(h);
        if (r == null) return "";
        boolean consumed = !r.file.isEmpty() && isConsumed(r.file);
        String reply;
        synchronized (agentReplies) {
            reply = agentReplies.get(r.batch);
        }
        if (reply != null && !reply.isEmpty()) {
            return (consumed ? "✓ " : "") + reply;
        }
        if (consumed) return "✓";
        return "sent";
    }

    /**
     * Full-text hover for cells whose column truncates visually: the note and
     * the agent state. Other columns inherit JTable's default tooltip.
     */
    String tooltipFor(Item it, int modelCol) {
        if (modelCol == 7) {
            String n = noteFor(it);
            return n.isEmpty() ? null : n;
        }
        if (modelCol == 8) {
            String s = agentStateFor(it);
            return s.isEmpty() ? null : s;
        }
        return null;
    }

    // ------------------------------------------------------------ sent-state codec

    static String compactNoteStatic(String note) {
        if (note == null) return "";
        String clean = note.replaceAll("\\s+", " ").trim();
        return clean.length() > NOTE_MAX ? clean.substring(0, NOTE_MAX) : clean;
    }

    /**
     * Tab-separated lines: hash \t colorIdx \t batch \t note \t file-base \n.
     * v1 state wrote four fields; decoding tolerates that (file stays blank).
     */
    static String encodeSentState(List<String[]> entries) {
        StringBuilder sb = new StringBuilder();
        for (String[] e : entries) {
            sb.append(e[0]).append('\t').append(e[1]).append('\t').append(e[2])
              .append('\t').append(compactNoteStatic(e[3]));
            if (e.length > 4 && e[4] != null) sb.append('\t').append(e[4]);
            sb.append('\n');
        }
        return sb.toString();
    }

    static List<String[]> decodeSentState(String blob) {
        List<String[]> out = new ArrayList<String[]>();
        if (blob == null || blob.trim().isEmpty()) return out;
        for (String line : blob.split("\n", -1)) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\t", -1);
            if (parts.length < 4) continue;
            String file = parts.length > 4 ? parts[4] : "";
            out.add(new String[]{parts[0], parts[1], parts[2], compactNoteStatic(parts[3]), file});
        }
        return out;
    }

    static List<String[]> capEntries(List<String[]> in, int max) {
        if (max <= 0) return new ArrayList<String[]>();
        if (in.size() <= max) return in;
        return new ArrayList<String[]>(in.subList(in.size() - max, in.size()));
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

    String send(List<Item> items, String note) {
        ensureDirs();
        String now = LocalDateTime.now().format(FILE_TS);
        final long batch = batchSeq.getAndIncrement();
        final int colorIdx = colorSeq.getAndIncrement();
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
                m.put("batch", batch);

                lines.add(toJson(m));
                tagSent(it, colorIdx, batch, note, base);
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
        return "Sent " + ok + " item(s) to " + inboxDir
                + (fail > 0 ? " (" + fail + " failed)" : "")
                + " - now tell your agent: check the burp inbox";
    }

    /**
     * Off-EDT hand-off for all callers (tab button and context menu share it):
     * heavy disk I/O runs in a worker thread, UI updates return via the EDT.
     * BApp acceptance criterion 5 - responsiveness.
     */
    void sendAsync(List<Item> items, String note) {
        new Thread(() -> {
            String msg;
            try {
                msg = send(items, note);
            } catch (Throwable t) {
                msg = "Send failed: " + t;
            }
            final String m = msg;
            SwingUtilities.invokeLater(() -> {
                if (ui != null) {
                    ui.setStatus(m);
                    ui.updatePending();
                    ui.refresh();
                }
            });
        }, "ai-bridge-send").start();
    }

    private boolean includeResponse() {
        return optIncludeResp;
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

    /**
     * URL as Burp's Target scope sees it, rebuilt from parts, because proxy
     * rows do not always carry a full URL. Normalized: default ports implicit,
     * empty path becomes "/".
     */
    static String urlFor(Item it) {
        String host = "?";
        try { host = it.svc.host(); } catch (Throwable ignored) { }
        if (host == null || host.isEmpty()) host = "?";
        int port = 0;
        try { port = it.svc.port(); } catch (Throwable ignored) { }
        boolean tls = false;
        try { tls = it.svc.secure(); } catch (Throwable ignored) { }
        String path = "/";
        try {
            if (it.req != null && it.req.path() != null && !it.req.path().isEmpty()) {
                path = it.req.path();
            }
        } catch (Throwable ignored) { }
        String scheme = tls ? "https" : "http";
        boolean defaultPort = (tls && port == 443) || (!tls && port == 80);
        return scheme + "://" + host + (port > 0 && !defaultPort ? ":" + port : "") + path;
    }

    /** Target-scope membership for a row; unreachable APIs stay visible. */
    boolean inScope(Item it) {
        if (it == null || it.req == null) return true;
        try {
            return api.scope().isInScope(it.req.url());
        } catch (Throwable t) {
            try {
                return api.scope().isInScope(urlFor(it));
            } catch (Throwable t2) {
                return true;
            }
        }
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

    // ------------------------------------------------------------------------- rotation

    static final long ROTATE_BYTES = 2L * 1024 * 1024;
    static final long ROTATE_LINES = 5000;
    static final int ROTATE_TAIL_LINES = 200;

    /** true when manifest.jsonl has outgrown the thresholds and should be rotated */
    static boolean shouldRotate(long sizeBytes, long lineCount) {
        return sizeBytes >= ROTATE_BYTES || lineCount >= ROTATE_LINES;
    }

    /**
     * Move the full manifest into archive/manifest-<stamp>.jsonl atomically, then
     * seed a fresh live manifest with the last ROTATE_TAIL_LINES history lines and
     * a rotated event naming the archive. A raced append either lands in the
     * archive (written before rename) or is re-appended by its writer; the tail
     * and marker guarantee agents always see a coherent live file.
     */
    void rotateManifest() {
        try {
            Path live = inboxDir.resolve("manifest.jsonl");
            if (Files.notExists(live)) return;
            List<String> history = Files.readAllLines(live, StandardCharsets.UTF_8);
            if (history.isEmpty()) {
                // blank/socket file: leave as-is
                Files.write(live, new byte[0]);
                return;
            }
            String stamp = LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS-'rot'"));
            Path archived = inboxDir.resolve("archive").resolve("manifest-" + stamp + ".jsonl");
            Files.createDirectories(inboxDir.resolve("archive"));
            Files.write(archived, (String.join("\n", history) + "\n").getBytes(StandardCharsets.UTF_8));

            int from = Math.max(0, history.size() - ROTATE_TAIL_LINES);
            List<String> tail = new ArrayList<String>(history.subList(from, history.size()));
            Map<String, Object> ev = new LinkedHashMap<String, Object>();
            ev.put("ts", Instant.now().toString());
            ev.put("event", "rotated");
            ev.put("message", archiveRelPath(archived));
            tail.add(toJson(ev));
            tail.add("");
            Path tmp = inboxDir.resolve("manifest.jsonl.next");
            Files.write(tmp, String.join("\n", tail).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, live,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Throwable notAtomic) {
                Files.move(tmp, live, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            if (api != null) api.logging().logToError("rotate failed: " + t);
            else t.printStackTrace();
        }
    }

    /** stable relative address agents can resolve from the inbox root */
    static String archiveRelPath(Path archived) {
        String name = archived.getFileName().toString();
        return "archive/" + name;
    }

    /** message field of a manifest event line; null when absent */
    static String extractEventMessage(String eventLine) {
        return extractField(eventLine, "message");
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
            Path live = inboxDir.resolve("manifest.jsonl");
            long size = 0;
            long count = 0;
            try {
                size = (long) Files.size(live);
                count = countLines(live);
            } catch (Throwable ignored) { }
            Files.write(live,
                    String.join("\n", body).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (shouldRotate(size + joinedSize(body), count + body.size())) {
                rotateManifest();
            }
        } catch (IOException e) {
            api.logging().logToError("manifest append failed: " + e);
        }
    }

    private static long joinedSize(List<String> body) {
        long n = 0;
        for (String s : body) n += s.length() + 1;
        return n;
    }

    static long countLines(Path f) throws Exception {
        long n = 0;
        try (var reader = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            while (reader.readLine() != null) n++;
        }
        return n;
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
        /** lazy SHA-1 of the request bytes; caches the hashOf() result. */
        volatile String hash;
    }

    static final class HistoryModel extends AbstractTableModel {
        final List<Item> rows = new ArrayList<Item>();
        private final String[] cols = {"ID", "Time", "Method", "Host", "Path", "Status", "Len", "Note", "Agent"};
        private final AiAgentBridge bridge;

        HistoryModel(AiAgentBridge bridge) { this.bridge = bridge; }

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
                case 7: return bridge.noteFor(it);
                case 8: return bridge.agentStateFor(it);
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

    /** JTable that paints the batch color of sent rows without touching selection. */
    static final class BridgeTable extends JTable {
        final AiAgentBridge bridge;

        BridgeTable(HistoryModel model, AiAgentBridge bridge) {
            super(model);
            this.bridge = bridge;
        }

        /** full-text hover for the Note and Agent columns; others keep default */
        @Override public String getToolTipText(java.awt.event.MouseEvent e) {
            try {
                int v = rowAtPoint(e.getPoint());
                int c = columnAtPoint(e.getPoint());
                if (v >= 0 && c >= 0) {
                    Item it = ((HistoryModel) getModel()).at(convertRowIndexToModel(v));
                    return bridge.tooltipFor(it, convertColumnIndexToModel(c));
                }
            } catch (Throwable ignored) { }
            return super.getToolTipText(e);
        }

        @Override public Component prepareRenderer(
                javax.swing.table.TableCellRenderer renderer, int row, int column) {
            Component c = super.prepareRenderer(renderer, row, column);
            try {
                // cell text originates from raw HTTP; Swing parses "<html"-led
                // values as rich text - switch that off on every handed-back
                // component (spoofing surface, BApp untrusted-content criterion)
                ((JComponent) c).putClientProperty("html.disable", Boolean.TRUE);
                if (!isRowSelected(row)) {
                    int m = convertRowIndexToModel(row);
                    Color bg = bridge.colorFor(((HistoryModel) getModel()).at(m));
                    // ALWAYS assign: Swing reuses one renderer component per column,
                    // so a skip here would leak the previous cell's background onto
                    // whatever rows are painted next (viewport/scroll dependent).
                    c.setBackground(bg != null ? bg : getBackground());
                }
            } catch (Throwable ignored) { }
            return c;
        }
    }

    // -------------------------------------------------------------------- UI

    /**
     * Bottom pane of the tab: the full raw request/response of the selected
     * row. Rendering is capped for huge bodies (the EDT must stay responsive
     * on multi-megabyte responses); the disk file in the inbox stays complete.
     */
    static final class ItemViewer extends JTabbedPane {
        static final int MAX_CHARS = 400_000;
        private final JTextArea reqArea = textArea();
        private final JTextArea respArea = textArea();

        ItemViewer() {
            setTabLayoutPolicy(SCROLL_TAB_LAYOUT);
            addTab("Request", new JScrollPane(reqArea));
            addTab("Response", new JScrollPane(respArea));
            clear();
        }

        private static JTextArea textArea() {
            JTextArea a = new JTextArea();
            a.setEditable(false);
            a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            return a;
        }

        void showItem(Item it) {
            String req;
            try {
                req = it.req == null ? "" : new String(
                        it.req.toByteArray().getBytes(), StandardCharsets.UTF_8);
            } catch (Throwable t) {
                req = "(unreadable request)";
            }
            apply(reqArea, req);
            String resp;
            if (it.resp == null) {
                resp = "(response not included)";
            } else {
                try {
                    resp = new String(
                            it.resp.toByteArray().getBytes(), StandardCharsets.UTF_8);
                } catch (Throwable t) {
                    resp = "(unreadable response)";
                }
            }
            apply(respArea, resp);
        }

        void clear() {
            apply(reqArea, "");
            apply(respArea, "");
        }

        private static void apply(JTextArea a, String s) {
            if (s == null) s = "";
            a.setText(s.length() > MAX_CHARS
                    ? s.substring(0, MAX_CHARS) + "\n\n... [display truncated]"
                    : s);
            a.setCaretPosition(0);
        }

        String requestText() { return reqArea.getText(); }
        String responseText() { return respArea.getText(); }
    }

    static final class UiPanel {
        final JPanel root = new JPanel(new BorderLayout(6, 6));
        final HistoryModel model;
        final BridgeTable table;
        final JTextField filter = new JTextField(20);
        final JTextField maxRows = new JTextField("3000", 6);
        final JTextField note = new JTextField(18);
        final JCheckBox includeResp = new JCheckBox("Include response");
        final JCheckBox hideSent = new JCheckBox("Hide sent");
        final JCheckBox inScopeOnly = new JCheckBox("In scope only");
        final JButton forgetSentBtn = new JButton("Forget sent");
        final JLabel status = new JLabel("Select rows and click Send.");
        final JLabel pending = new JLabel("Pending unread: 0");
        final JTextField pathField;
        final AiAgentBridge bridge;
        final ItemViewer viewer = new ItemViewer();

        UiPanel(AiAgentBridge bridge) {
            this.bridge = bridge;
            model = new HistoryModel(bridge);
            table = new BridgeTable(model, bridge);
            pathField = new JTextField(bridge.inbox().toString());
            // flags were loaded from persistence before this panel exists
            includeResp.setSelected(bridge.optIncludeResp);
            hideSent.setSelected(bridge.optHideSent);
            inScopeOnly.setSelected(bridge.optInScopeOnly);

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
            bar.add(hideSent);
            bar.add(inScopeOnly);
            forgetSentBtn.setToolTipText(
                    "Drop colors/notes/the sent-memory so previously sent rows can be sent again");
            forgetSentBtn.addActionListener(e -> bridge.forgetSent());
            bar.add(forgetSentBtn);
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

            root.add(bar, BorderLayout.NORTH);

            // detail: preview pane under the table, opens on row selection;
            // the viewer keeps the top side dominant and stays expandable
            javax.swing.JSplitPane split = new javax.swing.JSplitPane(
                    javax.swing.JSplitPane.VERTICAL_SPLIT,
                    new JScrollPane(table), viewer);
            split.setResizeWeight(0.75);
            split.setOneTouchExpandable(true);
            split.setBorder(null);
            viewer.setPreferredSize(new Dimension(100, 220));
            root.add(split, BorderLayout.CENTER);
            root.add(south, BorderLayout.SOUTH);

            // initial column widths
            table.getColumnModel().getColumn(0).setPreferredWidth(50);
            table.getColumnModel().getColumn(1).setPreferredWidth(70);
            table.getColumnModel().getColumn(2).setPreferredWidth(70);
            table.getColumnModel().getColumn(3).setPreferredWidth(180);
            table.getColumnModel().getColumn(4).setPreferredWidth(420);
            table.getColumnModel().getColumn(5).setPreferredWidth(60);
            table.getColumnModel().getColumn(6).setPreferredWidth(80);
            table.getColumnModel().getColumn(7).setPreferredWidth(160);
            table.getColumnModel().getColumn(8).setPreferredWidth(150);

            filter.addActionListener(e -> refresh());

            // selection drives the preview pane; clearing empties it
            table.getSelectionModel().addListSelectionListener(e -> {
                if (e.getValueIsAdjusting()) return;
                try {
                    int v = table.getSelectedRow();
                    if (v < 0) {
                        viewer.clear();
                        return;
                    }
                    int m = table.convertRowIndexToModel(v);
                    viewer.showItem(model.at(m));
                } catch (Throwable ignored) { }
            });

            // double-click opens the raw file on disk for rows already handed off
            table.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (e.getClickCount() != 2) return;
                    try {
                        int v = table.getSelectedRow();
                        if (v < 0) return;
                        int m = table.convertRowIndexToModel(v);
                        Item it = model.at(m);
                        SentRec rec = bridge.sentIndex.get(bridge.hashOf(it));
                        if (rec != null && !rec.file.isEmpty()) {
                            java.io.File f = bridge.inbox()
                                    .resolve(rec.file + ".request.http").toFile();
                            if (f.exists() && java.awt.Desktop.isDesktopSupported()) {
                                java.awt.Desktop.getDesktop().open(f);
                            }
                        }
                    } catch (Throwable ignored) { }
                }
            });
            hideSent.addActionListener(e -> {
                bridge.optHideSent = hideSent.isSelected();
                bridge.saveOptions();
                refresh();
            });
            inScopeOnly.addActionListener(e -> {
                bridge.optInScopeOnly = inScopeOnly.isSelected();
                bridge.saveOptions();
                refresh();
            });
            includeResp.addActionListener(e -> {
                bridge.optIncludeResp = includeResp.isSelected();
                bridge.saveOptions();
            });
            inScopeOnly.setToolTipText("Show only requests inside Burp's Target Scope (Target -> Scope)");
        }

        void refresh() {
            final AiAgentBridge b = this.bridge;
            int maxRaw = 3000;
            try { maxRaw = Math.max(1, Integer.parseInt(maxRows.getText().trim())); }
            catch (NumberFormatException ignored) { }
            final int max = maxRaw;
            final String f = filter.getText().trim().toLowerCase();
            final boolean hide = hideSent.isSelected();
            final boolean scopeOnly = inScopeOnly.isSelected();
            new Thread(new Runnable() {
                @Override public void run() {
                    List<Item> out = new ArrayList<Item>();
                    final int[] hidden = {0};
                    final int[] oos = {0};
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
                                if (!f.isEmpty() && !hay.toLowerCase().contains(f)) continue;
                                if (hide) {
                                    String h2 = b.hashOf(it);
                                    if (h2 != null && b.sentIndex.containsKey(h2)) {
                                        hidden[0]++;
                                        continue;
                                    }
                                }
                                if (scopeOnly && !b.inScope(it)) {
                                    oos[0]++;
                                    continue;
                                }
                                out.add(it);
                            } catch (Throwable ignored) { }
                        }
                    } catch (Throwable t) {
                        b.api.logging().logToError("refresh: " + t);
                    }
                    final List<Item> res = out;
                    final int tot = total;
                    final int hid = hidden[0];
                    final int outScope = oos[0];
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override public void run() {
                            model.set(res);
                            setStatus("Loaded " + res.size() + " entry/entries (of " + tot
                                    + " in history"
                                    + (hid > 0 ? ", " + hid + " already sent hidden" : "")
                                    + (outScope > 0 ? ", " + outScope + " out of scope hidden" : "")
                                    + "). Newest first.");
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
            final String n = note.getText().trim();
            note.setText("");
            bridge.sendAsync(items, n);
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

        /** repaint (not re-model): value/backgrounds are live lookups per cell */
        void repaintRows() {
            table.repaint();
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
                    all.addActionListener(e -> bridge.sendAsync(toItems(sel), "context-menu"));
                    items.add(all);
                }
                if (me != null && me.isPresent() && me.get().requestResponse() != null) {
                    final HttpRequestResponse hrr = me.get().requestResponse();
                    JMenuItem one = new JMenuItem("Send request to AI inbox");
                    one.addActionListener(e -> {
                        List<Item> single = new ArrayList<Item>();
                        single.add(bridge.itemFrom(hrr));
                        bridge.sendAsync(single, "context-menu");
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
        + "   host, port, tls, method, path, httpVersion, status, responseFile, note, batch.\n"
        + "   Items sent together share a `batch` id.\n"
        + "2. Read the manifest, then read the referenced request files (raw HTTP as Burp\n"
        + "   saw it; HTTP/2 messages are tagged with httpVersion in the manifest).\n"
        + "3. Then act: analyze, replay, fuzz. If Burp MCP tools are available use\n"
        + "   send_http1_request/send_http2_request with host/port/tls from the manifest,\n"
        + "   otherwise curl works too.\n"
        + "4. When done with an item, move BOTH its `.request.http` and `.response.http`\n"
        + "   into `archive/` and do NOT edit manifest.jsonl. The extension counts\n"
        + "   pending = request files at the root, so archiving drops the counter and\n"
        + "   the Burp tab marks the row consumed.\n"
        + "5. Reply to the human by APPENDING one JSON line per batch to\n"
        + "   `results.jsonl` in this directory: {\"ts\",\"batch\",\"summary\",\"by\"}.\n"
        + "   Append only - never edit or remove existing lines. The Burp tab shows\n"
        + "   the summary next to the matching batch. Keep summaries short.\n"
        + "6. Files contain the complete raw messages, no truncation. Binary bodies stay raw.\n\n"
        + "## Housekeeping\n"
        + "- `manifest.jsonl` rotates automatically (at 2 MB / 5000 lines): history moves\n"
        + "  to `archive/manifest-<stamp>.jsonl` and the live file keeps the newest lines\n"
        + "  plus a rotated event pointing at the archive. Treat a missing manifest as a\n"
        + "  rotation in progress and retry on your next tick.\n"
        + "- This directory is owner-only (0700) where the OS supports it. It contains raw\n"
        + "  authenticated HTTP - do not copy its contents anywhere without redacting.\n";
}
