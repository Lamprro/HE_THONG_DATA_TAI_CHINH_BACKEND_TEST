import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Legacy name retained for read-only inspection; manage rules through DB/admin, not a JSON file. */
class SyncNewsValidationRules {
    public static void main(String[] args) throws Exception {
        if(args.length!=1 || !"--inspect".equals(args[0]))
            throw new IllegalArgumentException("Only --inspect is supported. Initialize NEWS rules with V20261004_04__news_validation_catalog.sql; manage existing rules in DB/admin.");
        Properties local=new Properties();
        try(var reader=Files.newBufferedReader(Path.of("application-local.properties"))) {local.load(reader);}
        Properties options=new Properties();
        options.setProperty("user",local.getProperty("spring.datasource.username"));
        options.setProperty("password",local.getProperty("spring.datasource.password"));
        options.setProperty("options","-c default_transaction_read_only=on -c statement_timeout=15000");
        try(var db=DriverManager.getConnection(local.getProperty("spring.datasource.url"),options);
            var query=db.createStatement();
            var rows=query.executeQuery("SELECT code,data_domain,severity,is_active,executor_key,rule_config FROM validation_rules WHERE data_domain IN ('NEWS','NEWS_DATA') ORDER BY code")) {
            while(rows.next()) System.out.println(rows.getString(1)+" | "+rows.getString(2)+" | "+rows.getString(3)+" | "+rows.getBoolean(4)+" | "+rows.getString(5)+" | "+rows.getString(6));
        }
    }
}
