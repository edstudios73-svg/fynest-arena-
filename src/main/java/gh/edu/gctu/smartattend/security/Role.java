package gh.edu.gctu.smartattend.security;

public enum Role { STUDENT, LECTURER, ADMIN, SUPER_ADMIN;
    public boolean isAdmin() { return this == ADMIN || this == SUPER_ADMIN; }
}
