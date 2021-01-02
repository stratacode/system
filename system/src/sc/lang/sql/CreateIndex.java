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

   private String getColumnNames() {
      if (indexColumns == null || indexColumns.size() == 0)
         return "_noCol";
      BaseIndexColumn ic = indexColumns.get(0);
      if (ic instanceof IndexColumn)
         return ((IndexColumn) ic).columnName.getIdentifier();
      return "_exprIndex"; // TODO what does postgres do for the index name in this case?
   }

   public SQLCommand getDropCommand() {
      DropIndex dt = new DropIndex();
      dt.indexNames = new SemanticNodeList<SQLIdentifier>();
      SQLIdentifier idxName = indexName;
      if (idxName == null) {
         idxName = SQLIdentifier.create(getDefaultIndexName());
      }
      dt.indexNames.add((SQLIdentifier) idxName.deepCopy(ISemanticNode.CopyNormal, null));
      return dt;
   }

   private String getDefaultIndexName() {
      return tableName + "_" + getColumnNames() + "_idx";
   }

   public String getIdentifier() {
      if (indexName == null) {
         return getDefaultIndexName();
      }
      return indexName.getIdentifier();
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("CREATE ");
      if (unique)
         sb.append("UNIQUE ");
      sb.append("INDEX ");
      if (concurrently)
         sb.append("CONCURRENTLY ");
      if (indexName != null)
         sb.append(indexName + " ");
      sb.append("ON ");
      sb.append(tableName);
      sb.append(" ");
      if (usingMethod != null) {
         sb.append("USING ");
         sb.append(usingMethod);
      }
      if (indexColumns != null) {
         sb.append("(");
         for (int i = 0; i < indexColumns.size(); i++) {
            if (i != 0)
               sb.append(", ");
            sb.append(indexColumns.get(i));
         }
         sb.append(")");
      }
      return sb.toString();
   }
}
