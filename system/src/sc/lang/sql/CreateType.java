package sc.lang.sql;

import sc.lang.SemanticNode;
import sc.parser.IString;

import java.util.List;
import java.util.Set;

public class CreateType extends SQLCommand {
   public SQLIdentifier typeName;
   public List<TableDef> tableDefs;

   void addTableReferences(Set<String> refTableNames) {
   }

   public String toDeclarationString() {
      return "type " + typeName;
   }
}
