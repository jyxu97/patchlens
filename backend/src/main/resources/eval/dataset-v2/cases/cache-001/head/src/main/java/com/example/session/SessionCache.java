package com.example.session;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** In-memory session store shared by all request threads. Sessions expire after a fixed TTL. */
public class SessionCache {

    private record Entry(Session session, Instant expiresAt) {}

    private final Map<String, Entry> sessions = new HashMap<>();
    private final Clock clock;
    private final Duration ttl;

    public SessionCache(Clock clock, Duration ttl) {
        this.clock = clock;
        this.ttl = ttl;
    }

    public Session create(String userId) {
        Session session = new Session(UUID.randomUUID().toString(), userId);
        sessions.put(session.id(), new Entry(session, clock.instant().plus(ttl)));
        return session;
    }

    /** Returns the session if it exists and has not expired yet. */
    public Optional<Session> get(String sessionId) {
        Entry entry = sessions.get(sessionId);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt().isAfter(clock.instant())) {
            sessions.remove(sessionId);
            return Optional.empty();
        }
        return Optional.of(entry.session());
    }

    public void invalidate(String sessionId) {
        sessions.remove(sessionId);
    }
}
