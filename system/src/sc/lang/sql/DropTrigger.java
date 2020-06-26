package sc.lang.sql;

import java.util.Set;

public class DropTrigger extends SQLCommand {
   public boolean ifExists;
   public SQLIdentifier triggerName;
   public SQLIdentifier tableName;
   public String dropOptions;

   public void addTableReferences(Set<String> refTableNames) {
   }

   public boolean hasReferenceTo(SQLCommand cmd) {
      return false;
   }

   public String toDeclarationString() {
      return "drop trigger " + triggerName + " on " + tableName;
   }

   public String getIdentifier() {
      return null;
   }
}
