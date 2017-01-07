/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.type;

import sc.dyn.DynUtil;
import sc.js.JSSettings;

import java.util.HashMap;
import java.util.Set;

@JSSettings(jsModuleFile="js/scgen.js")
public enum Type {
   Boolean {
      public Object evalArithmetic(String operator, Object lhs, Object rhs) {
         throw new IllegalArgumentException("Illegal arithmetic operation for a boolean expression");
      }

      public Boolean evalPreConditional(String operator, Object lhsObj) {
         char c = operator.charAt(0);
         boolean lhsBool = ((Boolean) lhsObj);
         switch (c) {
            case '&':
               if (!lhsBool)
                  return java.lang.Boolean.FALSE;
               break;
            case '|':
               if (lhsBool)
                  return java.lang.Boolean.TRUE;
               break;
         }
         return null;
      }

      public boolean evalConditional(String operator, Object lhsObj, Object rhsObj) {
         boolean result;
         boolean lhsBool = ((Boolean) lhsObj);
         boolean rhsBool = ((Boolean) rhsObj);

         char c = operator.charAt(0);

         switch (c) {
            case '=':
               result = lhsBool == rhsBool;
               break;
            case '!':
               result = lhsBool != rhsBool;
               break;
            case '&':
               result = lhsBool && rhsBool;
               break;
            case '|':
               result = lhsBool || rhsBool;
               break;
            case 'i':
               result = DynUtil.instanceOf(lhsObj, rhsObj);
               break;
            case '>':
            case '<':
            default:
               throw new IllegalArgumentException("Operator: " + operator + " cannot be applied to boolean operands");
         }
         return result;
      }
      public Object evalUnary(String operator, Object value) {
         switch (operator.charAt(0)) {
            case '!':
               return ((Boolean) value) ? java.lang.Boolean.FALSE : java.lang.Boolean.TRUE;
            case '~':
            case '+':
            case '-':
            default:
               throw new IllegalArgumentException("Boolean type does not support unary operator: " + operator);
         }
      }
      public Object getDefaultObjectValue() {
         return java.lang.Boolean.FALSE;
      }
   },
   Byte {
      public Object evalArithmetic(String operator, Object lhsObj, Object rhsObj) {
         return Integer.evalArithmetic(operator, ((Number) lhsObj).intValue(), ((Number) rhsObj).intValue());
      }
      public boolean evalConditional(String operator, Object lhs, Object rhs) {
         return Integer.evalConditional(operator, ((Number) lhs).intValue(), ((Number) rhs).intValue());
      }
      public Object evalUnary(String operator, Object value) {
         throw new IllegalArgumentException("Byte type does not support unary operator: " + operator);
      }
      public Object evalCast(Class theClass, Object val) {
         return ((Number) val).byteValue();
      }
      public boolean isAssignableFromParameter(Type other, Class from, Class to) {
         return other.isByte();
      }
      public boolean isAssignableFromAssignment(Type other, Class from, Class to) {
         return Integer.isAssignableFromAssignment(other, from, to);
      }
      public Object getDefaultObjectValue() {
         return IntegerZero;
      }
   },
   Short {
      public Object evalArithmetic(String operator, Object lhsObj, Object rhsObj) {
         return Integer.evalArithmetic(operator, ((Number) lhsObj).intValue(), ((Number) rhsObj).intValue());
      }
      public boolean evalConditional(String operator, Object lhs, Object rhs) {
         return Integer.evalConditional(operator, ((Number) lhs).intValue(), ((Number) rhs).intValue());
      }
      public Object evalUnary(String operator, Object value) {
         throw new IllegalArgumentException("Short type does not support unary operator: " + operator);
      }
      public Object evalCast(Class theClass, Object val) {
         return ((Number) val).shortValue();
      }
      public boolean isAssignableFromParameter(Type other, Class from, Class to) {
         return other.isShort();
      }
      public boolean isAssignableFromAssignment(Type other, Class from, Class to) {
         return Integer.isAssignableFromAssignment(other, from, to);
      }
      public Object getDefaultObjectValue() {
         return IntegerZero;
      }
   }, Character {
      public Object evalArithmetic(String operator, Object lhsObj, Object rhsObj) {
         throw new IllegalArgumentException("Illegal arithmetic operation for a char expression");
      }
      public boolean evalConditional(String operator, Object lhs, Object rhs) {
         throw new IllegalArgumentException("Illegal conditional operation for a short expression");
      }
      public Object evalUnary(String operator, Object value) {
         throw new IllegalArgumentException("Character type does not support unary operator: " + operator);
      }
      public boolean isAssignableFromAssignment(Type other, Class from, Class to) {
         return other.isAnInteger() || other == Character;
      }
      public Object getDefaultObjectValue() {
         return '\0';
      }
   },
   Integer {
      public Object evalArithmetic(String operator, Object lhsObj, Object rhsObj) {
         char c = operator.charAt(0);
         int result;
         int lhs = ((Number) lhsObj).intValue();
         int rhs = ((Number) rhsObj).intValue();
         switch (c) {
            case '*':
               result = lhs * rhs;
               break;
            case '+':
               result = lhs + rhs;
               break;
            case '-':
               result = lhs - rhs;
               break;
            case '/':
               result = lhs / rhs;
               break;
            case '%':
               result = lhs % rhs;
               break;
            case '&':
               result = lhs & rhs;
               break;
            case '|':
               result = lhs | rhs;
               break;
            case '^':
               result = lhs ^ rhs;
               break;
            case '<':
               result = lhs << rhs;
               break;
            case '>':
               if (operator.length() == 2)
                  result = lhs >> rhs;
               else
                  result = lhs >>> rhs;
               break;
            default:
               throw new UnsupportedOperationException();
        }
        return result;
      }
      public boolean evalConditional(String operator, Object lhsObj, Object rhsObj) {
         int lhsInt, rhsInt;
         boolean result;

         // Could be comparing and Integer to a general Object.   The lhs should be a number because we select
         // this method based on its type.
         if (!(rhsObj instanceof Number))
            return false;

         lhsInt = ((Number) lhsObj).intValue();
         rhsInt = ((Number) rhsObj).intValue();

         char c = operator.charAt(0);

         switch (c) {
            case '=':
               result = lhsInt == rhsInt;
               break;
            case '!':
               result = lhsInt != rhsInt;
               break;
            case '<':
               if (operator.length() == 2)
                  result = lhsInt <= rhsInt;
               else
                  result = lhsInt < rhsInt;
               break;
            case '>':
               if (operator.length() == 2)
                  result = lhsInt >= rhsInt;
               else
                  result = lhsInt > rhsInt;
               break;
            case 'i':
               result = DynUtil.instanceOf(lhsObj, rhsObj);
               break;
            case '&':
            case '|':
            default:
               throw new IllegalArgumentException("Operator: " + operator + " cannot be applied to integer operands");
         }
         return result;
      }
      public Object evalUnary(String operator, Object value) {
         switch (operator.charAt(0)) {
            case '~':
               return ~((Number) value).intValue();
            case '+':
               if (operator.length() == 2)
                  return ((Number) value).intValue() + 1;
               return value;
            case '-':
               if (operator.length() == 2)
                  return ((Number) value).intValue() - 1;
               return -((Number)value).intValue();
            case '!':
            default:
               throw new IllegalArgumentException("Operator: " + operator + " cannot be applied to integer operands");
         }
      }
      public Object evalCast(Class theClass, Object val) {
         return ((Number) val).intValue();
      }
      public boolean isAssignableFromParameter(Type other, Class from, Class to) {
         return other.isInteger() || other == Character;
      }
      public boolean isAssignableFromAssignment(Type other, Class from, Class to) {
         return (other.isANumber() || other == Character) && !other.isAFloat() && !other.isLong();
      }
      public Object getDefaultObjectValue() {
         return IntegerZero;
      }
   },
   Float {
      public Object evalArithmetic(String operator, Object lhsObj, Object rhsObj) {
         float result;
         float lhs = ((Number) lhsObj).floatValue();
         float rhs = ((Number) rhsObj).floatValue();

         char c = operator.charAt(0);

         switch (c) {
            case '*':
               result = lhs * rhs;
               break;
            case '+':
               result = lhs + rhs;
               break;
            case '-':
               result = lhs - rhs;
               break;
            case '/':
               result = lhs / rhs;
               break;
            case '%':
               result = lhs % rhs;
               break;
            case '&':
            case '|':
            case '^':
            case '<':
            case '>':
               throw new IllegalArgumentException("Operator: " + operator + " cannot be applied to floating point operations");

            default:
               throw new UnsupportedOperationException();
         }
         return result;
      }
      public boolean evalConditional(String operator, Object lhsObj, Object rhsObj) {
         float lhsFloat = ((Number) lhsObj).floatValue();
         float rhsFloat = ((Number) rhsObj).floatValue();
         boolean result;

         char c = operator.charAt(0);

         switch (c) {
            case '=':
               result = lhsFloat == rhsFloat;
               break;
            case '!':
               result = lhsFloat != rhsFloat;
               break;
            case '<':
               if (operator.length() == 2)
                  result = lhsFloat <= rhsFloat;
               else
                  result = lhsFloat < rhsFloat;
               break;
            case '>':
               if (operator.length() == 2)
                  result = lhsFloat >= rhsFloat;
               else
                  result = lhsFloat > rhsFloat;
               break;
            case 'i':
               result = DynUtil.instanceOf(lhsObj, rhsObj);
               break;
            case '&':
            case '|':
            default:
               throw new IllegalArgumentException("Operator: " + operator + " cannot be applied to floating point operands ");
         }
         return result;
      }
      public Object evalUnary(String operator, Object value) {
         switch (operator.charAt(0)) {
            case '+':
               if (operator.length() == 2)
                  return ((Number)value).floatValue() + 1.0f;
               return value;
            case '-':
               if (operator.length() == 2)
                  return ((Number)value).floatValue() - 1.0f;
               return -((Number)value).floatValue();
            case '!':
            case '~':
            default:
               throw new IllegalArgumentException("Operator: " + operator + " cannot be applied to float operands");
         }
      }
      public Object evalCast(Class theClass, Object val) {
         return ((Number) val).floatValue();
      }

      public boolean isAssignableFromParameter(Type other, Class from, Class to) {
         return other.isANumber() && !other.isDouble();
      }
      public Object getDefaultObjectValue() {
         return FloatZero;
      }
   },
   Long {
      public Object evalArithmetic(String operator, Object lhsObj, Object rhsObj) {
         char c = operator.charAt(0);
         long result;
         long lhs = ((Number) lhsObj).longValue();
         long rhs = ((Number) rhsObj).longValue();
         switch (c) {
            case '*':
               result = lhs * rhs;
               break;
            case '+':
               result = lhs + rhs;
               break;
            case '-':
               result = lhs - rhs;
               break;
            case '/':
               result = lhs / rhs;
               break;
            case '%':
               result = lhs % rhs;
               break;
            case '&':
               result = lhs & rhs;
               break;
            case '|':
               result = lhs | rhs;
               break;
            case '^':
               result = lhs ^ rhs;
               break;
            case '<':
               result = lhs << rhs;
               break;
            case '>':
               if (operator.length() == 2)
                  result = lhs >> rhs;
               else
                  result = lhs >>> rhs;
               break;
            default:
               throw new UnsupportedOperationException();
         }
         return result;
      }
      public boolean evalConditional(String operator, Object lhsObj, Object rhsObj) {
         boolean result;

         long lhs = ((Number) lhsObj).intValue();
         long rhs = ((Number) rhsObj).intValue();

         char c = operator.charAt(0);

         switch (c) {
            case '=':
               result = lhs == rhs;
               break;
            case '!':
               result = lhs != rhs;
               break;
            case '<':
               if (operator.length() == 2)
                  result = lhs <= rhs;
               else
                  result = lhs < rhs;
               break;
            case '>':
               if (operator.length() == 2)
                  result = lhs >= rhs;
               else
                  result = lhs > rhs;
               break;
            case 'i':
               result = DynUtil.instanceOf(lhsObj, rhsObj);
               break;
            case '&':
            case '|':
            default:
               throw new IllegalArgumentException("Operator: " + operator + " cannot be applied to long operands");
         }
         return result;
      }
      public Object evalUnary(String operator, Object value) {
         switch (operator.charAt(0)) {
            case '~':
               return ~((Number) value).longValue();
            case '+':
               if (operator.length() == 2)
                  return ((Number) value).longValue() + 1;
               return value;
            case '-':
               if (operator.length() == 2)
                  return ((Number) value).longValue() - 1;
               return -((Number)value).longValue();
            case '!':
            default:
               throw new IllegalArgumentException("Operator: " + operator + " cannot be applied to integer operands");
         }
      }
      public Object evalCast(Class theClass, Object val) {
         return ((Number) val).longValue();
      }

      public boolean isAssignableFromParameter(Type other, Class from, Class to) {
         return other.isAnInteger();
      }
      public boolean isAssignableFromAssignment(Type other, Class from, Class to) {
         return other.isANumber() && !other.isAFloat();
      }
      public Object getDefaultObjectValue() {
         return 0L;
      }
   },
   Double {
      public Object evalArithmetic(String operator, Object lhsObj, Object rhsObj) {
         double result;
         double lhs = ((Number) lhsObj).doubleValue();
         double rhs = ((Number) rhsObj).doubleValue();

         char c = operator.charAt(0);

         switch (c) {
            case '*':
               result = lhs * rhs;
               break;
            case '+':
               result = lhs + rhs;
               break;
            case '-':
               result = lhs - rhs;
               break;
            case '/':
               result = lhs / rhs;
               break;
            case '%':
               result = lhs % rhs;
               break;
            case '&':
            case '|':
            case '^':
            case '<':
            case '>':
               throw new IllegalArgumentException("Operator: " + operator + " cannot be applied to floating point operations");

            default:
               throw new UnsupportedOperationException();
         }
         return result;
      }
      public boolean evalConditional(String operator, Object lhsObj, Object rhsObj) {
         double lhs = ((Number) lhsObj).doubleValue();
         double rhs = ((Number) rhsObj).doubleValue();
         boolean result;

         char c = operator.charAt(0);

         switch (c) {
            case '=':
               result = lhs == rhs;
               break;
            case '!':
               result = lhs != rhs;
               break;
            case '<':
               if (operator.length() == 2)
                  result = lhs <= rhs;
               else
                  result = lhs < rhs;
               break;
            case '>':
               if (operator.length() == 2)
                  result = lhs >= rhs;
               else
                  result = lhs > rhs;
               break;
            case 'i':
               result = DynUtil.instanceOf(lhsObj, rhsObj);
               break;
            case '&':
            case '|':
            default:
               throw new IllegalArgumentException("Operator: " + operator + " cannot be applied to floating point operands ");
         }
         return result;
      }
      public Object evalUnary(String operator, Object value) {
         switch (operator.charAt(0)) {
            case '+':
               if (operator.length() == 2)
                  return ((Number)value).doubleValue() + 1.0;
               return value;
            case '-':
               if (operator.length() == 2)
                  return ((Number)value).doubleValue() - 1.0;
               return -((Number)value).doubleValue();
            case '!':
            case '~':
            default:
               throw new IllegalArgumentException("Operator: " + operator + " cannot be applied to double operands");
         }
      }
      public Object evalCast(Class theClass, Object val) {
         return ((Number) val).doubleValue();
      }

      public boolean isAssignableFromParameter(Type other, Class from, Class to) {
         return other.isANumber();
      }
      public Object getDefaultObjectValue() {
         return DoubleZero;
      }
   },
   String {
      public Object evalArithmetic(String operator, Object lhsObj, Object rhsObj) {
         char c = operator.charAt(0);
         if (c == '+') {
            String lhsStr = lhsObj == null ? "null" : lhsObj.toString();
            String rhsStr = rhsObj == null ? "null" : rhsObj.toString();
            return lhsStr + rhsStr;
         }
         throw new IllegalArgumentException("Illegal operator for non-number objects: " + operator);
      }
      public boolean evalConditional(String operator, Object lhsObj, Object rhsObj) {
         char c = operator.charAt(0);
         boolean result;
         switch (c) {
            case '=':
               result = lhsObj == rhsObj;
               break;
            case '!':
               result = lhsObj != rhsObj;
               break;
            case 'i':
               result = DynUtil.instanceOf(lhsObj, rhsObj);
               break;
            case '<':
            case '>':
            case '&':
            case '|':
            default:
               throw new IllegalArgumentException("Operator: " + operator + " cannot be applied to object operands");
         }
         return result;
      }
      public Object evalUnary(String operator, Object value) {
         throw new IllegalArgumentException("String type does not support unary operator: " + operator);
      }
   },
   Object {
      public Object evalArithmetic(String operator, Object lhsObj, Object rhsObj) {
         throw new IllegalArgumentException("Illegal operator for non-number objects: " + operator);
      }
      public boolean evalConditional(String operator, Object lhsObj, Object rhsObj) {
         char c = operator.charAt(0);
         boolean result;
         switch (c) {
            case '=':
               result = lhsObj == rhsObj;
               break;
            case '!':
               result = lhsObj != rhsObj;
               break;
            case 'i':
               result = DynUtil.instanceOf(lhsObj, rhsObj);
               break;
            case '|':
            case '&':
            case '<':
            case '>':
            default:
               if (rhsObj == null)
                  return false;
               throw new IllegalArgumentException("Operator: " + operator + " cannot be applied to object operands");
         }
         return result;
      }
      public Object evalUnary(String operator, Object value) {
         throw new IllegalArgumentException("Object type does not support unary operator: " + operator);
      }

      public boolean isAssignableFromParameter(Type other, Class from, Class to) {
         return true;
      }
   },
   Number {
      public Object evalArithmetic(String operator, Object lhsObj, Object rhsObj) {
         Type lhsType = Type.get(lhsObj.getClass());
         Type rhsType = Type.get(rhsObj.getClass());
         if (lhsType.isAnInteger() && rhsType.isAnInteger())
            return Long.evalArithmetic(operator, lhsObj, rhsObj);
         else
            return Double.evalArithmetic(operator, lhsObj, rhsObj);
      }
      public boolean evalConditional(String operator, Object lhsObj, Object rhsObj) {
         Type lhsType = Type.get(lhsObj.getClass());
         Type rhsType = Type.get(rhsObj.getClass());
         if (lhsType.isAnInteger() && rhsType.isAnInteger())
            return Long.evalConditional(operator, lhsObj, rhsObj);
         else
            return Double.evalConditional(operator, lhsObj, rhsObj);
      }
      public Object evalUnary(String operator, Object value) {
         Type valueType = Type.get(value.getClass());
         if (valueType.isAnInteger())
            return Long.evalUnary(operator, value);
         else
            return Double.evalUnary(operator, value);
      }
      public boolean isAssignableFromParameter(Type other, Class from, Class to) {
         return other.isANumber();
      }
   },
   Void {
      public Object evalArithmetic(String operator, Object lhsObj, Object rhsObj) {
         throw new IllegalArgumentException("Arithmetic not permitted on void");
      }
      public boolean evalConditional(String operator, Object lhsObj, Object rhsObj) {
         throw new IllegalArgumentException("Conditional ops not permitted on void");
      }
      public Object evalUnary(String operator, Object value) {
         throw new IllegalArgumentException("Unary ops not permitted on void");
      }
      public boolean isAssignableFromParameter(Type other, Class from, Class to) {
         return other.isVoid();
      }
      public boolean isVoid() {
         return true;
      }
   };


   static HashMap<Class,Type> classToTypeIndex = new HashMap<Class,Type>();
   static HashMap<String,Type> primitiveIndex = new HashMap<String,Type>();
   static HashMap<String,Type> arrayTypeIndex = new HashMap<String,Type>();
   static {
      classToTypeIndex.put(java.lang.Object.class, Object);
      classToTypeIndex.put(java.lang.String.class, String);
      classToTypeIndex.put(java.lang.Integer.class, Integer);
      classToTypeIndex.put(java.lang.Long.class, Long);
      classToTypeIndex.put(java.lang.Short.class, Short);
      classToTypeIndex.put(java.lang.Byte.class, Byte);
      classToTypeIndex.put(java.lang.Character.class, Character);
      classToTypeIndex.put(java.lang.Double.class, Double);
      classToTypeIndex.put(java.lang.Float.class, Float);
      classToTypeIndex.put(java.lang.Number.class, Number);
      classToTypeIndex.put(java.lang.Boolean.class, Boolean);
      classToTypeIndex.put(java.lang.Void.class, Void);
      primitiveIndex.put("boolean", Boolean);
      arrayTypeIndex.put("Z", Boolean);
      Boolean.arrayTypeCode = 'Z';
      primitiveIndex.put("int", Integer);
      arrayTypeIndex.put("I", Integer);
      Integer.arrayTypeCode = 'I';
      primitiveIndex.put("long", Long);
      arrayTypeIndex.put("J", Long);
      Long.arrayTypeCode = 'J';
      primitiveIndex.put("short", Short);
      arrayTypeIndex.put("S", Short);
      Short.arrayTypeCode = 'S';
      primitiveIndex.put("char", Character);
      arrayTypeIndex.put("C", Character);
      Character.arrayTypeCode = 'C';
      primitiveIndex.put("byte", Byte);
      arrayTypeIndex.put("B", Byte);
      Byte.arrayTypeCode = 'B';
      primitiveIndex.put("float", Float);
      arrayTypeIndex.put("F", Float);
      Float.arrayTypeCode = 'F';
      primitiveIndex.put("double", Double);
      arrayTypeIndex.put("D", Double);
      Double.arrayTypeCode = 'D';
      primitiveIndex.put("void", Void);
      arrayTypeIndex.put("V", Void);
      Void.arrayTypeCode = 'V';

      arrayTypeIndex.put("L", Object);
      Object.arrayTypeCode = 'L';
   }

   private HashMap<Integer,Class> arrayClassCache;
   private HashMap<Integer,Class> primArrayClassCache;

   private Class objectClass;

   public Class primitiveClass;

   public char arrayTypeCode;

   private final static String NOT_IMPL_ERROR = "Error - method not implemented in coreRuntime - make sure dependency on the fullRuntime is ahead in the classpath";

   public Class getArrayClass(Class componentType, int ndims) {
      System.err.println(NOT_IMPL_ERROR);
      throw new UnsupportedOperationException(NOT_IMPL_ERROR);
   }

   public Class getPrimitiveArrayClass(int ndims) {
      System.err.println(NOT_IMPL_ERROR);
      throw new UnsupportedOperationException(NOT_IMPL_ERROR);
   }


   public static Type get(Class cl) {
      Type t = classToTypeIndex.get(cl);
      if (t == null)
         t = Object;
      return t;
   }

   public static Type getPrimitiveType(String primName) {
      return primitiveIndex.get(primName);
   }

   public static Set<String> getPrimitiveTypeNames() {
      return primitiveIndex.keySet();
   }

   public static Type getArrayType(String arrayTypeName) {
      return arrayTypeIndex.get(arrayTypeName);
   }

   public Object getDefaultObjectValue() {
      return null;
   }

   public abstract Object evalArithmetic(String operator, Object lhs, Object rhs);

   public boolean evalConditional(String operator, Object lhs, Object rhs) {
      if (operator.equals("instanceof"))
         return DynUtil.instanceOf(lhs, rhs);
      throw new UnsupportedOperationException(NOT_IMPL_ERROR);
   }

   public abstract Object evalUnary(String operator, Object arg);

   /** Use this to avoid evaluating the second arg to an && or || statement if the first one evals to false or true */
   public Boolean evalPreConditional(String operator, Object lhsObj) {
      return null;
   }

   /** Takes the class used to get this type as the first argument and implements the cast operator on the value */
   public Object evalCast(Class theClass, Object val) {
      throw new UnsupportedOperationException(NOT_IMPL_ERROR);
      /*
      if (theClass.isInstance(val)) {
         return val;
      }
      throw new ClassCastException("Value " + val + " of type: " + (val == null ? "null" : val.getClass()) + " cannot be cast to: " + theClass);
      */
   }

   public boolean isANumber() {
      return primitiveClass != null && (isAnInteger() || isAFloat());
   }

   public boolean isAnInteger() {
      return isInteger() || isShort() || isByte() || isLong();
   }

   public boolean isInteger() {
      throw new UnsupportedOperationException(NOT_IMPL_ERROR);
   }

   public boolean isShort() {
      throw new UnsupportedOperationException(NOT_IMPL_ERROR);
   }

   public boolean isByte() {
      throw new UnsupportedOperationException(NOT_IMPL_ERROR);
   }

   public boolean isLong() {
      throw new UnsupportedOperationException(NOT_IMPL_ERROR);
   }

   public boolean isFloat() {
      throw new UnsupportedOperationException(NOT_IMPL_ERROR);
   }

   public boolean isDouble() {
      throw new UnsupportedOperationException(NOT_IMPL_ERROR);
   }

   public boolean isAFloat() {
      throw new UnsupportedOperationException(NOT_IMPL_ERROR);
   }

   public boolean isAssignableFromAssignment(Type other, Class from, Class to) {
      return isAssignableFromParameter(other, from, to);
   }

   public boolean isAssignableFromParameter(Type other, Class from, Class to) {
      throw new UnsupportedOperationException();
      //return this == other && from.isAssignableFrom(to);
   }

   public boolean isAssignableFromOverride(Type other, Class from, Class to) {
      return this == other && from == to;
   }

   public boolean isVoid() {
      return false;
   }

   public boolean isFloatingType() {
      return this == Float || this == Double;
   }

   public static final Integer IntegerZero = new Integer(0);
   public static final Float FloatZero = new Float(0.0);
   public static final Double DoubleZero = new Double(0.0);
}
