package aibridge;

/*
 * Manifest rotation: manifest.jsonl grows unbounded across a real hunt; the
 * extension must move history into archive/ and keep the live file small,
 * always ending with a "rotated" event naming the archive, losing nothing.
 *
 * Run: java -cp montoya.jar:build:build-test aibridge.RotationTest
 */

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public final class RotationTest {

    static int checks = 0;
    static int fails = 0;

    public static void main(String[] args) throws Throwable {
        testThresholds();
        testRotationMovesHistory();
        testRotationKeepsTailAndMarker();
        testConcurrentAppendNoLoss();

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

    static AiAgentBridge bridgeIn(Path tmp) {
        AiAgentBridge bridge = new AiAgentBridge();
        bridge.inboxDir = tmp;
        return bridge;
    }

    static List<String> lines(Path f) throws Exception {
        return Files.readAllLines(f, StandardCharsets.UTF_8);
    }

    static void testThresholds() {
        // below both thresholds: no rotation
        eq(false, AiAgentBridge.shouldRotate(1000, 100), "small manifest does not rotate");
        eq(true, AiAgentBridge.shouldRotate(3 * 1024 * 1024, 10), "bytes threshold");
        eq(true, AiAgentBridge.shouldRotate(10, 6000), "line threshold");
        eq(false, AiAgentBridge.shouldRotate(1024 * 1024, 4999), "just under both thresholds");
    }

    static void testRotationMovesHistory() throws Exception {
        Path tmp = Files.createTempDirectory("ai-bridge-rot");
        AiAgentBridge bridge = bridgeIn(tmp);
        List<String> seed = new ArrayList<String>();
        for (int i = 1; i <= 1200; i++) seed.add("{\"item\":" + i + "}");
        Files.write(tmp.resolve("manifest.jsonl"),
                String.join("\n", seed).concat("\n").getBytes(StandardCharsets.UTF_8));

        bridge.rotateManifest();

        List<String> live = lines(tmp.resolve("manifest.jsonl"));
        checks++;
        if (live.size() > 201) {
            fails++;
            System.out.println("[live small] live manifest has " + live.size() + " lines after rotation");
        }
        checks++;
        if (!live.get(live.size() - 1).contains("\"event\":\"rotated\"")) {
            fails++;
            System.out.println("[marker] last live line is not a rotated event: "
                    + live.get(live.size() - 1));
        }

        Files.walk(tmp).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
            try { Files.deleteIfExists(p); } catch (Exception ignored) { }
        });
    }

    static void testRotationKeepsTailAndMarker() throws Exception {
        Path tmp = Files.createTempDirectory("ai-bridge-rot2");
        AiAgentBridge bridge = bridgeIn(tmp);
        List<String> seed = new ArrayList<String>();
        for (int i = 1; i <= 1000; i++) seed.add("{\"item\":" + i + "}");
        Files.write(tmp.resolve("manifest.jsonl"),
                String.join("\n", seed).concat("\n").getBytes(StandardCharsets.UTF_8));

        bridge.rotateManifest();

        Path archived = null;
        try (var ds = Files.newDirectoryStream(tmp.resolve("archive"), "manifest-*.jsonl")) {
            for (Path p : ds) archived = p;
        }
        checks++;
        if (archived == null) {
            fails++;
            System.out.println("[archive exists] no archived manifest file found");
        } else {
            eq(1000, lines(archived).size(), "archived file holds the full history");
        }

        List<String> live = lines(tmp.resolve("manifest.jsonl"));
        String marker = live.get(live.size() - 1);
        checks++;
        if (!marker.contains("rotated")) {
            fails++;
            System.out.println("[marker] last live line is not a rotated event: " + marker);
        } else {
            String archivedName = AiAgentBridge.extractEventMessage(marker);
            checks++;
            if (archivedName == null || archivedName.isEmpty() || !archivedName.contains("manifest-")) {
                fails++;
                System.out.println("[marker names archive] message=\"" + archivedName + "\"");
            } else {
                eq(true, Files.exists(tmp.resolve("archive").resolve(
                        archivedName.replaceFirst("^.*archive/", ""))),
                        "marker points at a real file");
            }
        }

        Files.walk(tmp).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
            try { Files.deleteIfExists(p); } catch (Exception ignored) { }
        });
    }

    /** a line appended immediately before rotation lands either in live tail or archive — never lost */
    static void testConcurrentAppendNoLoss() throws Exception {
        Path tmp = Files.createTempDirectory("ai-bridge-rot3");
        AiAgentBridge bridge = bridgeIn(tmp);
        StringBuilder seed = new StringBuilder();
        for (int i = 1; i <= 2000; i++) seed.append("{\"item\":").append(i).append("}\n");
        Files.write(tmp.resolve("manifest.jsonl"), seed.toString().getBytes(StandardCharsets.UTF_8));

        // simulate a racing append right before rotation
        Files.write(tmp.resolve("manifest.jsonl"),
                "{\"race\":true}\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);

        bridge.rotateManifest();

        boolean found = sameFileHas(lines(tmp.resolve("manifest.jsonl")), "\"race\":true")
                || archiveHas(tmp, "\"race\":true");
        eq(true, found, "raced line survives rotation");

        Files.walk(tmp).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
            try { Files.deleteIfExists(p); } catch (Exception ignored) { }
        });
    }

    static boolean sameFileHas(List<String> ls, String needle) {
        for (String l : ls) {
            if (l.contains(needle)) return true;
        }
        return false;
    }

    static boolean archiveHas(Path tmp, String needle) throws Exception {
        try (var ds = Files.newDirectoryStream(tmp.resolve("archive"), "manifest-*.jsonl")) {
            for (Path p : ds) {
                if (sameFileHas(lines(p), needle)) return true;
            }
        }
        return false;
    }
}
