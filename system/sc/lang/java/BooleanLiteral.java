/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

public class BooleanLiteral extends AbstractLiteral {
   public Boolean boolValue;

   public void initialize() {
      boolValue = (value == null || value.equalsIgnoreCase("false") ? Boolean.FALSE : Boolean.TRUE);
   }

   public Object getLiteralValue() {
      return boolValue;
   }

   public Object getTypeDeclaration() {
      return Boolean.TYPE;
   }

   public static BooleanLiteral create(boolean value) {
      BooleanLiteral bl = new BooleanLiteral();
      if (value) {
         bl.boolValue = Boolean.TRUE;
         bl.value = "true";
      }
      else {
         bl.boolValue = Boolean.FALSE;
         bl.value = "false";
      }
      return bl;
   }
}
