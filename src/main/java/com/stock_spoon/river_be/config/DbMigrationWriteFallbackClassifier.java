package com.stock_spoon.river_be.config;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

/**
 * Detects a ProxySQL hostgroup connection-timeout signal in an exception chain.
 * The HTTP-method check is only a coarse observation hint; it does not prove that
 * a request performs a database write and must not by itself trigger replay.
 */
public final class DbMigrationWriteFallbackClassifier {
    public static final int PROXYSQL_HOSTGROUP_CONNECT_TIMEOUT = 9001;

    private static final Set<String> WRITE_HTTP_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private DbMigrationWriteFallbackClassifier() {
    }

    public static boolean isWriteHttpMethod(String method) {
        return method != null && WRITE_HTTP_METHODS.contains(method.toUpperCase(Locale.ROOT));
    }

    public static SQLException findProxySqlConnectTimeout(Throwable error) {
        if (error == null) return null;

        Deque<Throwable> pending = new ArrayDeque<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(error);

        while (!pending.isEmpty()) {
            Throwable candidate = pending.removeFirst();
            if (!visited.add(candidate)) continue;

            if (candidate instanceof SQLException sqlError) {
                if (sqlError.getErrorCode() == PROXYSQL_HOSTGROUP_CONNECT_TIMEOUT) return sqlError;
                if (sqlError.getNextException() != null) pending.addLast(sqlError.getNextException());
            }
            if (candidate.getCause() != null) pending.addLast(candidate.getCause());
        }
        return null;
    }
}
