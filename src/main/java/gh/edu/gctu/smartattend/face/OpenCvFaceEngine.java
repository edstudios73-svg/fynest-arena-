package gh.edu.gctu.smartattend.face;

import gh.edu.gctu.smartattend.config.AppProperties;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.FaceDetectorYN;
import org.opencv.objdetect.FaceRecognizerSF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Face analysis with OpenCV:
 *  - YuNet (face_detection_yunet_2023mar.onnx): detection + 5 facial landmarks
 *  - SFace (face_recognition_sface_2021dec.onnx): 128-d identity embedding, cosine similarity
 *    (published LFW accuracy 99.40%; OpenCV's recommended cosine threshold is 0.363).
 * Models are downloaded by scripts/download-models.sh and are not committed to git.
 */
@Component
@ConditionalOnProperty(name = "smartattend.face-provider", havingValue = "local-opencv", matchIfMissing = true)
public class OpenCvFaceEngine implements FaceEngine {
    private static final Logger log = LoggerFactory.getLogger(OpenCvFaceEngine.class);
    public static final String DETECTOR = "face_detection_yunet_2023mar.onnx";
    public static final String RECOGNISER = "face_recognition_sface_2021dec.onnx";
    private static final int MAX_DIM = 720;

    private final String detectorPath;
    private final String recogniserPath;
    private final String unavailable;
    private final ThreadLocal<FaceDetectorYN> detector;
    private final ThreadLocal<FaceRecognizerSF> recogniser;

    public OpenCvFaceEngine(AppProperties props) {
        Path dir = Path.of(props.modelsDir());
        this.detectorPath = dir.resolve(DETECTOR).toAbsolutePath().toString();
        this.recogniserPath = dir.resolve(RECOGNISER).toAbsolutePath().toString();
        String reason = null;
        if (!Files.exists(Path.of(detectorPath)) || !Files.exists(Path.of(recogniserPath))) {
            reason = "Face models not installed in " + dir.toAbsolutePath() + ". Run scripts/download-models.sh.";
        } else {
            try {
                nu.pattern.OpenCV.loadLocally();
            } catch (Throwable t) {
                reason = "OpenCV native library could not be loaded: " + t.getMessage();
            }
        }
        this.unavailable = reason;
        if (reason != null) log.error("Face verification UNAVAILABLE: {}", reason);
        else log.info("Face verification ready (OpenCV {} YuNet + SFace)", Core.VERSION);
        this.detector = ThreadLocal.withInitial(() ->
                FaceDetectorYN.create(detectorPath, "", new Size(320, 320), 0.85f, 0.3f, 50));
        this.recogniser = ThreadLocal.withInitial(() -> FaceRecognizerSF.create(recogniserPath, ""));
    }

    @Override public String providerId() { return "local-opencv"; }
    @Override public String modelVersion() { return "yunet-2023mar+sface-2021dec"; }
    @Override public boolean available() { return unavailable == null; }
    @Override public String unavailableReason() { return unavailable; }

    @Override
    public FrameAnalysis analyse(byte[] image) {
        if (!available()) throw new IllegalStateException(unavailable);
        Mat img = Imgcodecs.imdecode(new MatOfByte(image), Imgcodecs.IMREAD_COLOR);
        Mat faces = new Mat();
        Mat aligned = new Mat();
        Mat feature = new Mat();
        try {
            if (img.empty()) return problem("UNREADABLE_IMAGE");
            double scale = Math.min(1.0, MAX_DIM / (double) Math.max(img.cols(), img.rows()));
            if (scale < 1.0) Imgproc.resize(img, img, new Size(Math.round(img.cols() * scale), Math.round(img.rows() * scale)));
            FaceDetectorYN det = detector.get();
            det.setInputSize(new Size(img.cols(), img.rows()));
            det.detect(img, faces);
            int n = faces.rows();
            if (n == 0) return problem("NO_FACE");
            if (n > 1) return new FrameAnalysis(n, null, 0, 0, 0, 0, 0, "MULTIPLE_FACES");
            float[] f = new float[15];
            faces.get(0, 0, f);
            double w = f[2];
            double reX = f[4], leX = f[6], noseX = f[8];
            double eyeSpan = leX - reX;
            double yaw = Math.abs(eyeSpan) < 1 ? 0 : (noseX - (reX + leX) / 2.0) / eyeSpan;
            double widthRatio = w / img.cols();

            Rect roi = new Rect(new Point(Math.max(0, f[0]), Math.max(0, f[1])),
                    new Point(Math.min(img.cols() - 1, f[0] + f[2]), Math.min(img.rows() - 1, f[1] + f[3])));
            Mat gray = new Mat();
            Imgproc.cvtColor(new Mat(img, roi), gray, Imgproc.COLOR_BGR2GRAY);
            double brightness = Core.mean(gray).val[0];
            Mat lap = new Mat();
            Imgproc.Laplacian(gray, lap, CvType.CV_64F);
            MatOfDouble mu = new MatOfDouble(), sigma = new MatOfDouble();
            Core.meanStdDev(lap, mu, sigma);
            double sharpness = Math.pow(sigma.get(0, 0)[0], 2);
            gray.release(); lap.release();

            String problem = null;
            if (widthRatio < 0.10) problem = "FACE_TOO_SMALL";
            else if (brightness < 35) problem = "TOO_DARK";
            else if (brightness > 225) problem = "TOO_BRIGHT";
            else if (sharpness < 8) problem = "TOO_BLURRY";

            FaceRecognizerSF rec = recogniser.get();
            rec.alignCrop(img, faces.row(0), aligned);
            rec.feature(aligned, feature);
            float[] emb = new float[(int) feature.total()];
            feature.get(0, 0, emb);
            normalise(emb);
            return new FrameAnalysis(1, emb, yaw, widthRatio, brightness, sharpness, f[14], problem);
        } finally {
            img.release(); faces.release(); aligned.release(); feature.release();
        }
    }

    private static FrameAnalysis problem(String p) {
        return new FrameAnalysis(0, null, 0, 0, 0, 0, 0, p);
    }

    static void normalise(float[] v) {
        double n = 0;
        for (float x : v) n += x * x;
        n = Math.sqrt(n);
        if (n > 0) for (int i = 0; i < v.length; i++) v[i] /= (float) n;
    }
}
