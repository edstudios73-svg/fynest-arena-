package gh.edu.gctu.smartattend.security;

import gh.edu.gctu.smartattend.common.ApiException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window rate limiter (per node). For multi-node deployments put an edge rate limiter
 * (e.g. Vercel/Cloudflare/NGINX) in front as well; account lockouts are persisted in the database.
 */
@Component
public class RateLimiter {
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    public void check(String bucket, String key, int max, long windowSeconds) {
        if (!tryAcquire(bucket, key, max, windowSeconds)) {
            throw ApiException.tooMany("Too many attempts. Please wait a moment and try again.");
        }
    }

    public boolean tryAcquire(String bucket, String key, int max, long windowSeconds) {
        long now = System.currentTimeMillis();
        long cutoff = now - windowSeconds * 1000;
        Deque<Long> q = windows.computeIfAbsent(bucket + "|" + key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst() < cutoff) q.pollFirst();
            if (q.size() >= max) return false;
            q.addLast(now);
            return true;
        }
    }

    public void reset() { windows.clear(); }

    @Scheduled(fixedDelay = 300_000)
    void cleanup() {
        long cutoff = System.currentTimeMillis() - 3_600_000;
        windows.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                return e.getValue().isEmpty() || e.getValue().peekLast() < cutoff;
            }
        });
    }
}
