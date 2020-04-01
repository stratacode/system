package sc.lang.sql;

import sc.lang.ISemanticNode;
import sc.lang.SemanticNode;
import sc.lang.SemanticNodeList;

import java.util.List;
import java.util.Set;

public class CreateIndex extends SQLCommand {
   public boolean unique;
   public boolean concurrently;
   public SQLIdentifier indexName;
   public SQLIdentifier tableName;
   public String usingMethod;
   public SemanticNodeList<BaseIndexColumn> indexColumns;
   List<SQLIdentifier> includeColumns;
   public List<WithOperand> withOpList;
   public String tableSpace;
   public SQLExpression whereClause;

   @Override
   void addTableReferences(Set<String> refTableNames) {
      if (tableName != null)
         refTableNames.add(tableName.getIdentifier());
   }

   public SQLCommand getDropCommand() {
      DropIndex dt = new DropIndex();
      dt.indexNames = new SemanticNodeList<SQLIdentifier>();
      dt.indexNames.add((SQLIdentifier) indexName.deepCopy(ISemanticNode.CopyNormal, null));
      return dt;
   }
}
