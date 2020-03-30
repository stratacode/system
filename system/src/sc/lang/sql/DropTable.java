package sc.lang.sql;

import sc.util.StringUtil;

import java.util.List;
import java.util.Set;

public class DropTable extends SQLCommand {
   public List<SQLIdentifier> tableNames;
   public String dropOptions;

   void addTableReferences(Set<String> refTableNames) {
   }

   public String toDeclarationString() {
      return "drop " + StringUtil.argsToString(tableNames);
   }
}
