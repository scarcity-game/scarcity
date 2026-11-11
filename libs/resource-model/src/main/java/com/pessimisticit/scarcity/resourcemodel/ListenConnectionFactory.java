package com.pessimisticit.scarcity.resourcemodel;

import java.sql.Connection;
import java.sql.SQLException;

/** Opens a fresh, caller-owned connection for LISTEN (never borrowed from a pool). */
@FunctionalInterface
public interface ListenConnectionFactory {
    Connection open() throws SQLException;
}
