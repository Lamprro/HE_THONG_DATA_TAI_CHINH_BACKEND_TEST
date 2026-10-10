package com.hethongdata.taichinh.support;

import java.sql.Connection;
import java.util.ArrayList;

/** LIKE INCLUDING ALL can retain public serial defaults; detach them before fixture writes. */
public final class IsolatedSchemaSupport {
    private IsolatedSchemaSupport() {}

    public static void detachSequences(Connection connection, String schema) throws Exception {
        if (!schema.matches("[a-z][a-z0-9_]*_test_[a-f0-9]{32}"))
            throw new IllegalArgumentException("Expected a disposable test schema");
        var defaults = new ArrayList<String[]>();
        try (var query = connection.prepareStatement("SELECT table_name,column_name FROM "
                + "information_schema.columns WHERE table_schema=? AND column_default LIKE 'nextval%'")) {
            query.setString(1, schema);
            try (var rows = query.executeQuery()) {
                while (rows.next()) defaults.add(new String[] {rows.getString(1), rows.getString(2)});
            }
        }
        try (var sql = connection.createStatement()) {
            for (var pair : defaults) {
                String table = pair[0], column = pair[1];
                if (!table.matches("[a-z_]+") || !column.matches("[a-z_]+"))
                    throw new IllegalArgumentException("Unexpected SQL identifier");
                long next;
                try (var rows = sql.executeQuery("SELECT COALESCE(MAX(" + column + "),0)+1 FROM "
                        + schema + "." + table)) { rows.next(); next = rows.getLong(1); }
                String sequence = schema + ".fixture_" + table + "_" + column;
                sql.execute("CREATE SEQUENCE " + sequence + " START " + next);
                sql.execute("ALTER TABLE " + schema + "." + table + " ALTER COLUMN " + column
                        + " SET DEFAULT nextval('" + sequence + "')");
            }
        }
    }
}
