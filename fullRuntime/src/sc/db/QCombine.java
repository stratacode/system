package sc.db;

enum QCombine {
   And, Or;

   String getSQLOperator() {
      return name().toUpperCase();
   }
}
