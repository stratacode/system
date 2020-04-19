package sc.lang.sql;

import sc.lang.ISemanticNode;

public class AlterSetType extends AlterCmd {
   public SQLDataType columnType;
   public Collation collation;
   public SQLExpression usingExpression;

   public static AlterSetType create(SQLDataType columnType) {
      AlterSetType res = new AlterSetType();
      res.columnType = (SQLDataType) columnType.deepCopy(ISemanticNode.CopyNormal, null);
      return res;
   }
}
