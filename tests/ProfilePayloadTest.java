package local.nova.diagnostic;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

/** Malformed-patch rejection plus optional exact reconstruction of private test data. */
public final class ProfilePayloadTest {
    private interface Checked { void run() throws Exception; }
    private static int passed;
    private static void reject(String label, Checked code) throws Exception {
        try { code.run(); } catch (Exception expected) { passed++; System.out.println("PASS " + label); return; }
        throw new AssertionError("Unexpected acceptance: " + label);
    }
    private static byte[] field(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        for (int i = bytes.length % 2; i < bytes.length - 1; i += 2) {
            byte a = bytes[i]; bytes[i] = bytes[i + 1]; bytes[i + 1] = a;
        }
        return bytes;
    }
    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(String.format(java.util.Locale.ROOT, "%02X", b & 255));
        return s.toString();
    }
    private static byte[] mutateInt(byte[] input, int at, int value) {
        byte[] copy = input.clone(); ByteBuffer.wrap(copy).putInt(at, value); return copy;
    }
    public static void main(String[] args) throws Exception {
        int[] rows = {36, 39, 40, 41, 76, 79, 80, 81};
        String[] fields = {"PostBlendGC", "PostBlendIGC", "PostBlendPCC", "PostBlendPa"};
        String[] originalLines = new String[88];
        String[] expectedLines = new String[88];
        Arrays.fill(originalLines, ""); Arrays.fill(expectedLines, "");
        originalLines[0] = expectedLines[0] = "{";
        originalLines[86] = expectedLines[86] = "}";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeBytes("NOVAPCH1"); out.writeInt(8);
        for (int i = 0; i < 8; i++) {
            originalLines[rows[i]] = "            \"" + fields[i % 4] + "\": \"" + hex(field("{\"v\":1}")) + "\",";
            expectedLines[rows[i]] = "      \"" + fields[i % 4] + "\": \"" + hex(field("{\"v\":2}")) + "\",";
            out.writeInt(rows[i]); out.writeInt(3);
            out.writeByte(0); out.writeInt(0); out.writeInt(5);
            out.writeByte(1); out.writeInt(1); out.writeByte('2');
            out.writeByte(0); out.writeInt(6); out.writeInt(1);
        }
        byte[] factory = String.join("\n", originalLines).getBytes(StandardCharsets.US_ASCII);
        byte[] expected = String.join("\r\n", expectedLines).getBytes(StandardCharsets.US_ASCII);
        byte[] patch = bytes.toByteArray();
        String fh = ProfilePayload.sha256(factory), oh = ProfilePayload.sha256(expected);
        if (!Arrays.equals(expected, ProfilePayload.reconstructChecked(factory, patch, fh, oh))) throw new AssertionError("Round trip");
        passed++; System.out.println("PASS synthetic byte-exact reconstruction");
        reject("wrong factory", () -> ProfilePayload.reconstructChecked(factory, patch, "bad", oh));
        reject("wrong final checksum", () -> ProfilePayload.reconstructChecked(factory, patch, fh, "bad"));
        reject("truncated header", () -> ProfilePayload.reconstructChecked(factory, new byte[3], fh, oh));
        reject("wrong magic", () -> ProfilePayload.reconstructChecked(factory, mutateInt(patch, 0, 0), fh, oh));
        reject("wrong field count", () -> ProfilePayload.reconstructChecked(factory, mutateInt(patch, 8, -1), fh, oh));
        reject("unexpected field index", () -> ProfilePayload.reconstructChecked(factory, mutateInt(patch, 12, 37), fh, oh));
        reject("too many operations", () -> ProfilePayload.reconstructChecked(factory, mutateInt(patch, 16, 9000), fh, oh));
        byte[] badTag = patch.clone(); badTag[20] = 7;
        reject("unknown operation", () -> ProfilePayload.reconstructChecked(factory, badTag, fh, oh));
        reject("negative copy offset", () -> ProfilePayload.reconstructChecked(factory, mutateInt(patch, 21, -1), fh, oh));
        reject("copy outside input", () -> ProfilePayload.reconstructChecked(factory, mutateInt(patch, 25, Integer.MAX_VALUE), fh, oh));
        reject("negative literal size", () -> ProfilePayload.reconstructChecked(factory, mutateInt(patch, 30, -1), fh, oh));
        reject("oversized literal", () -> ProfilePayload.reconstructChecked(factory, mutateInt(patch, 30, Integer.MAX_VALUE), fh, oh));
        reject("truncated operation", () -> ProfilePayload.reconstructChecked(factory, Arrays.copyOf(patch, 34), fh, oh));
        reject("trailing data", () -> ProfilePayload.reconstructChecked(factory, Arrays.copyOf(patch, patch.length + 1), fh, oh));
        byte[] corrupt = patch.clone(); corrupt[34] = '3';
        reject("changed calibration value", () -> ProfilePayload.reconstructChecked(factory, corrupt, fh, oh));
        reject("oversized patch", () -> ProfilePayload.reconstructChecked(factory, new byte[262145], fh, oh));
        reject("bounded factory stream", () -> ProfilePayload.readFactory(new ByteArrayInputStream(new byte[2097153])));
        reject("bounded patch stream", () -> ProfilePayload.readPatch(new ByteArrayInputStream(new byte[262145])));
        if (args.length != 0) {
            if (args.length != 2) throw new IllegalArgumentException("Optional args: private-factory-json sourceprofiles-directory");
            byte[] realFactory = Files.readAllBytes(Paths.get(args[0]));
            String[] profiles = {"gamma22", "srgb"};
            String[] hashes = {"c6e20079ef58220960e9511d5641bc02f1bcfb0f90c5bb0a842e5b2ed00c9afd", "8be527253edcc712006e73a26cbadd4f3aa1ade3bd8d2d00ae38587b3f393b25"};
            for (int i = 0; i < profiles.length; i++) {
                byte[] result = ProfilePayload.reconstruct(realFactory, Files.readAllBytes(Paths.get(args[1], profiles[i] + ".npatch")), hashes[i]);
                passed++; System.out.println("PASS measured " + profiles[i] + " " + result.length + " bytes SHA256 " + ProfilePayload.sha256(result));
            }
        }
        System.out.println("Passed: " + passed);
    }
}
