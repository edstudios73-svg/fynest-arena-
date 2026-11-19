package gh.edu.gctu.smartattend.face;

import java.util.ArrayList;
import java.util.List;

/**
 * Active liveness: the server issues a random sequence of head poses (CENTER / LEFT / RIGHT) and
 * checks that the captured frames follow it.
 *
 * Why this resists the common attacks:
 *  - Printed photo / photo on another screen: a flat image tilted in front of the camera keeps the
 *    nose-to-eye-midpoint ratio roughly constant (every point is foreshortened equally), so the
 *    required yaw changes cannot be produced.
 *  - Replayed video: the sequence is random per challenge, single-use and expires in ~2 minutes,
 *    so a pre-recorded clip will not match the requested order.
 *  - Reusing captured images: challenges are single-use and identical frames are rejected.
 * Limits (documented): a live deep-fake / virtual-camera injection that follows instructions in
 * real time is not defeated by this check alone; for high-assurance deployments plug in a
 * certified PAD provider through FaceEngine.
 */
public final class LivenessEvaluator {
    public static final double CENTER_MAX = 0.14;
    public static final double TURN_MIN = 0.20;
    public static final double MIN_SPREAD = 0.45;

    private LivenessEvaluator() {}

    public record Result(boolean passed, String failure) {}

    /** @param steps requested pose per frame; @param yaws measured yaw ratio per frame (same length). */
    public static Result evaluate(List<String> steps, List<Double> yaws) {
        if (steps.size() != yaws.size() || steps.isEmpty()) return new Result(false, "FRAME_COUNT");
        List<Double> left = new ArrayList<>(), right = new ArrayList<>();
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int i = 0; i < steps.size(); i++) {
            double y = yaws.get(i);
            min = Math.min(min, y);
            max = Math.max(max, y);
            switch (steps.get(i)) {
                case "CENTER" -> { if (Math.abs(y) > CENTER_MAX) return new Result(false, "NOT_FACING_CAMERA"); }
                case "LEFT" -> left.add(y);
                case "RIGHT" -> right.add(y);
                default -> { return new Result(false, "UNKNOWN_STEP"); }
            }
        }
        if (left.isEmpty() || right.isEmpty()) return new Result(false, "INCOMPLETE_SEQUENCE");
        // The sign convention depends on whether the camera stream is mirrored, so we derive it from
        // the LEFT frames and require every RIGHT frame to be clearly on the opposite side.
        double sign = Math.signum(left.get(0));
        if (sign == 0) return new Result(false, "HEAD_TURN_NOT_DETECTED");
        for (double y : left) if (Math.signum(y) != sign || Math.abs(y) < TURN_MIN) return new Result(false, "HEAD_TURN_NOT_DETECTED");
        for (double y : right) if (Math.signum(y) != -sign || Math.abs(y) < TURN_MIN) return new Result(false, "HEAD_TURN_NOT_DETECTED");
        if (max - min < MIN_SPREAD) return new Result(false, "HEAD_TURN_NOT_DETECTED");
        return new Result(true, null);
    }
}
