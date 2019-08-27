/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.type.CTypeUtil;

public class CharacterLiteral extends AbstractLiteral {
   public char charValue;  // this needs to be non-transient even though it's not in the grammar because I believe it needs to be copied in deepCopy.

   public void init() {
      if (value != null) {
         String str = CTypeUtil.unescapeJavaString(value);
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
      cl.value = literalValue.toString();
      return cl;
   }

   public String toGenerateString() {
      return "'" + CTypeUtil.escapeJavaString(String.valueOf(charValue), '\'', false) + "'";
   }
}
