package com.backuputil.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RestoreAdvisorTest {
    @TempDir Path temp;

    @Test
    void pickerExcludesOtherDatabasesCaseVariantsAndWrongFormats() throws Exception {
        for (String name : List.of("shop_20260709_120000_backup.sql.gz",
                "shop_20260710_120000_backup.sql.zst", "Shop_20260708_120000_backup.sql.gz",
                "other_20260709_120000_backup.sql.gz", "shop_20260709_120000_backup.bson.gz",
                "shop_20260709_120000_backup.sql.txt")) {
            Files.createFile(temp.resolve(name));
        }
        assertEquals(List.of(temp.resolve("shop_20260710_120000_backup.sql.zst"),
                        temp.resolve("shop_20260709_120000_backup.sql.gz")),
                RestoreAdvisor.listBackups(temp.toString(), "shop", "postgres"));
        assertEquals(List.of(temp.resolve("shop_20260709_120000_backup.bson.gz")),
                RestoreAdvisor.listBackups(temp.toString(), "shop", "mongo"));
    }
}
