package gh.edu.gctu.smartattend.attendance;

import gh.edu.gctu.smartattend.common.Db;
import gh.edu.gctu.smartattend.common.Json;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.net.InetAddress;
import java.util.*;

/**
 * Layered classroom presence evidence. No single signal proves physical presence:
 *  - Rotating QR/OTP shown only in the room (primary, lecturer-controlled),
 *  - Approved classroom network (client IP within the venue's configured CIDRs; meaningful only
 *    when the server can see campus-internal addresses, e.g. on-premise deployment or campus NAT IP),
 *  - GPS (supplementary; spoofable and inaccurate indoors),
 *  - BLE beacons (registered as proximity_devices; browsers cannot scan BLE advertisements, so this
 *    requires a native companion app — interface provided, not evaluated in the web app),
 *  - Lecturer-supervised review of PENDING_VERIFICATION records.
 */
@Service
public class PresenceService {
    private final Db db;

    public PresenceService(Db db) { this.db = db; }

    public record Gps(Double lat, Double lon, Double accuracy) {}

    public record Verdict(String result, List<Map<String, Object>> checks) {
        /** CONSISTENT, NOT_EVALUATED, INCONSISTENT, NOT_REQUIRED */
        public boolean inconsistent() { return "INCONSISTENT".equals(result); }
        public boolean positive() { return "CONSISTENT".equals(result); }
    }

    public Verdict evaluate(Map<String, Object> session, String ip, Gps gps) {
        List<Map<String, Object>> checks = new ArrayList<>();
        if ("ONLINE".equals(session.get("mode")) || "NONE".equals(session.get("proximityPolicy"))) {
            checks.add(Json.obj("type", "POLICY", "result", "NOT_REQUIRED"));
            return new Verdict("NOT_REQUIRED", checks);
        }
        if ("GEOFENCE".equals(session.get("proximityPolicy"))) return geofence(session, gps);
        Map<String, Object> venue = session.get("venueId") == null ? null : db.one("""
                SELECT code, latitude, longitude, radius_m, array_to_string(network_cidrs, ',') AS cidrs FROM venues WHERE id = :v""",
                "v", session.get("venueId"));
        boolean anyPositive = false, anyNegative = false;

        String cidrs = venue == null ? "" : (String) venue.get("cidrs");
        if (cidrs != null && !cidrs.isBlank()) {
            boolean in = Arrays.stream(cidrs.split(",")).anyMatch(c -> inCidr(ip, c.trim()));
            checks.add(Json.obj("type", "NETWORK", "result", in ? "PASS" : "FAIL"));
            if (in) anyPositive = true; else anyNegative = true;
        } else {
            checks.add(Json.obj("type", "NETWORK", "result", "NOT_CONFIGURED"));
        }

        if (venue != null && venue.get("latitude") != null && venue.get("longitude") != null) {
            if (gps == null || gps.lat() == null || gps.lon() == null) {
                checks.add(Json.obj("type", "GPS", "result", "NOT_PROVIDED"));
            } else {
                double dist = haversine(gps.lat(), gps.lon(), (Double) venue.get("latitude"), (Double) venue.get("longitude"));
                double acc = gps.accuracy() == null ? 999 : gps.accuracy();
                int radius = ((Number) venue.get("radiusM")).intValue();
                String r;
                if (acc > 150) r = "LOW_ACCURACY";
                else if (dist <= radius + acc) { r = "PASS"; anyPositive = true; }
                else if (dist > radius + acc + 100) { r = "FAIL"; anyNegative = true; }
                else r = "INCONCLUSIVE";
                checks.add(Json.obj("type", "GPS", "result", r, "distanceM", Math.round(dist), "accuracyM", Math.round(acc)));
            }
        } else {
            checks.add(Json.obj("type", "GPS", "result", "NOT_CONFIGURED"));
        }
        checks.add(Json.obj("type", "BLE_BEACON", "result", "NOT_SUPPORTED_IN_BROWSER"));
        String result = anyNegative ? "INCONSISTENT" : anyPositive ? "CONSISTENT" : "NOT_EVALUATED";
        return new Verdict(result, checks);
    }

    /**
     * Geofence around the admin's device: the student's phone must be within the session's radius of the
     * location most recently reported by the admin's screen. GPS accuracy indoors is limited, so an
     * allowance for both devices' reported accuracy (at most 50 m each) is added.
     */
    Verdict geofence(Map<String, Object> session, Gps gps) {
        List<Map<String, Object>> checks = new ArrayList<>();
        Double aLat = (Double) session.get("anchorLat"), aLon = (Double) session.get("anchorLon");
        java.time.Instant at = (java.time.Instant) session.get("anchorAt");
        int radius = ((Number) session.get("geofenceM")).intValue();
        String r;
        Long dist = null;
        if (aLat == null || aLon == null || at == null) r = "ADMIN_LOCATION_MISSING";
        else if (at.isBefore(java.time.Instant.now().minusSeconds(15 * 60))) r = "ADMIN_LOCATION_STALE";
        else if (gps == null || gps.lat() == null || gps.lon() == null) r = "NOT_PROVIDED";
        else if (gps.accuracy() != null && gps.accuracy() > 150) r = "LOW_ACCURACY";
        else {
            double d = haversine(gps.lat(), gps.lon(), aLat, aLon);
            dist = Math.round(d);
            // Both readings are uncertain indoors (big halls, concrete): allow for the student's and the admin's
            // reported accuracy, capped so a very rough reading can't stretch the range too far.
            Number aAcc = (Number) session.get("anchorAccuracy");
            double allowance = Math.min(gps.accuracy() == null ? 0 : gps.accuracy(), 50)
                    + Math.min(aAcc == null ? 0 : aAcc.doubleValue(), 50);
            r = d <= radius + allowance ? "PASS" : "TOO_FAR";
        }
        checks.add(Json.obj("type", "GEOFENCE", "result", r, "distanceM", dist, "radiusM", radius,
                "accuracyM", gps == null || gps.accuracy() == null ? null : Math.round(gps.accuracy())));
        return new Verdict("PASS".equals(r) ? "CONSISTENT" : "INCONSISTENT", checks);
    }

    /** Short user-facing message for a failed geofence. */
    public static String geofenceMessage(Verdict v) {
        Object r = v.checks().isEmpty() ? null : v.checks().get(0).get("result");
        Object d = v.checks().isEmpty() ? null : v.checks().get(0).get("distanceM");
        Object radius = v.checks().isEmpty() ? null : v.checks().get(0).get("radiusM");
        if ("TOO_FAR".equals(r)) return "You're " + d + " m away. Get within " + radius + " m of the class.";
        if ("NOT_PROVIDED".equals(r)) return "Turn on location and allow it for this site.";
        if ("LOW_ACCURACY".equals(r)) return "Location is too imprecise. Turn on precise location and try again.";
        if ("ADMIN_LOCATION_STALE".equals(r) || "ADMIN_LOCATION_MISSING".equals(r)) return "Class location isn't available yet. Try again in a moment.";
        return "You're not in class range.";
    }

    static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000, dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    static boolean inCidr(String ip, String cidr) {
        try {
            String[] p = cidr.split("/");
            byte[] a = InetAddress.getByName(ip).getAddress();
            byte[] n = InetAddress.getByName(p[0]).getAddress();
            if (a.length != n.length) return false;
            int bits = p.length > 1 ? Integer.parseInt(p[1]) : a.length * 8;
            BigInteger mask = BigInteger.ONE.shiftLeft(a.length * 8).subtract(BigInteger.ONE)
                    .xor(BigInteger.ONE.shiftLeft(a.length * 8 - bits).subtract(BigInteger.ONE));
            return new BigInteger(1, a).and(mask).equals(new BigInteger(1, n).and(mask));
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean validCidr(String cidr) {
        if (!cidr.matches("[0-9a-fA-F:.]+(/\\d{1,3})?")) return false;
        try {
            String[] p = cidr.split("/");
            byte[] n = InetAddress.getByName(p[0]).getAddress();
            return p.length == 1 || Integer.parseInt(p[1]) <= n.length * 8;
        } catch (Exception e) {
            return false;
        }
    }
}
