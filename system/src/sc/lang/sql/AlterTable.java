/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

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

   public boolean hasReferenceTo(SQLCommand other) {
      if (tableName == null)
         return false;
      if (other instanceof CreateTable)  {
         CreateTable otherTable = (CreateTable) other;
         if (otherTable.tableName == null)
            return false;
         return otherTable.tableName.getIdentifier().equals(tableName.getIdentifier());
      }
      if (alterDefs != null) {
         for (AlterDef def:alterDefs)
            if (def.hasReferenceTo(other))
               return true;
      }
      return false;
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

   public String getIdentifier() {
      return null; // Returning null here because we are not going to try and 'merge' alter table statements like we do with 'create'
   }
}
