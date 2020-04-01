package sc.lang.sql;

import sc.lang.SemanticNodeList;

import java.util.List;
import java.util.Set;

public class AlterTable extends SQLCommand {
   public boolean ifExists;
   public boolean only;
   public SQLIdentifier tableName;
   public SemanticNodeList<AlterDef> alterDefs;

   public static AlterTable create(SQLIdentifier tableName) {
      AlterTable at = new AlterTable();
      at.tableName = tableName;
      return at;
   }

   void addTableReferences(Set<String> refTableNames) {
      if (alterDefs != null) {
         for (AlterDef def:alterDefs)
            def.addTableReferences(refTableNames);
      }
   }

   void addAlterDef(AlterDef alterDef) {
      SemanticNodeList<AlterDef> defs = alterDefs;
      boolean setProp = false;
      if (defs == null) {
         setProp = true;
         defs = new SemanticNodeList<AlterDef>();
      }
      defs.add(alterDef);
      if (setProp)
         setProperty("alterDefs", defs);
   }

   public String toDeclarationString() {
      return "alter " + tableName;
   }
}
