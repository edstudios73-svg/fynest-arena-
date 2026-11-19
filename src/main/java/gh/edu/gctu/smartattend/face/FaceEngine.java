package gh.edu.gctu.smartattend.face;

/**
 * Replaceable face analysis back-end. The default implementation runs evaluated open models
 * (OpenCV YuNet detector + SFace recogniser) on the server. A commercial provider with certified
 * presentation-attack detection (e.g. AWS Rekognition Face Liveness, Azure AI Face liveness,
 * iProov, FaceTec) can be plugged in by implementing this interface.
 */
public interface FaceEngine {
    String providerId();

    String modelVersion();

    boolean available();

    String unavailableReason();

    /** Analyse one camera frame (JPEG/PNG bytes). The image is processed in memory and never stored. */
    FrameAnalysis analyse(byte[] image);

    /** Cosine similarity between two L2-normalised embeddings. */
    default double similarity(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    record FrameAnalysis(int faceCount, float[] embedding, double yaw, double faceWidthRatio,
                         double brightness, double sharpness, double detectionScore, String problem) {
        public boolean ok() { return problem == null; }
    }
}
