package sc.db;

public enum QCompare {
   Equals("=", "=="), Match(null, null), NotEquals("!=", "!="),
   GreaterThan(">", ">"), LessThan("<", "<"), GreaterThanEq(">=", ">="), LessThanEq("<=", "<=");

   String sqlOp;
   String javaOp;

   QCompare(String sqlOp, String javaOp) {
      this.sqlOp = sqlOp;
      this.javaOp = javaOp;
   }

   public static QCompare fromOperator(String op) {
      if (op.equals("=="))
         return Equals;
      else if (op.equals("!="))
         return NotEquals;
      else if (op.equals(">"))
         return GreaterThan;
      else if (op.equals("<"))
         return LessThan;
      else if (op.equals(">="))
         return GreaterThanEq;
      else if (op.equals("<="))
         return LessThanEq;
      return null;
   }
}
