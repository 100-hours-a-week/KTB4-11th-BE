package com.stock_spoon.river_be.config;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

/** Identifies database connection failures eligible for write replay. */
public final class DbMigrationWriteFallbackClassifier {
    public static final int PROXYSQL_HOSTGROUP_CONNECT_TIMEOUT = 9001;
    public static final String MYSQL_COMMUNICATIONS_LINK_FAILURE_SQL_STATE = "08S01";

    private static final Set<String> WRITE_HTTP_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private DbMigrationWriteFallbackClassifier() {
    }

    public static boolean isWriteHttpMethod(String method) {
        return method != null && WRITE_HTTP_METHODS.contains(method.toUpperCase(Locale.ROOT));
    }

    public static SQLException findWriteFallbackFailure(Throwable error) {
        if (error == null) return null;

        Deque<Throwable> pending = new ArrayDeque<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(error);

        while (!pending.isEmpty()) {
            Throwable candidate = pending.removeFirst();
            if (!visited.add(candidate)) continue;

            if (candidate instanceof SQLException sqlError) {
                if (isWriteFallbackSignal(sqlError)) return sqlError;
                if (sqlError.getNextException() != null) pending.addLast(sqlError.getNextException());
            }
            if (candidate.getCause() != null) pending.addLast(candidate.getCause());
        }
        return null;
    }

    public static boolean isWriteFallbackSignal(SQLException sqlError) {
        return sqlError != null
                && (sqlError.getErrorCode() == PROXYSQL_HOSTGROUP_CONNECT_TIMEOUT
                        || isMySqlCommunicationsLinkFailure(sqlError));
    }

    public static boolean isMySqlCommunicationsLinkFailure(SQLException sqlError) {
        return sqlError != null
                && sqlError.getErrorCode() == 0
                && MYSQL_COMMUNICATIONS_LINK_FAILURE_SQL_STATE.equals(sqlError.getSQLState());
    }
}
