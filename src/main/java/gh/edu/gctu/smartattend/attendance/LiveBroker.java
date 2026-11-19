package gh.edu.gctu.smartattend.attendance;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server-Sent Events for the lecturer's live screen. Events carry no data — they only tell the
 * (already authorised) client to re-fetch the snapshot, so authorisation is always re-checked.
 */
@Component
public class LiveBroker {
    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID sessionId) {
        SseEmitter e = new SseEmitter(0L);
        List<SseEmitter> l = emitters.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        l.add(e);
        Runnable remove = () -> l.remove(e);
        e.onCompletion(remove);
        e.onTimeout(remove);
        e.onError(t -> remove.run());
        try { e.send(SseEmitter.event().name("ready").data("ok")); } catch (IOException ex) { remove.run(); }
        return e;
    }

    public void publish(UUID sessionId, String kind) {
        List<SseEmitter> l = emitters.get(sessionId);
        if (l == null) return;
        for (SseEmitter e : l) {
            try { e.send(SseEmitter.event().name("update").data(kind)); } catch (Exception ex) { l.remove(e); }
        }
    }

    @Scheduled(fixedDelay = 20_000)
    void heartbeat() {
        emitters.forEach((id, l) -> {
            for (SseEmitter e : l) {
                try { e.send(SseEmitter.event().comment("hb")); } catch (Exception ex) { l.remove(e); }
            }
        });
    }
}
