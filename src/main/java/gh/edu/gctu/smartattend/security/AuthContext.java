package gh.edu.gctu.smartattend.security;

import gh.edu.gctu.smartattend.common.ApiException;

public final class AuthContext {
    private static final ThreadLocal<CurrentUser> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<String> IP = new ThreadLocal<>();

    private AuthContext() {}

    static void set(CurrentUser u, String ip) { CURRENT.set(u); IP.set(ip); }
    static void clear() { CURRENT.remove(); IP.remove(); }

    public static CurrentUser get() { return CURRENT.get(); }
    public static String ip() { return IP.get(); }

    public static CurrentUser require() {
        CurrentUser u = CURRENT.get();
        if (u == null) throw ApiException.unauthorized("Please sign in.");
        return u;
    }

    public static CurrentUser requireRole(Role... roles) {
        CurrentUser u = require();
        for (Role r : roles) if (u.role() == r) return u;
        throw ApiException.forbidden("You do not have permission to perform this action.");
    }
}
