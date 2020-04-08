package sc.lang.sql;

import sc.lang.ISemanticNode;
import sc.lang.SemanticNode;
import sc.lang.SemanticNodeList;
import sc.parser.IString;

import java.util.List;
import java.util.Set;

public class CreateType extends SQLCommand {
   public SQLIdentifier typeName;
   public List<TableDef> tableDefs;

   void addTableReferences(Set<String> refTableNames) {
   }

   public boolean hasReferenceTo(SQLCommand cmd) {
      return false;
   }

   public String toDeclarationString() {
      return "type " + typeName;
   }

   public SQLCommand getDropCommand() {
      DropType dt = new DropType();
      dt.typeNames = new SemanticNodeList<SQLIdentifier>();
      dt.typeNames.add((SQLIdentifier) typeName.deepCopy(ISemanticNode.CopyNormal, null));
      return dt;
   }

   public String getIdentifier() {
      return typeName.getIdentifier();
   }
}
