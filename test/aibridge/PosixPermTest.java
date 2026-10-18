package aibridge;

/*
 * Inbox permissions: files carry raw HTTP (cookies, tokens), so the directory
 * must be owner-only where POSIX is supported. On filesystems without POSIX
 * support (Windows) hardenInbox() must be a silent no-op.
 *
 * Run: java -cp montoya.jar:build:build-test aibridge.PosixPermTest
 */

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

public final class PosixPermTest {

    static int checks = 0;
    static int fails = 0;

    public static void main(String[] args) throws Throwable {
        Path tmp = Files.createTempDirectory("ai-bridge-perm");
        AiAgentBridge bridge = new AiAgentBridge();
        bridge.inboxDir = tmp;
        bridge.hardenInbox();

        boolean posix = tmp.getFileSystem().supportedFileAttributeViews().contains("posix");
        checks++;
        if (posix) {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(tmp);
            Set<PosixFilePermission> expect =
                    PosixFilePermissions.fromString("rwx------");
            if (!perms.equals(expect)) {
                fails++;
                System.out.println("[owner-only] got " + perms + " expected " + expect);
            }
        } // non-POSIX: the call above already proved silence; nothing to assert

        checks++;
        if (fails == 0 && posix) System.out.println("PASS");
        else if (!posix) System.out.println("PASS (non-posix: no-op verified)");
        else System.out.println("FAIL");
        System.out.println("checks=" + checks + " fails=" + fails);
        if (fails > 0) System.exit(1);

        Files.walk(tmp).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
            try { Files.deleteIfExists(p); } catch (Exception ignored) { }
        });
    }
}
