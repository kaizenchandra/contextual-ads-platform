package io.contextads;

import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MigrationHistoryTest {
    @Test void deployedMigrationChecksumsMustNeverChange() throws Exception {
        // These are the actual V1–V3 checksums in the existing Compose database, not new snapshots.
        // SQL reformatting changes Flyway checksums even when its semantics are unchanged.
        for(var migration:Map.of("V1__platform.sql",160300381L,"V2__identity_inventory_daily_budget.sql",-1690407760L,"V3__fraud_billing_delivery.sql",370452549L,"V4__invoices_cash_revenue_settlements.sql",-692542901L,"V5__versioned_creatives.sql",-2000512581L).entrySet()){
            var resource=getClass().getResourceAsStream("/db/migration/"+migration.getKey());assertNotNull(resource);
            var crc=new CRC32();try(var reader=new BufferedReader(new InputStreamReader(resource,StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null)crc.update(line.getBytes(StandardCharsets.UTF_8));}
            assertEquals(migration.getValue().longValue(),(long)(int)crc.getValue(),"Applied migration must be restored, never reformatted or repaired in place: "+migration.getKey());
        }
    }
}
