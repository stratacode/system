package sc.lang.sql;

import sc.lang.SemanticNode;

public class IndexColumnExpr extends BaseIndexColumn {
   public SQLExpression expression;

   public static IndexColumnExpr create(SQLExpression expr) {
      IndexColumnExpr ice = new IndexColumnExpr();
      ice.setProperty("expression", expr);
      return ice;
   }
}
