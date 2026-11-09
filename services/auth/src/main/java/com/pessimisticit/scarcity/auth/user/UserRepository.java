package com.pessimisticit.scarcity.auth.user;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a user. Throws {@link org.springframework.dao.DuplicateKeyException} if the
     * username is taken (case-insensitively), enforced by the unique index on lower(username).
     */
    public User insert(UUID id, String username, String passwordHash) {
        return jdbc.sql("""
                        INSERT INTO auth.users (id, username, password_hash)
                        VALUES (:id, :username, :hash)
                        RETURNING id, username, password_hash, created_at
                        """)
                .param("id", id)
                .param("username", username)
                .param("hash", passwordHash)
                .query(UserRepository::map)
                .single();
    }

    public boolean existsByUsernameIgnoreCase(String username) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM auth.users WHERE lower(username) = lower(:username))")
                .param("username", username)
                .query(Boolean.class)
                .single();
    }

    public Optional<User> findByUsernameIgnoreCase(String username) {
        return jdbc.sql("""
                        SELECT id, username, password_hash, created_at
                        FROM auth.users WHERE lower(username) = lower(:username)
                        """)
                .param("username", username)
                .query(UserRepository::map)
                .optional();
    }

    public Optional<User> findById(UUID id) {
        return jdbc.sql("SELECT id, username, password_hash, created_at FROM auth.users WHERE id = :id")
                .param("id", id)
                .query(UserRepository::map)
                .optional();
    }

    private static User map(ResultSet rs, int row) throws SQLException {
        return new User(
                rs.getObject("id", UUID.class),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
