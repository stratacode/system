/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.JavaLanguage;

public class IntegerLiteral extends AbstractLiteral {
   private static final int DECIMAL = 0;
   private static final int OCTAL = 1;
   private static final int HEX = 2;
   private static final int BINARY = 3;

   public String hexPrefix;  // Ox or OX
   public String typeSuffix; // l or L
   public String binaryPrefix; // 0b or 0B

   // Not part of the language model directly but we need to copy it etc so it's not transient
   public int valueType;
   // Like the above, not transient because it needs to be cloned
   public long longValue;

   public static Expression create(int value) {
      return create(value, null);
   }

   public static Expression create(int value, String typeSuff) {
      // Negative numbers are not represented in the grammar so need to wrap them in a PrefixUnary expression
      IntegerLiteral il = new IntegerLiteral();
      il.valueType = DECIMAL;
      il.typeSuffix = typeSuff;

      Expression expr;
      if (value < 0) {
         value = -value;
         expr = PrefixUnaryExpression.create("-", il);
      }
      else {
         expr = il;
      }
      il.longValue = value;
      il.value = Integer.toString(value);
      return expr;
   }

   //
   // These setOctal,Hex, etc. methods are used by the JavaLanguage grammar to define the Integer literal and keep track of how
   // it should be formatted if we need to convert the value back to a value in the source.
   //

   public void setOctal(boolean o) {
      valueType = OCTAL;
   }
   public boolean getOctal() {
      return valueType == OCTAL;
   }

   public void setHexValue(String hv) {
      valueType = HEX;
      value = hv;
   }
   public String getHexValue() {
      return value;
   }

   public void setBinaryValue(String hv) {
      valueType = BINARY;
      value = hv;
   }
   public String getBinaryValue() {
      return value;
   }

   public void setOctalValue(String ov) {
      valueType = OCTAL;
      value = ov;
   }
   public String getOctalValue() {
      return value;
   }

   public void setDecimalValue(String dv) {
      valueType = DECIMAL;
      value = dv;
   }
   public String getDecimalValue() {
      return value;
   }

   public void init() {
      super.init();
      try {
         String toParse = value;
         if (toParse.contains("_")) {
            if (toParse.endsWith("_")) {
               displayError("Invalid integer literal - ends with '_'");
            }
            toParse = value.replace("_", "");
         }

         switch (valueType) {
            case HEX:
               if (toParse.startsWith("-"))
                  longValue = Long.parseLong(toParse, 16);
               // A really long negative const like: 0x9AAABBBBCCCCDDDDL will fail to parse unless we do it as unsigned
               else
                  longValue = Long.parseUnsignedLong(toParse, 16);
               break;
            case OCTAL:
               longValue = Long.parseLong(toParse, 8);
               break;
            case DECIMAL:
               longValue = Long.parseLong(toParse, 10);
               break;
            case BINARY:
               longValue = Long.parseLong(toParse, 2);
               break;
         }
      }
      catch (NumberFormatException exc) {
         displayError("NumberFormatError for: " + value + ": " + exc + " in: ");
      }
      // TODO: should enforce max constant values here
   }

   public Object getLiteralValue() {
      if (typeSuffix != null)
         return new Long(longValue);
      else
         return new Integer((int) longValue);
   }

   public long evalLong(Class expectedType, ExecutionContext ctx) {
      return longValue;
   }

   public Object getTypeDeclaration() {
      if (typeSuffix != null)
         return Long.TYPE;
      else
         return Integer.TYPE;
   }

   public String toGenerateString() {
      if (hexPrefix == null && typeSuffix == null && binaryPrefix == null && valueType == DECIMAL)
         return value;
      else {
         StringBuilder sb = new StringBuilder();
         if (hexPrefix != null)
            sb.append(hexPrefix);
         else if (binaryPrefix != null)
            sb.append(binaryPrefix);
         else if (valueType != DECIMAL) {
            if (valueType == OCTAL)
               sb.append("0");
            else if (valueType == HEX)
               sb.append("0x");
            else if (valueType == BINARY)
               sb.append("0b");
         }
         sb.append(value);
         JavaLanguage jl = getJavaLanguage();
         if (typeSuffix != null && (jl == null || jl.getSupportsLongTypeSuffix()))
            sb.append(typeSuffix);
         return sb.toString();
      }
   }

   public static void transformNumberToJS(AbstractLiteral literal) {
      if (literal.value != null && literal.value.contains("_")) {
         literal.setProperty("value", literal.value.replace("_", ""));
      }
   }

   public Statement transformToJS() {
      transformNumberToJS(this);
      return this;
   }

}
