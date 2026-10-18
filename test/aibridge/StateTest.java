package aibridge;

/*
 * Pure-logic tests for the sent-state persistence blob (no Burp runtime needed).
 * Run: java -cp montoya.jar:build:build-test aibridge.StateTest
 */

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class StateTest {

    static int checks = 0;
    static int fails = 0;

    public static void main(String[] args) {
        testRoundTrip();
        testRoundTripEmpty();
        testV1Tolerance();
        testNoteCompaction();
        testCapKeepsNewest();
        testDecodeGarbage();
        testDecodeNullAndBlank();
        testManyEntries();

        System.out.println("checks=" + checks + " fails=" + fails);
        if (fails > 0) {
            System.out.println("FAIL");
            System.exit(1);
        }
        System.out.println("PASS");
    }

    static List<String[]> entry(String hash, String color, String batch, String note, String file) {
        return new ArrayList<String[]>(Arrays.<String[]>asList(
                new String[]{hash, color, batch, note, file}));
    }

    static void eq(Object expect, Object got, String label) {
        checks++;
        boolean ok = (expect == null) ? got == null : expect.equals(got);
        if (!ok) {
            fails++;
            System.out.println("[" + label + "] expected=\"" + expect + "\" got=\"" + got + "\"");
        }
    }

    /** Round trip must preserve hash/color/batch/file exactly and notes after compaction. */
    static void testRoundTrip() {
        List<String[]> in = entry("abc123", "3", "91", "run idor checks on user ids", "20261018-071111-001_api.example.com");
        in.add(new String[]{"def456", "0", "92", "note with \"quotes\" and \\ backslash", "20261018-071111-002_api.example.com"});
        in.add(new String[]{"789ghi", "7", "93", "unicode: \u00abaccent\u00bb \u2014 dash \u2022 dot", "20261018-071111-003_api.example.com"});
        in.add(new String[]{"last000", "1", "94", "", ""});

        String blob = AiAgentBridge.encodeSentState(in);
        List<String[]> out = AiAgentBridge.decodeSentState(blob);

        eq(4, out.size(), "roundtrip size");
        eq("abc123", out.get(0)[0], "rt hash0");
        eq("3", out.get(0)[1], "rt color0");
        eq("91", out.get(0)[2], "rt batch0");
        eq("run idor checks on user ids", out.get(0)[3], "rt note0");
        eq("20261018-071111-001_api.example.com", out.get(0)[4], "rt file0");
        eq("note with \"quotes\" and \\ backslash", out.get(1)[3], "rt note1 quotes");
        eq("unicode: \u00abaccent\u00bb \u2014 dash \u2022 dot", out.get(2)[3], "rt note2 unicode");
        eq("", out.get(3)[3], "rt note3 empty");
        eq("", out.get(3)[4], "rt file3 empty");
    }

    /** State written by v1 (4-field) must decode, file blank — no crash, no migration. */
    static void testV1Tolerance() {
        String blob = "h1\t0\t45\tsome note\nh2\t2\t46\t\n";
        List<String[]> out = AiAgentBridge.decodeSentState(blob);
        eq(2, out.size(), "v1 rows counted");
        eq("h1", out.get(0)[0], "v1 hash");
        eq("", out.get(0)[4], "v1 file empty by default");
        eq("", out.get(1)[3], "v1 empty note");
    }

    static void testRoundTripEmpty() {
        checks++;
        try {
            List<String[]> out = AiAgentBridge.decodeSentState(AiAgentBridge.encodeSentState(new ArrayList<String[]>()));
            eq(0, out.size(), "empty list encodes to zero entries");
        } catch (Throwable t) {
            fails++;
            System.out.println("[empty roundtrip] threw: " + t);
        }
    }

    /** Notes must survive blob storage with tabs/newlines flattened, length capped. */
    static void testNoteCompaction() {
        String messy = "line one\nline two\tcolumn\tequals\r\nend";
        String clean = AiAgentBridge.compactNoteStatic(messy);
        eq("line one line two column equals end", clean, "compaction flattens whitespace");

        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 50; i++) big.append("0123456789");
        String capped = AiAgentBridge.compactNoteStatic(big.toString());
        eq(160, capped.length(), "compaction caps length");
        eq("0123456789", capped.substring(0, 10), "compaction preserves prefix");
    }

    /** Trimming keeps the NEWEST entries (tail of the ordered list). */
    static void testCapKeepsNewest() {
        List<String[]> in = new ArrayList<String[]>();
        for (int i = 1; i <= 5; i++) {
            in.add(new String[]{"h" + i, "0", String.valueOf(i), "note" + i});
        }
        List<String[]> out = AiAgentBridge.capEntries(in, 3);
        eq(3, out.size(), "cap size");
        eq("h3", out.get(0)[0], "cap keeps newest 1");
        eq("h4", out.get(1)[0], "cap keeps newest 2");
        eq("h5", out.get(2)[0], "cap keeps newest 3");

        List<String[]> untouched = AiAgentBridge.capEntries(in, 10);
        eq(5, untouched.size(), "cap under limit is a no-op");
        eq("h1", untouched.get(0)[0], "no-op preserves order");
    }

    /** Corrupt lines must be skipped, not crash the loader. */
    static void testDecodeGarbage() {
        String bad = "justtext\nhash\t3\nh\tc\tb\tnote\n" + (char) 0x1f + "\t2\t9\tx";
        checks++;
        try {
            List<String[]> out = AiAgentBridge.decodeSentState(bad);
            eq(2, out.size(), "garbage lines skipped (>=4 fields kept)");
            eq("h", out.get(0)[0], "3-field line skipped");
            eq("x", out.get(1)[3], "control char treated as hash field ok");
        } catch (Throwable t) {
            fails++;
            System.out.println("[garbage] threw: " + t);
        }
    }

    static void testDecodeNullAndBlank() {
        eq(0, AiAgentBridge.decodeSentState(null).size(), "null decodes empty");
        eq(0, AiAgentBridge.decodeSentState("").size(), "empty decodes empty");
        eq(0, AiAgentBridge.decodeSentState("   \n  ").size(), "blank decodes empty");
    }

    /** 10k entries through encode/decode in a pooled loop: must not corrupt or hang. */
    static void testManyEntries() {
        List<String[]> in = new ArrayList<String[]>();
        for (int i = 0; i < 10000; i++) {
            in.add(new String[]{"hash" + i, String.valueOf(i % 11), String.valueOf(i), "note" + i, "file" + i});
        }
        checks++;
        try {
            List<String[]> round = AiAgentBridge.decodeSentState(AiAgentBridge.encodeSentState(in));
            eq(10000, round.size(), "10k count survives");
            eq("note9999", round.get(9999)[3], "10k last note survives");
            eq("file0", round.get(0)[4], "10k first file survives");
        } catch (Throwable t) {
            fails++;
            System.out.println("[10k] threw: " + t);
        }
    }
}
