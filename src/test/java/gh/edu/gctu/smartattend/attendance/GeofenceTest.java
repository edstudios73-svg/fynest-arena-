package gh.edu.gctu.smartattend.attendance;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeofenceTest {
    static final double LAT = 5.6037, LON = -0.1870, PER_M = 1 / 111_320.0;
    final PresenceService presence = new PresenceService(null);

    Map<String, Object> session(int radius, double adminAccuracy) {
        Map<String, Object> s = new HashMap<>();
        s.put("anchorLat", LAT); s.put("anchorLon", LON); s.put("anchorAt", Instant.now());
        s.put("anchorAccuracy", adminAccuracy); s.put("geofenceM", radius);
        return s;
    }

    String result(Map<String, Object> s, double metres, double accuracy) {
        var v = presence.geofence(s, new PresenceService.Gps(LAT + metres * PER_M, LON, accuracy));
        return (String) v.checks().get(0).get("result");
    }

    @Test
    void bigLectureHallAllowsForBothDevicesAccuracy() {
        Map<String, Object> hall = session(100, 30);
        assertEquals("PASS", result(hall, 60, 10), "front to back of a big hall");
        assertEquals("PASS", result(hall, 150, 40), "far corner with indoor GPS error (100 + 40 + 30)");
        assertEquals("TOO_FAR", result(hall, 260, 40), "outside the building");
        assertEquals("TOO_FAR", result(hall, 300, 140), "a very rough reading can't stretch the range past 100 + 50 + 30");
        assertEquals("LOW_ACCURACY", result(hall, 50, 400));
    }
}
