import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;

/** 격리 HA 외부 요청 키를 현재 Primary 원장과 대조하며 DB 변경·비밀 출력은 수행하지 않는다. */
class PermissionLedgerReconcile {
    public static void main(String[] args) throws Exception {
        Set<UUID> all=new LinkedHashSet<>();Set<UUID> acknowledged=new LinkedHashSet<>();int initial=0;
        Pattern key=Pattern.compile("\"key\":\"([a-f0-9-]{36})\"");
        Pattern status=Pattern.compile("\"status\":([0-9]+)");
        for(String line:Files.readAllLines(Path.of(System.getenv("PERMISSION_HA_LEDGER")))) {
            if(!line.contains("\"retry\":false"))continue;
            Matcher k=key.matcher(line);Matcher s=status.matcher(line);
            if(!k.find()||!s.find())throw new IllegalStateException("Malformed external ledger");
            UUID id=UUID.fromString(k.group(1));all.add(id);initial++;
            if(Integer.parseInt(s.group(1))==204)acknowledged.add(id);
        }
        if(initial!=48||all.size()!=48)throw new IllegalStateException("Expected 48 unique initial requests");
        try(Connection c=DriverManager.getConnection("jdbc:postgresql://127.0.0.1:18443/permission_test?connectTimeout=3&socketTimeout=8","postgres",System.getenv("PERMISSION_HA_PASSWORD"))) {
            c.setAutoCommit(false);
            try(var s=c.createStatement();var r=s.executeQuery("SELECT NOT pg_is_in_recovery() AND current_database()='postgres'")) {
                r.next();if(!r.getBoolean(1))throw new IllegalStateException("Isolated Primary required");
            }
            try(var s=c.prepareStatement("SELECT count(*), count(*) FILTER (WHERE r.request_id=ANY(?)), count(p.id) FROM permission_revocation_requests r LEFT JOIN document_permissions p ON p.id=r.permission_id WHERE r.request_id=ANY(?)")) {
                s.setArray(1,c.createArrayOf("uuid",acknowledged.toArray()));s.setArray(2,c.createArrayOf("uuid",all.toArray()));
                try(var r=s.executeQuery()) {
                    r.next();long total=r.getLong(1),preserved=r.getLong(2),remaining=r.getLong(3);
                    System.out.println("EVIDENCE ledger_unique_keys="+all.size()+" initial_204_keys="+acknowledged.size()+" acknowledged_preserved="+preserved+" resolved_keys="+total+" remaining_permissions="+remaining);
                    if(total!=48||preserved!=acknowledged.size()||remaining!=0)throw new IllegalStateException("Request-key reconciliation failed");
                }
            }
            c.commit();
        }
    }
}
