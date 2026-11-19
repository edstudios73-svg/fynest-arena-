package gh.edu.gctu.smartattend.security;

import java.util.UUID;

/** The authenticated principal for the current request. Populated only by AuthFilter from a server-side session. */
public record CurrentUser(UUID userId, Role role, UUID sessionId, String csrfToken, UUID deviceId,
                          UUID studentId, UUID lecturerId, String displayName, boolean mustChangePassword) {}
