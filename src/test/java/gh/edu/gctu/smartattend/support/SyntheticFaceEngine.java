package gh.edu.gctu.smartattend.support;

import gh.edu.gctu.smartattend.face.FaceEngine;

import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * TEST-ONLY face engine used for workflow tests (registration, sign-in, attendance) where a real
 * camera cannot be driven. Frames are synthetic byte blobs that encode a person id and a head yaw;
 * embeddings are deterministic per person. The real OpenCV pipeline is tested separately in
 * FaceEngineRealModelTest with real photographs.
 */
public class SyntheticFaceEngine implements FaceEngine {
    @Override public String providerId() { return "test-synthetic"; }
    @Override public String modelVersion() { return "test"; }
    @Override public boolean available() { return true; }
    @Override public String unavailableReason() { return null; }

    @Override
    public FrameAnalysis analyse(byte[] image) {
        String s = new String(image, 2, image.length - 2, StandardCharsets.ISO_8859_1);
        String person = field(s, "person");
        if (person == null) return new FrameAnalysis(0, null, 0, 0, 0, 0, 0, "NO_FACE");
        double yaw = Double.parseDouble(field(s, "yaw"));
        float[] emb = embedding(person);
        return new FrameAnalysis(1, emb, yaw, 0.4, 120, 100, 0.95, null);
    }

    static String field(String s, String k) {
        int i = s.indexOf(k + "=");
        if (i < 0) return null;
        int j = s.indexOf(';', i);
        return s.substring(i + k.length() + 1, j);
    }

    static float[] embedding(String person) {
        Random r = new Random(person.hashCode());
        float[] v = new float[128];
        double n = 0;
        for (int i = 0; i < v.length; i++) { v[i] = (float) r.nextGaussian(); n += v[i] * v[i]; }
        n = Math.sqrt(n);
        for (int i = 0; i < v.length; i++) v[i] /= (float) n;
        return v;
    }

    /** Build a synthetic "JPEG" frame (valid JPEG magic so upload validation applies). */
    public static byte[] frame(String person, double yaw) {
        String payload = "person=" + person + ";yaw=" + yaw + ";nonce=" + System.nanoTime() + Math.random() + ";";
        byte[] p = payload.getBytes(StandardCharsets.ISO_8859_1);
        byte[] b = new byte[1500];
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xD8;
        System.arraycopy(p, 0, b, 2, p.length);
        for (int i = 2 + p.length; i < b.length; i++) b[i] = ' ';
        return b;
    }

    public static double yawFor(String step) {
        return switch (step) {
            case "LEFT" -> 0.35;
            case "RIGHT" -> -0.35;
            default -> 0.02;
        };
    }
}
