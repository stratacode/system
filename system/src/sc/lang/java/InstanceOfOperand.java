/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

public class InstanceOfOperand extends BaseOperand {
   public JavaType rhs;

   public InstanceOfOperand() {}

   public InstanceOfOperand(String op, JavaType rhsType, boolean changeParseTree, boolean changeParent) {
      operator = op;
      setProperty("rhs", rhsType, changeParseTree, changeParent);
   }

   public Object getRhs() {
      return rhs;
   }

   public int transformTemplate(int ix, boolean statefulContext) {
      if (rhs != null)
         ix = rhs.transformTemplate(ix, statefulContext);
      return ix;
   }
}
