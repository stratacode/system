/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.type.CTypeUtil;

public class CharacterLiteral extends AbstractLiteral {
   public char charValue;

   public void initialize() {
      if (value != null) {
         String str = ModelUtil.unescapeJavaString(value);
         if (str.length() == 1)
            charValue = str.charAt(0);
         else if (str.length() > 1)
            System.err.println("**** Invalid character literal: " + value);
      }
   }

   public Object getLiteralValue() {
      return new Character(charValue);
   }

   public long evalLong(Class expectedType, ExecutionContext ctx) {
      return charValue;
   }

   public Object getTypeDeclaration() {
      return Character.TYPE;
   }

   public static CharacterLiteral create(Character literalValue) {
      CharacterLiteral cl = new CharacterLiteral();
      cl.charValue = literalValue.charValue();
      return cl;
   }

   public String toGenerateString() {
      return "'" + CTypeUtil.escapeJavaString(String.valueOf(charValue), true) + "'";
   }
}
