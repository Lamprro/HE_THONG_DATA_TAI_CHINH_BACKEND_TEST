import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * Run from project root with PostgreSQL + Jackson jars on the classpath.
 * --inspect is read-only. --apply upserts only codes from validation/news-rules.json.
 * Does not modify raw payloads, validation results or data versions.
 */
class SyncNewsValidationRules {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !Set.of("--inspect", "--apply").contains(args[0]))
            throw new IllegalArgumentException("Use --inspect or --apply");
        boolean apply = "--apply".equals(args[0]);
        ObjectMapper json = new ObjectMapper();
        JsonNode entries = json.readTree(Path.of("src/main/resources/validation/news-rules.json").toFile());
        Set<String> codes = new HashSet<>();
        for (JsonNode entry : entries) {
            String code = entry.required("code").asText();
            if (!code.startsWith("NEWS_") || !codes.add(code)
                    || !Set.of("NEWS", "NEWS_DATA").contains(entry.required("domain").asText()))
                throw new IllegalArgumentException("Unexpected or duplicate news catalog entry");
        }
        Properties local = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("application-local.properties"))) { local.load(reader); }
        Properties options = new Properties();
        options.setProperty("user", local.getProperty("spring.datasource.username"));
        options.setProperty("password", local.getProperty("spring.datasource.password"));
        options.setProperty("connectTimeout", "10");
        options.setProperty("socketTimeout", "30");
        options.setProperty("options", "-c statement_timeout=15000 -c lock_timeout=5000"
                + (apply ? "" : " -c default_transaction_read_only=on"));
        try (Connection db = DriverManager.getConnection(local.getProperty("spring.datasource.url"), options)) {
            db.setReadOnly(!apply);
            db.setAutoCommit(false);
            try {
                if (apply) {
                    try (var lock = db.createStatement()) {
                        lock.execute("LOCK TABLE public.validation_rules IN SHARE ROW EXCLUSIVE MODE");
                    }
                    var backup = json.createArrayNode();
                    try (var query = db.prepareStatement("SELECT to_jsonb(r)::text FROM public.validation_rules r WHERE code=?")) {
                        for (String code : codes) {
                            query.setString(1, code);
                            try (var rows = query.executeQuery()) {
                                if (rows.next()) backup.add(json.readTree(rows.getString(1)));
                            }
                        }
                    }
                    Path backupPath = Path.of("target", "news-rules-before-" + System.currentTimeMillis() + ".json");
                    Files.createDirectories(backupPath.getParent());
                    json.writerWithDefaultPrettyPrinter().writeValue(backupPath.toFile(), backup);
                    System.out.println("Previous rule rows exported to " + backupPath);
                    int inserted = 0, updated = 0;
                    try (var existing = db.prepareStatement("SELECT data_domain,executor_key FROM public.validation_rules WHERE code=?");
                         var update = db.prepareStatement("""
                             UPDATE public.validation_rules SET name=?,data_domain=?,severity=?,rule_type=?,
                             rule_config=?::jsonb,description=?,executor_key=?,is_active=true,updated_at=now()
                             WHERE code=?
                             """);
                         var insert = db.prepareStatement("""
                             INSERT INTO public.validation_rules
                             (name,data_domain,severity,rule_type,rule_config,description,executor_key,code,
                              is_active,created_at,updated_at)
                             VALUES (?,?,?,?,?::jsonb,?,?,?,true,now(),now())
                             """)) {
                        for (JsonNode entry : entries) {
                            String code = entry.path("code").asText();
                            existing.setString(1, code);
                            boolean found;
                            try (var row = existing.executeQuery()) {
                                found = row.next();
                                if (found && (!entry.path("domain").asText().equals(row.getString(1))
                                        || !entry.path("executor").asText().equals(row.getString(2))))
                                    throw new IllegalStateException("Conflicting existing rule: " + code);
                            }
                            PreparedStatement write = found ? update : insert;
                            write.setString(1, entry.path("name").asText());
                            write.setString(2, entry.path("domain").asText());
                            write.setString(3, entry.path("severity").asText());
                            write.setString(4, entry.path("type").asText());
                            write.setString(5, entry.path("config").toString());
                            write.setString(6, entry.path("description").asText());
                            write.setString(7, entry.path("executor").asText());
                            write.setString(8, code);
                            if (write.executeUpdate() != 1) throw new IllegalStateException("Unexpected row count for " + code);
                            if (found) updated++; else inserted++;
                        }
                    }
                    // Validate DB values before committing the transaction.
                    try (var query = db.prepareStatement("SELECT name,data_domain,severity,rule_type,rule_config::text,description,executor_key,is_active FROM public.validation_rules WHERE code=?")) {
                        for (JsonNode entry : entries) {
                            query.setString(1, entry.path("code").asText());
                            try (var row = query.executeQuery()) {
                                if (!row.next()) throw new IllegalStateException("Missing synced rule");
                                String[] keys = {"name","domain","severity","type","config","description","executor"};
                                for (int i=0;i<keys.length;i++) {
                                    boolean equal = i==4 ? entry.path("config").equals(json.readTree(row.getString(5)))
                                            : entry.path(keys[i]).asText().equals(row.getString(i+1));
                                    if (!equal) throw new IllegalStateException("Synced rule differs from catalog");
                                }
                                if (!row.getBoolean(8) || row.next()) throw new IllegalStateException("Invalid synced rule state");
                            }
                        }
                    }
                    db.commit();
                    System.out.println("COMMITTED: inserted=" + inserted + ", updated=" + updated);
                }
                try (var query = db.createStatement();
                     var rows = query.executeQuery("SELECT code,data_domain,severity,executor_key,is_active FROM public.validation_rules WHERE data_domain IN ('NEWS','NEWS_DATA') ORDER BY data_domain,code")) {
                    while (rows.next()) System.out.println(rows.getString(1) + " | " + rows.getString(2)
                            + " | " + rows.getString(3) + " | " + rows.getString(4) + " | active=" + rows.getBoolean(5));
                }
            } finally { db.rollback(); }
        } catch (SQLException e) {
            System.err.println("DB sync failed (transaction rolled back if not committed), SQLState=" + e.getSQLState());
            System.exit(1);
        }
    }
}
