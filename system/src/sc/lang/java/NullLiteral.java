/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

public class NullLiteral extends AbstractLiteral {
   public Object getLiteralValue() { return null; }

   public static String NULL_TYPE = "<null>";

   public Object getTypeDeclaration() {
      return NULL_TYPE;
   }

   public static NullLiteral create() {
      return new NullLiteral();
   }

   public Class getRuntimeClass() {
      return null;
   }

   public Object eval(Class expectedClass, ExecutionContext ctx) {
      if (bindingDirection != null)
         return super.eval(expectedClass, ctx);
      return null;
   }

   public String toGenerateString() {
      return "null";
   }
}
