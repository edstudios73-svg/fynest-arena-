package gh.edu.gctu.smartattend.face;

import gh.edu.gctu.smartattend.config.AppProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the REAL face models (no mocks). Skipped automatically if the models have not been
 * downloaded (scripts/download-models.sh).
 */
class FaceEngineRealModelTest {
    static OpenCvFaceEngine engine;

    @BeforeAll
    static void init() {
        AppProperties p = new AppProperties("http://localhost", "localhost", "t", "", "", "", "", true, false, 8,
                "./models", "local-opencv", "", "");
        engine = new OpenCvFaceEngine(p);
        Assumptions.assumeTrue(engine.available(), "face models not installed");
    }

    static byte[] img(String name) throws Exception {
        return Files.readAllBytes(Path.of("src/test/resources/faces", name));
    }

    @Test
    void samePersonDifferentPhotosMatch() throws Exception {
        var a1 = engine.analyse(img("person_a_1.jpg"));
        var a2 = engine.analyse(img("person_a_2.jpg"));
        assertEquals(1, a1.faceCount());
        assertEquals(1, a2.faceCount());
        double sim = engine.similarity(a1.embedding(), a2.embedding());
        System.out.println("same-person similarity = " + sim);
        assertTrue(sim >= 0.42, "same person should match, got " + sim);
    }

    @Test
    void differentPeopleAreRejected() throws Exception {
        var a = engine.analyse(img("person_a_1.jpg"));
        var b = engine.analyse(img("person_b_1.jpg"));
        double sim = engine.similarity(a.embedding(), b.embedding());
        System.out.println("different-person similarity = " + sim);
        assertTrue(sim < 0.42, "different people must not match, got " + sim);
    }

    @Test
    void frontalPortraitHasNearZeroYaw() throws Exception {
        var a = engine.analyse(img("person_a_1.jpg"));
        System.out.println("frontal yaw = " + a.yaw());
        assertTrue(Math.abs(a.yaw()) < LivenessEvaluator.CENTER_MAX);
    }

    /** Spoof: a printed photo tilted left/right in front of the camera cannot produce real head turns. */
    @Test
    void tiltedFlatPhotoFailsLiveness() throws Exception {
        byte[] src = img("person_a_1.jpg");
        double yC = engine.analyse(src).yaw();
        double yL = engine.analyse(tilt(src, 0.35)).yaw();
        double yR = engine.analyse(tilt(src, -0.35)).yaw();
        System.out.printf("flat-photo yaws: center=%.3f left=%.3f right=%.3f%n", yC, yL, yR);
        var res = LivenessEvaluator.evaluate(List.of("CENTER", "LEFT", "RIGHT", "CENTER"), List.of(yC, yL, yR, yC));
        assertFalse(res.passed(), "tilted flat photo must not pass liveness");
    }

    @Test
    void noFaceIsReported() throws Exception {
        Mat blank = new Mat(480, 640, CvType.CV_8UC3, new Scalar(120, 130, 140));
        MatOfByte buf = new MatOfByte();
        Imgcodecs.imencode(".jpg", blank, buf);
        assertEquals("NO_FACE", engine.analyse(buf.toArray()).problem());
    }

    /** Perspective-warp an image as if a flat print were rotated about its vertical axis. */
    static byte[] tilt(byte[] jpeg, double amount) {
        Mat m = Imgcodecs.imdecode(new MatOfByte(jpeg), Imgcodecs.IMREAD_COLOR);
        int w = m.cols(), h = m.rows();
        double d = Math.abs(amount) * h * 0.5;
        MatOfPoint2f s = new MatOfPoint2f(new Point(0, 0), new Point(w, 0), new Point(w, h), new Point(0, h));
        MatOfPoint2f t = amount > 0
                ? new MatOfPoint2f(new Point(0, 0), new Point(w * (1 - amount * 0.6), d), new Point(w * (1 - amount * 0.6), h - d), new Point(0, h))
                : new MatOfPoint2f(new Point(w * (-amount) * 0.6, d), new Point(w, 0), new Point(w, h), new Point(w * (-amount) * 0.6, h - d));
        Mat out = new Mat();
        Imgproc.warpPerspective(m, out, Imgproc.getPerspectiveTransform(s, t), new Size(w, h), Imgproc.INTER_LINEAR,
                Core.BORDER_CONSTANT, new Scalar(200, 200, 200));
        MatOfByte buf = new MatOfByte();
        Imgcodecs.imencode(".jpg", out, buf);
        return buf.toArray();
    }
}
