package sc.lang.sql;

import sc.util.StringUtil;

import java.util.List;
import java.util.Set;

public class DropFunction extends SQLCommand {
   public List<SQLIdentifier> funcNames;
   public boolean ifExists;
   public String dropOptions;

   void addTableReferences(Set<String> refTableNames) {
   }

   public String toDeclarationString() {
      return "drop function " + StringUtil.argsToString(funcNames);
   }
}
