package aibridge;

/*
 * The agent reply stream: results.jsonl lives in the inbox root, agents APPEND
 * one JSON object per batch, the extension tails it by byte offset. Never
 * crashes on torn/malformed lines; re-reading after truncation restarts safely.
 *
 * Run: java -cp montoya.jar:build:build-test aibridge.ResultsStreamTest
 */

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;

public final class ResultsStreamTest {

    static int checks = 0;
    static int fails = 0;

    public static void main(String[] args) throws Throwable {
        parsesValidLines();
        skipsTornAndMalformedLines();
        watermarkIsIdempotent();
        truncationResetsWatermark();
        capsMemorySize();

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

    static Path tmp;
    static AiAgentBridge bridge() throws Exception {
        tmp = Files.createTempDirectory("ai-bridge-results");
        AiAgentBridge bridge = new AiAgentBridge();
        bridge.inboxDir = tmp;
        return bridge;
    }

    static void cleanup() {
        try {
            Files.walk(tmp).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }

    static void parsesValidLines() throws Exception {
        AiAgentBridge bridge = bridge();
        Files.write(tmp.resolve("results.jsonl"), (
                "{\"ts\":\"2026-10-18T06:00:00Z\",\"batch\":3,\"summary\":\"3 IDOR candidates\",\"by\":\"opencode-glm\"}\n"
              + "{\"ts\":\"2026-10-18T06:01:00Z\",\"batch\":4,\"summary\":\"clean, nothing exploitable\",\"by\":\"claude-code\"}\n"
        ).getBytes(StandardCharsets.UTF_8));

        bridge.readResults();
        eq("3 IDOR candidates", bridge.agentReplies.get(3L), "valid line maps batch -> summary");
        eq("clean, nothing exploitable", bridge.agentReplies.get(4L), "second batch mapped");
        cleanup();
    }

    /** A torn last line (agent crashed mid-write) must be skipped, not reported. */
    static void skipsTornAndMalformedLines() throws Exception {
        AiAgentBridge bridge = bridge();
        bridge.readResults(); // no file at all — must be a silent no-op
        eq(0, bridge.agentReplies.size(), "missing file is a no-op");

        Files.write(tmp.resolve("results.jsonl"), (
                "{\"ts\":\"2026-10-18T06:02:00Z\",\"batch\":5,\"summary\":\"telemetry ok\",\"by\":\"op\"}\n"
              + "{\"ts\":\"2026-10-18T06:0"                       // torn write
        ).getBytes(StandardCharsets.UTF_8));
        bridge.readResults();
        eq("telemetry ok", bridge.agentReplies.get(5L), "torn line skipped, good line kept");
        eq(1, bridge.agentReplies.size(), "torn line not half-parsed");

        Files.write(tmp.resolve("results.jsonl"),
                "}{!not json\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        bridge.readResults();
        eq(1, bridge.agentReplies.size(), "malformed line skipped");
        cleanup();
    }

    /** Re-reading with no new bytes must not duplicate or crash. */
    static void watermarkIsIdempotent() throws Exception {
        AiAgentBridge bridge = bridge();
        Files.write(tmp.resolve("results.jsonl"),
                "{\"ts\":\"t\",\"batch\":7,\"summary\":\"once\",\"by\":\"op\"}\n".getBytes());
        bridge.readResults();
        bridge.readResults();
        bridge.readResults();
        eq(1, bridge.agentReplies.size(), "watermark: no duplicate entries");
        eq("once", bridge.agentReplies.get(7L), "value stable");
        cleanup();
    }

    /** If the file shrinks (fresh inbox, rotation), restart from zero and re-read. */
    static void truncationResetsWatermark() throws Exception {
        AiAgentBridge bridge = bridge();
        Files.write(tmp.resolve("results.jsonl"),
                "{\"ts\":\"t1\",\"batch\":8,\"summary\":\"first inbox\",\"by\":\"op\"}\n".getBytes());
        bridge.readResults();
        eq("first inbox", bridge.agentReplies.get(8L), "first tracked");

        // agent replaced the whole file (fresh inbox): watermark > size now
        Files.write(tmp.resolve("results.jsonl"),
                "{\"ts\":\"t2\",\"batch\":9,\"summary\":\"second inbox\",\"by\":\"op\"}\n".getBytes(),
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        bridge.readResults();
        eq("second inbox", bridge.agentReplies.get(9L), "replaced file re-read");
        eq(null, bridge.agentReplies.get(8L), "stale batch dropped after reset");
        cleanup();
    }

    /** Many batches across a long session: bounded memory, newest entries kept. */
    static void capsMemorySize() throws Exception {
        AiAgentBridge bridge = bridge();
        StringBuilder big = new StringBuilder();
        for (int b = 100; b <= 130; b++) {
            big.append("{\"ts\":\"t\",\"batch\":").append(b)
               .append(",\"summary\":\"s").append(b).append("\",\"by\":\"op\"}\n");
        }
        Files.write(tmp.resolve("results.jsonl"), big.toString().getBytes());
        bridge.readResults();
        checks++;
        if (bridge.agentReplies.size() > AiAgentBridge.RESULTS_CAP) {
            fails++;
            System.out.println("[cap] size " + bridge.agentReplies.size()
                    + " > cap " + AiAgentBridge.RESULTS_CAP);
        }
        eq("s130", bridge.agentReplies.get(130L), "newest batch survives the cap");
        cleanup();
    }
}
