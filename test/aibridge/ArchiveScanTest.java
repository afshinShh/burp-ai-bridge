package aibridge;

/*
 * Consumption detection: rows whose pair has been moved into archive/ count as
 * consumed. refreshArchived() reads ONE directory listing and volunteers the
 * base names; the Agent column renders it. Uses a real temp inbox directory.
 *
 * Run: java -cp montoya.jar:build:build-test aibridge.ArchiveScanTest
 */

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;

public final class ArchiveScanTest {

    static int checks = 0;
    static int fails = 0;

    public static void main(String[] args) throws Throwable {
        consumesFilesInArchive();
        ignoresNonRequestFiles();
        refreshIsIdempotent();

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

    /** temp inbox wired straight onto the bridge fields, files live and moved like agents would */
    static void consumesFilesInArchive() throws Exception {
        Path tmp = Files.createTempDirectory("ai-bridge-scan");
        AiAgentBridge bridge = new AiAgentBridge();
        bridge.inboxDir = tmp;
        Files.createDirectories(tmp.resolve("archive"));

        String alive = "20261018-090000-001_api.example.com";
        String dead  = "20261018-090000-002_api.example.com";
        Files.write(tmp.resolve(alive + ".request.http"), "x".getBytes());
        Files.write(tmp.resolve("archive").resolve(dead + ".request.http"), "x".getBytes());
        Files.write(tmp.resolve("archive").resolve(dead + ".response.http"), "x".getBytes());

        bridge.refreshArchived();
        eq(false, bridge.isConsumed(alive), "pending file is not consumed");
        eq(true, bridge.isConsumed(dead), "archived request file is consumed");

        // agent consumes the second one later: same scan picks it up
        Files.move(tmp.resolve(alive + ".request.http"),
                tmp.resolve("archive").resolve(alive + ".request.http"),
                StandardCopyOption.REPLACE_EXISTING);
        bridge.refreshArchived();
        eq(true, bridge.isConsumed(alive), "pending -> archived transition detected");

        Files.walk(tmp).sorted((a, b) -> b.compareTo(a)).forEach(p -> delete(p));
    }

    /** response files alone must not mark anything consumed, nor must noise files */
    static void ignoresNonRequestFiles() throws Exception {
        Path tmp = Files.createTempDirectory("ai-bridge-scan2");
        AiAgentBridge bridge = new AiAgentBridge();
        bridge.inboxDir = tmp;
        Files.createDirectories(tmp.resolve("archive"));
        Files.write(tmp.resolve("archive").resolve("orphan.response.http"), "x".getBytes());
        Files.write(tmp.resolve("archive").resolve("notes.txt"), "x".getBytes());

        bridge.refreshArchived();
        eq(false, bridge.isConsumed("orphan"), "response-only file does not consume");
        eq(false, bridge.isConsumed("notes"), "noise file does not consume");
        eq(0, bridge.archived.size(), "scan set stays empty for noise");

        Files.walk(tmp).sorted((a, b) -> b.compareTo(a)).forEach(p -> delete(p));
    }

    /** repeated scans must produce the same set without accumulating stale entries */
    static void refreshIsIdempotent() throws Exception {
        Path tmp = Files.createTempDirectory("ai-bridge-scan3");
        AiAgentBridge bridge = new AiAgentBridge();
        bridge.inboxDir = tmp;
        Files.createDirectories(tmp.resolve("archive"));
        String one = "20261018-090001-001_api.example.com";
        Files.write(tmp.resolve("archive").resolve(one + ".request.http"), "x".getBytes());

        bridge.refreshArchived();
        int first = bridge.archived.size();
        bridge.refreshArchived();
        eq(first, bridge.archived.size(), "no accumulation across scans");
        eq(true, bridge.isConsumed(one), "still consumed after repeat");

        Files.walk(tmp).sorted((a, b) -> b.compareTo(a)).forEach(p -> delete(p));
    }

    static void delete(Path p) throws RuntimeException {
        try {
            Files.deleteIfExists(p);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
