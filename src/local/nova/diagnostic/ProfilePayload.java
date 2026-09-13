package local.nova.diagnostic;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/** Reconstructs the measured payload from this device's verified factory data.
 * No firmware configuration is distributed with the application.
 * Pure Java so the exact production decoder can also be tested on the host.
 */
final class ProfilePayload {
    static final String FACTORY_PATH = "/vendor/etc/display/qdcm_calib_data_il97680a_amoled_panel_without_DSC.json";
    static final String FACTORY_SHA256 = "26a56de100c2a27ecac2fe7ed4d30ba5bcb935865c7470775dcfda744d0173af";
    private static final int MAX_FILE = 2 * 1024 * 1024;
    private static final int MAX_PATCH = 256 * 1024;
    private static final int MAX_FIELD = 64 * 1024;
    private static final int[] ROWS = {36, 39, 40, 41, 76, 79, 80, 81};
    private static final String[] FIELDS = {"PostBlendGC", "PostBlendIGC", "PostBlendPCC", "PostBlendPa"};

    static byte[] readFactory(InputStream in) throws IOException {
        return readLimited(in, MAX_FILE);
    }

    static byte[] readPatch(InputStream in) throws IOException {
        return readLimited(in, MAX_PATCH);
    }

    private static byte[] readLimited(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = in.read(buffer)) != -1) {
            if (count == 0) continue;
            if (out.size() > limit - count) throw new IOException("Profile input exceeds size limit");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    static byte[] reconstruct(byte[] factory, byte[] patch, String expectedOutput) throws Exception {
        return reconstructChecked(factory, patch, FACTORY_SHA256, expectedOutput);
    }

    // Package-private: synthetic tests provide their own factory digest.
    static byte[] reconstructChecked(byte[] factory, byte[] patch, String expectedFactory, String expectedOutput) throws Exception {
        if (factory.length > MAX_FILE || patch.length > MAX_PATCH) throw new IOException("Profile input exceeds size limit");
        if (!sha256(factory).equals(expectedFactory)) throw new IOException("firmware_mismatch: factory checksum");
        for (byte b : factory) if (b < 0 || b == '\r' || b == 0) throw new IOException("Unsupported factory text");
        String[] lines = new String(factory, StandardCharsets.US_ASCII).split("\n", -1);
        if (lines.length != 88 || !lines[87].isEmpty()) throw new IOException("Unsupported factory layout");
        // The verified factory uses four-space indentation; the measured payload
        // uses two. Preserve every other byte, including all untouched OEM fields.
        for (int i = 0; i < lines.length; i++) {
            int spaces = 0;
            while (spaces < lines[i].length() && lines[i].charAt(spaces) == ' ') spaces++;
            if (spaces % 4 != 0) throw new IOException("Unsupported indentation");
            lines[i] = lines[i].substring(spaces / 2);
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(patch));
        byte[] magic = new byte[8];
        in.readFully(magic);
        if (!Arrays.equals(magic, "NOVAPCH1".getBytes(StandardCharsets.US_ASCII)) || in.readInt() != ROWS.length)
            throw new IOException("Invalid profile patch header");
        for (int row = 0; row < ROWS.length; row++) {
            if (in.readInt() != ROWS[row]) throw new IOException("Unexpected profile field");
            String prefix = "      \"" + FIELDS[row % FIELDS.length] + "\": \"";
            String line = lines[ROWS[row]];
            if (!line.startsWith(prefix) || !line.endsWith("\",")) throw new IOException("Unexpected profile syntax");
            byte[] original = decodeHex(line.substring(prefix.length(), line.length() - 2));
            swap(original);
            int count = in.readInt();
            if (count <= 0 || count > 8192) throw new IOException("Invalid patch operation count");
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            for (int operation = 0; operation < count; operation++) {
                int tag = in.readUnsignedByte();
                if (tag == 0) {
                    int start = in.readInt();
                    int length = in.readInt();
                    if (start < 0 || length <= 0 || start > original.length || length > original.length - start)
                        throw new IOException("Invalid factory copy range");
                    requireSpace(result, length);
                    result.write(original, start, length);
                } else if (tag == 1) {
                    int length = in.readInt();
                    requireSpace(result, length);
                    byte[] value = new byte[length];
                    in.readFully(value);
                    result.write(value);
                } else throw new IOException("Unknown patch operation");
            }
            byte[] decoded = result.toByteArray();
            swap(decoded);
            lines[ROWS[row]] = prefix + encodeHex(decoded) + "\",";
        }
        if (in.read() != -1) throw new IOException("Trailing patch data");
        // The frozen, measured Windows-generated payloads use CRLF. This is
        // intentional: the installer verifies their original byte-level hashes.
        byte[] output = String.join("\r\n", lines).getBytes(StandardCharsets.US_ASCII);
        if (output.length > MAX_FILE || !sha256(output).equals(expectedOutput))
            throw new IOException("Reconstructed profile checksum mismatch");
        return output;
    }

    private static void requireSpace(ByteArrayOutputStream out, int length) throws IOException {
        if (length <= 0 || length > MAX_FIELD - out.size()) throw new IOException("Invalid patch output size");
    }

    private static byte[] decodeHex(String hex) throws IOException {
        if (hex.length() % 2 != 0 || hex.length() > MAX_FIELD * 2) throw new IOException("Invalid encoded field size");
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int a = Character.digit(hex.charAt(i * 2), 16);
            int b = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (a < 0 || b < 0) throw new IOException("Invalid encoded field");
            bytes[i] = (byte)((a << 4) | b);
        }
        return bytes;
    }

    private static String encodeHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        char[] alphabet = "0123456789ABCDEF".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = alphabet[(bytes[i] & 255) >>> 4];
            out[i * 2 + 1] = alphabet[bytes[i] & 15];
        }
        return new String(out);
    }

    private static void swap(byte[] bytes) {
        for (int i = bytes.length % 2; i < bytes.length - 1; i += 2) {
            byte value = bytes[i]; bytes[i] = bytes[i + 1]; bytes[i + 1] = value;
        }
    }

    static String sha256(byte[] bytes) throws Exception {
        return encodeHex(MessageDigest.getInstance("SHA-256").digest(bytes)).toLowerCase(java.util.Locale.ROOT);
    }
}
