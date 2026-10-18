package com.stock_spoon.river_be.dbwrite;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Only errors that establish the writer rejected the operation are replayed. */
public final class SqlWriteFailureClassifier {
    private SqlWriteFailureClassifier() {}

    public static boolean isWriterUnavailable(Throwable failure) {
        return isWriterUnavailable(failure, Set.of());
    }

    public static boolean isWriterUnavailable(Throwable failure, Set<Integer> proxySqlErrorCodes) {
        if (failure == null) return false;
        var pending = new ArrayDeque<Throwable>();
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) continue;
            if (current instanceof SQLException sql) {
                if (isWriterUnavailable(sql, proxySqlErrorCodes)) return true;
                if (sql.getNextException() != null) pending.addLast(sql.getNextException());
            }
            if (current.getCause() != null) pending.addLast(current.getCause());
        }
        return false;
    }

    private static boolean isWriterUnavailable(SQLException error, Set<Integer> proxySqlErrorCodes) {
        int code = error.getErrorCode();
        if (proxySqlErrorCodes.contains(code)) return true;
        if (code != 1290) return false;

        String message = error.getMessage();
        if (message == null) return false;
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("read only") || normalized.contains("read-only")
                || normalized.contains("read_only") || normalized.contains("super_read_only")
                || normalized.contains("super-read-only");
    }
}
