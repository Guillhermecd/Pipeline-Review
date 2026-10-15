package com.bimd.fiscalpipeline.service.event;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The open {@code /eventos} connections. They live in memory, so this works for one instance of
 * the API; more than one would need a pub/sub between them.
 */
@Slf4j
@Component
public class EventStreamHub {

    /** No timeout: the connection lasts until the client leaves. */
    private static final long NEVER = 0L;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /** Opens a connection whose first event is the current state. */
    public SseEmitter open(String firstEventName, Object firstEvent) {
        SseEmitter emitter = new SseEmitter(NEVER);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(error -> emitters.remove(emitter));
        // Registered before the snapshot goes out: a change in between arrives after it, never lost.
        emitters.add(emitter);
        send(emitter, SseEmitter.event().name(firstEventName).data(firstEvent));
        return emitter;
    }

    public void broadcast(String eventName, Object data) {
        for (SseEmitter emitter : emitters) {
            send(emitter, SseEmitter.event().name(eventName).data(data));
        }
    }

    /** Proxies close a connection that stays silent; a comment line keeps it open. */
    @Scheduled(fixedRate = 15_000)
    public void heartbeat() {
        for (SseEmitter emitter : emitters) {
            send(emitter, SseEmitter.event().comment("ping"));
        }
    }

    public int connections() {
        return emitters.size();
    }

    private void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            // The client went away; there is nobody to tell.
            log.debug("Dropped an event stream connection: {}", e.getMessage());
            emitters.remove(emitter);
        }
    }
}
