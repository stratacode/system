/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.JavaLanguage;

public class IntegerLiteral extends AbstractLiteral {
   private static final int DECIMAL = 0;
   private static final int OCTAL = 1;
   private static final int HEX = 2;

   public String hexPrefix;  // Ox or OX
   public String typeSuffix; // l or L

   // Not part of the language model directly but we need to copy it etc so it's not transient
   public int valueType;
   long longValue;

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

   public void initialize() {
      try {
         switch (valueType) {
            case HEX:
               longValue = Long.parseLong(value, 16);
               break;
            case OCTAL:
               longValue = Long.parseLong(value, 8);
               break;
            case DECIMAL:
               longValue = Long.parseLong(value, 10);
               break;
         }
      }
      catch (NumberFormatException exc) {
         displayError("NumberFormatError for: " + value + ": " + exc + " in: ");
      }
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
      if (hexPrefix == null && typeSuffix == null && valueType == DECIMAL)
         return value;
      else {
         StringBuilder sb = new StringBuilder();
         if (hexPrefix != null)
            sb.append(hexPrefix);
         else if (valueType != DECIMAL) {
            if (valueType == OCTAL)
               sb.append("0");
            else if (valueType == HEX)
               sb.append("0x");
         }
         sb.append(value);
         JavaLanguage jl = getJavaLanguage();
         if (typeSuffix != null && (jl == null || jl.getSupportsLongTypeSuffix()))
            sb.append(typeSuffix);
         return sb.toString();
      }
   }
}
