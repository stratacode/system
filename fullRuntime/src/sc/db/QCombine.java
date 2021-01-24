/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.db;

public enum QCombine {
   And, Or;

   public String getSQLOperator() {
      return name().toUpperCase();
   }

   public String getJavaOperator() {
      if (this == And)
         return "&&";
      else if (this == Or)
         return "||";
      else
         throw new IllegalArgumentException();
   }

   public static QCombine fromOperator(String op) {
      if (op.equals("&&"))
         return And;
      else if (op.equals("||"))
         return Or;
      return null;
   }
}
