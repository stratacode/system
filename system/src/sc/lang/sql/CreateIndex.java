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

   public boolean hasReferenceTo(SQLCommand cmd) {
      if (cmd instanceof CreateTable) {
         CreateTable cr = (CreateTable) cmd;
         if (cr.tableName.getIdentifier().equals(tableName.getIdentifier()))
            return true;
      }
      return false;
   }

   public SQLCommand getDropCommand() {
      DropIndex dt = new DropIndex();
      dt.indexNames = new SemanticNodeList<SQLIdentifier>();
      dt.indexNames.add((SQLIdentifier) indexName.deepCopy(ISemanticNode.CopyNormal, null));
      return dt;
   }

   public String getIdentifier() {
      return indexName.getIdentifier();
   }
}
