package sc.lang.sql;

import sc.util.StringUtil;

import java.util.List;
import java.util.Set;

public class DropSequence extends SQLCommand {
   public List<SQLIdentifier> seqNames;
   public boolean ifExists;
   public String dropOptions;

   void addTableReferences(Set<String> refTableNames) {
   }

   public boolean hasReferenceTo(SQLCommand cmd) {
      return false;
   }

   public String toDeclarationString() {
      return "drop sequence " + StringUtil.argsToString(seqNames);
   }

   public String getIdentifier() {
      return null;
   }
}
