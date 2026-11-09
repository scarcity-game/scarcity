package com.pessimisticit.scarcity.auth.user;

import java.time.Instant;
import java.util.UUID;

public record User(UUID id, String username, String passwordHash, Instant createdAt) {

    @Override
    public String toString() {
        return "User[id=" + id + ", username=" + username + ", createdAt=" + createdAt + "]";
    }
}
