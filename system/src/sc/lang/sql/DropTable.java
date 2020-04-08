package sc.lang.sql;

import sc.util.StringUtil;

import java.util.List;
import java.util.Set;

public class DropTable extends SQLCommand {
   public List<SQLIdentifier> tableNames;
   public String dropOptions;

   void addTableReferences(Set<String> refTableNames) {
      for (SQLIdentifier nm:tableNames)
         refTableNames.add(nm.getIdentifier());
   }

   public boolean hasReferenceTo(SQLCommand cmd) {
      return false;
   }

   public String toDeclarationString() {
      return "drop table " + StringUtil.argsToString(tableNames);
   }

   public String getIdentifier() {
      return null;
   }
}
