/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.type.CTypeUtil;

/*
* Used to represent a String literal.  Not that "null" is NullLiteral, not a StringLiteral where stringValue = null.
* When stringValue is null, that's really the empty string.  This is because of how the parser works.
*/
public class StringLiteral extends AbstractLiteral {
   public String stringValue;

   public Object getLiteralValue() {
      if (stringValue == null)
         return "";
      return stringValue;
   }

   public Object getExprValue() {
      if (stringValue == null)
         return "";
      return '"' + stringValue + '"';
   }

   public void initialize() {
      if (initialized) return;
      super.initialize();
      if (value != null)
         stringValue = ModelUtil.unescapeJavaString(value);
   }

   public Object getTypeDeclaration() {
      return String.class;
   }

   static public Expression createNull(String val) {
      if (val == null)
         return NullLiteral.create();
      else
         return create(val);
   }

   /** Appends a raw, not escaped string to the StringLiteral. */
   public void appendString(String str) {
      if (!this.initialized)
         this.initialize();
      this.stringValue = this.stringValue + str;
      this.value = this.value + CTypeUtil.escapeJavaString(str, false);
   }

   static public StringLiteral create(String val) {
      StringLiteral res = new StringLiteral();
      res.stringValue = val;
      res.value = CTypeUtil.escapeJavaString(val, false);
      return res;
   }

   public String toGenerateString() {
      String val = value;
      if (val == null)
         val = "";
      return '"' + val + '"';
   }
}
