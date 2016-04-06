/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

public class FloatLiteral extends AbstractLiteral {
   // Computed during the init method by parsing 'value' - not parsed but not transient so it's cloned
   public double doubleValue;

   public static Expression create(Object value) {
      boolean negate = false;
      if (value instanceof Float) {
         Float fv = (Float) value;
         if (fv < 0) {
            value = new Float(-fv);
            negate = true;
         }
      }
      else if (value instanceof Double) {
         Double dv = (Double) value;
         if (dv < 0) {
            value = new Double(-dv);
            negate = true;
         }
      }
      FloatLiteral fl = new FloatLiteral();
      fl.value = String.valueOf(value);
      if (value instanceof Float)
         fl.value += "f";

      if (negate) {
         Expression pue = PrefixUnaryExpression.create("-", fl);
         return pue;
      }
      return fl;
   }

   public void init() {
      if (value != null) {
         try {
            String toParse = value;
            if (toParse.contains("_")) {
               if (toParse.endsWith("_")) {
                  displayError("Invalid float literal - ends with '_'");
               }
               toParse = value.replace("_", "");
            }
            doubleValue = Double.valueOf(toParse);
         }
         catch (NumberFormatException exc) {
            displayError("Invalid number value for floating point literal: ", value + ": ");
            doubleValue = 0;
         }
      }
   }

   public Object eval(Class expectedType, ExecutionContext ctx) {
      if (bindingDirection != null) {
         return super.eval(expectedType, ctx);
      }
      if (expectedType != null && ModelUtil.isFloat(expectedType))
         return (float) doubleValue;
      return getLiteralValue();
   }

   public Object getLiteralValue() {
      Object res;
      if (value != null && (value.endsWith("f") || value.endsWith("F")))
         res = (float)doubleValue;
      else
         res = doubleValue;
      return res;
   }

   public double evalDouble(Class expectedType, ExecutionContext ctx) {
      return value.endsWith("f") || value.endsWith("F") ? (double)(float)doubleValue : doubleValue;
   }

   public Object getTypeDeclaration() {
      if (value.endsWith("f") || value.endsWith("F"))
         return Float.TYPE;
      return Double.TYPE;
   }

   public Statement transformToJS() {
      IntegerLiteral.transformNumberToJS(this);
      // JS does not support the float/double suffix
      if (value.endsWith("f") || value.endsWith("F") || value.endsWith("d") || value.endsWith("D"))
         value = value.substring(0, value.length()-1);
      return this;
   }
}
