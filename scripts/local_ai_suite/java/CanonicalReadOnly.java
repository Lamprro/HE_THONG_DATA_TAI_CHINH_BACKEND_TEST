import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.io.*;
import java.time.Instant;

/** Read-only repeatable-read canonical export. Never writes database rows or logs credentials. */
public class CanonicalReadOnly {
  static String resolve(String text) {
    if (text != null && text.startsWith("${") && text.endsWith("}")) {
      String key = text.substring(2, text.length()-1);
      int i=key.indexOf(':'); String fallback=i<0?"":key.substring(i+1);
      if(i>=0) key=key.substring(0,i);
      return System.getenv().getOrDefault(key,fallback);
    }
    return text;
  }
  static String csv(String value) { return value==null?"":"\""+value.replace("\"","\"\"")+"\""; }
  public static void main(String[] args) throws Exception {
    Path config=Path.of(args[0]), queryDir=Path.of(args[1]), out=Path.of(args[2]);
    Files.createDirectories(out);
    var app=new Properties(); try(var reader=Files.newBufferedReader(config,StandardCharsets.UTF_8)){app.load(reader);}
    String url=resolve(app.getProperty("spring.datasource.url"));
    var props=new Properties();
    props.setProperty("user",resolve(app.getProperty("spring.datasource.username","")));
    props.setProperty("password",resolve(app.getProperty("spring.datasource.password","")));
    props.setProperty("connectTimeout","10"); props.setProperty("socketTimeout","40");
    props.setProperty("ApplicationName","local-ai-readonly-export");
    Class.forName("org.postgresql.Driver");
    try(var conn=DriverManager.getConnection(url,props)) {
      conn.setReadOnly(true); conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); conn.setAutoCommit(false);
      try(var st=conn.createStatement();var rs=st.executeQuery("SELECT current_setting('transaction_read_only'),current_timestamp::text")) {
        rs.next(); if(!"on".equals(rs.getString(1)))throw new IllegalStateException("Read-only enforcement unavailable");
        Files.writeString(out.resolve("snapshot_time.txt"),rs.getString(2),StandardCharsets.UTF_8);
      }
      try(var paths=Files.list(queryDir)) {
        for(Path path:paths.filter(p->p.toString().endsWith(".sql")).sorted().toList()) {
          String sql=Files.readString(path,StandardCharsets.UTF_8).trim();
          if(!(sql.toUpperCase(Locale.ROOT).startsWith("SELECT")||sql.toUpperCase(Locale.ROOT).startsWith("WITH")))throw new IllegalArgumentException("Only SELECT export allowed");
          String name=path.getFileName().toString().replace(".sql","");
          try(var st=conn.createStatement()) {
            st.setQueryTimeout(35); st.setFetchSize(1000);
            try(var rs=st.executeQuery(sql);var w=Files.newBufferedWriter(out.resolve(name+".csv"),StandardCharsets.UTF_8)) {
              int n=rs.getMetaData().getColumnCount();
              for(int c=1;c<=n;c++){if(c>1)w.write(',');w.write(csv(rs.getMetaData().getColumnLabel(c)));}w.newLine();
              long rows=0;
              while(rs.next()){for(int c=1;c<=n;c++){if(c>1)w.write(',');w.write(csv(rs.getString(c)));}w.newLine();rows++;}
              System.out.println(name+": "+rows+" rows");
            }
          }
        }
      }
      conn.rollback();
      System.out.println("READ_ONLY_EXPORT_COMPLETED "+Instant.now());
    } catch(SQLException e) {
      System.err.println("DATABASE_READ_FAILED SQLSTATE="+e.getSQLState()+" TYPE="+e.getClass().getSimpleName());
      System.exit(2);
    }
  }
}
