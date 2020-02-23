package sc.lang.sql;

import java.util.List;
import java.util.Set;

public class DropTable extends SQLCommand {
   public List<SQLIdentifier> tableNames;
   public String dropOptions;

   void addTableReferences(Set<String> refTableNames) {
   }
}
