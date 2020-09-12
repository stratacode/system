package sc.db;

public enum QCompare {
   Equals, Match, NotEquals;

   public static QCompare fromOperator(String op) {
      if (op.equals("=="))
         return Equals;
      else if (op.equals("!="))
         return NotEquals;
      return null;
   }
}
