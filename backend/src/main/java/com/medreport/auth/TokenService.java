package com.medreport.auth;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TokenService {
    public record Session(long userId, String username, String role, Instant expiresAt) {}
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public String issue(long userId, String username, String role) {
        String token = UUID.randomUUID().toString().replace("-", "");
        sessions.put(token, new Session(userId, username, role, Instant.now().plus(12, ChronoUnit.HOURS)));
        return token;
    }

    public Session verify(String token) {
        Session session = sessions.get(token);
        if (session == null || session.expiresAt().isBefore(Instant.now())) {
            sessions.remove(token);
            return null;
        }
        return session;
    }

    public void revoke(String token) {
        sessions.remove(token);
    }
}

