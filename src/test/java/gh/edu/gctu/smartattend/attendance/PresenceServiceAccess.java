package gh.edu.gctu.smartattend.attendance;

/** Test bridge to package-private helpers. */
public final class PresenceServiceAccess {
    public static boolean inCidr(String ip, String cidr) { return PresenceService.inCidr(ip, cidr); }
    public static double haversine(double a, double b, double c, double d) { return PresenceService.haversine(a, b, c, d); }
}
