package gh.edu.gctu.smartattend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class SmartAttendApplication {
    /** True when running on the bundled database (data is not permanent). */
    public static volatile boolean previewMode = false;

    public static void main(String[] args) throws Exception {
        adoptPlatformPublicUrl();
        adoptPlatformDatabaseUrl();
        startBundledDatabaseIfNeeded();
        SpringApplication.run(SmartAttendApplication.class, args);
    }

    /**
     * Hosting platforms (Vercel Marketplace Postgres/Neon, Render, Railway, Heroku) provide the database as
     * postgres://user:password@host:port/db?sslmode=require. Convert that to JDBC settings. A value that is
     * already a jdbc: URL is left untouched.
     */
    static void adoptPlatformDatabaseUrl() {
        String url = firstNonBlank(System.getenv("DATABASE_URL"), System.getenv("POSTGRES_URL"));
        if (url == null || url.startsWith("jdbc:")) return;
        if (!url.startsWith("postgres://") && !url.startsWith("postgresql://")) return;
        URI u = URI.create(url.replaceFirst("^postgres(ql)?://", "http://"));
        String query = u.getRawQuery() == null ? "sslmode=require" : u.getRawQuery().replace("channel_binding=require", "").replaceAll("&&+", "&").replaceAll("^&|&$", "");
        if (!query.contains("sslmode=")) query = query.isEmpty() ? "sslmode=require" : query + "&sslmode=require";
        String jdbc = "jdbc:postgresql://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "") + u.getRawPath() + "?" + query;
        System.setProperty("spring.datasource.url", jdbc);
        if (u.getRawUserInfo() != null) {
            String[] ui = u.getRawUserInfo().split(":", 2);
            System.setProperty("spring.datasource.username", URLDecoder.decode(ui[0], StandardCharsets.UTF_8));
            if (ui.length > 1) System.setProperty("spring.datasource.password", URLDecoder.decode(ui[1], StandardCharsets.UTF_8));
        }
    }

    /**
     * Preview mode: with no DATABASE_URL and SMARTATTEND_EMBEDDED_DB=true, start a real PostgreSQL 16 inside
     * this process. Its data lives on the container's temporary disk, so it is lost when the host restarts
     * the container. Attach an external PostgreSQL (DATABASE_URL) for permanent data.
     */
    static void startBundledDatabaseIfNeeded() throws Exception {
        if (firstNonBlank(System.getenv("DATABASE_URL"), System.getenv("POSTGRES_URL")) != null) return;
        if (!"true".equalsIgnoreCase(System.getenv("SMARTATTEND_EMBEDDED_DB"))) return;
        // SMARTATTEND_DATA_DIR = a permanent disk (single always-on server): data is kept.
        // Without it the database lives on temporary storage and the site runs in preview mode.
        String persistentDir = firstNonBlank(System.getenv("SMARTATTEND_DATA_DIR"));
        java.nio.file.Path base = persistentDir != null ? java.nio.file.Path.of(persistentDir) : java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));
        java.nio.file.Files.createDirectories(base);
        java.nio.file.Path dir = base.resolve("smartattend-pg");
        io.zonky.test.db.postgres.embedded.EmbeddedPostgres pg = io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder()
                .setDataDirectory(dir).setCleanDataDirectory(false).setPort(54329)
                .setOverrideWorkingDirectory(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "smartattend-pg-bin").toFile())
                .setPGStartupWait(java.time.Duration.ofSeconds(60))
                .start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { pg.close(); } catch (Exception ignored) { } }));
        System.setProperty("spring.datasource.url", pg.getJdbcUrl("postgres", "postgres"));
        System.setProperty("spring.datasource.username", "postgres");
        System.setProperty("spring.datasource.password", "postgres");
        previewMode = persistentDir == null;
    }

    /** Use the host's public URL (e.g. Render's RENDER_EXTERNAL_URL) when SMARTATTEND_BASE_URL is not set. */
    static void adoptPlatformPublicUrl() {
        if (firstNonBlank(System.getenv("SMARTATTEND_BASE_URL")) != null) return;
        String url = firstNonBlank(System.getenv("RENDER_EXTERNAL_URL"), System.getenv("PUBLIC_URL"));
        if (url == null) return;
        url = url.replaceAll("/$", "");
        System.setProperty("smartattend.base-url", url);
        if (firstNonBlank(System.getenv("SMARTATTEND_RP_ID")) == null) System.setProperty("smartattend.rp-id", URI.create(url).getHost());
    }

    private static String firstNonBlank(String... v) {
        for (String s : v) if (s != null && !s.isBlank()) return s.trim();
        return null;
    }
}
