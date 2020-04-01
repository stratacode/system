package sc.lang.sql;

import sc.util.StringUtil;

import java.util.List;
import java.util.Set;

public class DropType extends SQLCommand {
   public List<SQLIdentifier> typeNames;
   public String dropOptions;

   void addTableReferences(Set<String> refTableNames) {
      for (SQLIdentifier nm:typeNames)
         refTableNames.add(nm.getIdentifier());
   }

   public String toDeclarationString() {
      return "drop type " + StringUtil.argsToString(typeNames);
   }
}
