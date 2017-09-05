/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.type;

import sc.dyn.DynUtil;
import sc.js.JSSettings;

import java.lang.reflect.Array;
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

         char c = operator.charAt(0);

         if (c == 'i')
             return DynUtil.instanceOf(lhsObj, rhsObj);

         boolean lhsBool = ((Boolean) lhsObj);
         boolean rhsBool = ((Boolean) rhsObj);

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

      public boolean isAssignableFromParameter(Type other, Class from, Class to) {
         return other == Boolean;
      }
      public boolean isAssignableFromAssignment(Type other, Class from, Class to) {
         return other == Boolean;
      }

      public Object stringToValue(String val) {
         if (val.equalsIgnoreCase("true"))
            return java.lang.Boolean.TRUE;
         else if (val.equalsIgnoreCase("false"))
            return java.lang.Boolean.FALSE;
         else
            throw new IllegalArgumentException("Invalid boolean: " + val);
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

      public Object stringToValue(String val) {
         return java.lang.Integer.valueOf(val);
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

      public Object stringToValue(String val) {
         return java.lang.Integer.valueOf(val);
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

      public Object stringToValue(String val) {
         return val.length() == 0 ? '\0' : val.charAt(0);
      }
   },
   Integer {
      public Object evalArithmetic(String operator, Object lhsObj, Object rhsObj) {
         char c = operator.charAt(0);
         int result;
         if (lhsObj == null || rhsObj == null)
            throw new NullPointerException("Null encountered in dynamic arithemtic exprs");
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
      public Object stringToValue(String val) {
         return java.lang.Integer.valueOf(val);
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

      public Object stringToValue(String val) {
         return java.lang.Float.valueOf(val);
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

      public Object stringToValue(String val) {
         return java.lang.Long.valueOf(val);
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

      public Object stringToValue(String val) {
         return java.lang.Double.valueOf(val);
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

      public Object stringToValue(String val) {
         return val;
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

      public Object stringToValue(String val) {
         return java.lang.Double.valueOf(val);
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
      for (Type t:Type.values()) {
         Class objClass = RTypeUtil.loadClass("java.lang." + t.toString());
         classToTypeIndex.put(objClass, t);
         t.objectClass = objClass;
      }
      // Just for primitive types, register the no-wrapped types too
      classToTypeIndex.put(java.lang.Boolean.TYPE, Boolean);
      classToTypeIndex.put(java.lang.Integer.TYPE, Integer);
      classToTypeIndex.put(java.lang.Long.TYPE, Long);
      classToTypeIndex.put(java.lang.Short.TYPE, Short);
      classToTypeIndex.put(java.lang.Character.TYPE, Character);
      classToTypeIndex.put(java.lang.Byte.TYPE, Byte);
      classToTypeIndex.put(java.lang.Float.TYPE, Float);
      classToTypeIndex.put(java.lang.Double.TYPE, Double);
      classToTypeIndex.put(java.lang.Void.TYPE, Void);

      Boolean.primitiveClass = java.lang.Boolean.TYPE;
      primitiveIndex.put("boolean", Boolean);
      arrayTypeIndex.put("Z", Boolean);
      Boolean.arrayTypeCode = 'Z';
      Integer.primitiveClass = java.lang.Integer.TYPE;
      primitiveIndex.put("int", Integer);
      arrayTypeIndex.put("I", Integer);
      Integer.arrayTypeCode = 'I';
      Long.primitiveClass = java.lang.Long.TYPE;
      primitiveIndex.put("long", Long);
      arrayTypeIndex.put("J", Long);
      Long.arrayTypeCode = 'J';
      Short.primitiveClass = java.lang.Short.TYPE;
      primitiveIndex.put("short", Short);
      arrayTypeIndex.put("S", Short);
      Short.arrayTypeCode = 'S';
      Character.primitiveClass = java.lang.Character.TYPE;
      primitiveIndex.put("char", Character);
      arrayTypeIndex.put("C", Character);
      Character.arrayTypeCode = 'C';
      Byte.primitiveClass = java.lang.Byte.TYPE;
      primitiveIndex.put("byte", Byte);
      arrayTypeIndex.put("B", Byte);
      Byte.arrayTypeCode = 'B';
      Float.primitiveClass = java.lang.Float.TYPE;
      primitiveIndex.put("float", Float);
      arrayTypeIndex.put("F", Float);
      Float.arrayTypeCode = 'F';
      Double.primitiveClass = java.lang.Double.TYPE;
      primitiveIndex.put("double", Double);
      arrayTypeIndex.put("D", Double);
      Double.arrayTypeCode = 'D';
      primitiveIndex.put("void", Void);
      arrayTypeIndex.put("V", Void);
      Void.arrayTypeCode = 'V';
      Void.primitiveClass = java.lang.Void.TYPE;

      arrayTypeIndex.put("L", Object);
      Object.arrayTypeCode = 'L';
   }

   private HashMap<Integer,Class> arrayClassCache;
   private HashMap<Integer,Class> primArrayClassCache;

   private Class objectClass;

   public Class primitiveClass;

   public char arrayTypeCode;

   public Class getObjectClass() {
      return objectClass;
   }

   public Class getArrayClass(Class componentType, int ndims) {
      Class res;
      if (componentType == null) {
         componentType = objectClass;
      }
      if (componentType == objectClass) {
         if (arrayClassCache == null)
            arrayClassCache = new HashMap<Integer,Class>();
         if ((res = arrayClassCache.get(ndims)) != null)
            return res;
      }
      res = Array.newInstance(componentType, new int[ndims]).getClass();

      if (componentType == objectClass)
         arrayClassCache.put(ndims, res);
      return res;
   }

   public Class getPrimitiveArrayClass(int ndims) {
      Class res;
      if (primArrayClassCache == null)
         primArrayClassCache = new HashMap<Integer,Class>();
      if ((res = primArrayClassCache.get(ndims)) != null)
         return res;
      if (primitiveClass == null)
         throw new IllegalArgumentException("No primitive type for: " + this);
      res = Array.newInstance(primitiveClass, new int[ndims]).getClass();
      primArrayClassCache.put(ndims, res);
      return res;
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
      throw new UnsupportedOperationException();
   }

   public abstract Object evalUnary(String operator, Object arg);

   /** Use this to avoid evaluating the second arg to an && or || statement if the first one evals to false or true */
   public Boolean evalPreConditional(String operator, Object lhsObj) {
      return null;
   }

   /** Takes the class used to get this type as the first argument and implements the cast operator on the value */
   public Object evalCast(Class theClass, Object val) {
      if (theClass.isInstance(val) || theClass == primitiveClass) {
         return val;
      }
      throw new ClassCastException("Value " + val + " of type: " + (val == null ? "null" : val.getClass()) + " cannot be cast to: " + theClass);
   }

   public boolean isANumber() {
      return primitiveClass != null && (isAnInteger() || isAFloat());
   }

   public boolean isAnInteger() {
      return isInteger() || isShort() || isByte() || isLong();
   }

   public boolean isInteger() {
      return primitiveClass == java.lang.Integer.TYPE;
   }

   public boolean isShort() {
      return primitiveClass == java.lang.Short.TYPE;
   }

   public boolean isByte() {
      return primitiveClass == java.lang.Byte.TYPE;
   }

   public boolean isLong() {
      return primitiveClass == java.lang.Long.TYPE;
   }

   public boolean isFloat() {
      return primitiveClass == java.lang.Float.TYPE;
   }

   public boolean isDouble() {
      return primitiveClass == java.lang.Double.TYPE;
   }

   public boolean isAFloat() {
      return isFloat() || isDouble();
   }

   public boolean isAssignableFromAssignment(Type other, Class from, Class to) {
      return isAssignableFromParameter(other, from, to);
   }

   public boolean isAssignableFromParameter(Type other, Class from, Class to) {
      return this == other && from.isAssignableFrom(to);
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

   public Object stringToValue(String val) {
      throw new UnsupportedOperationException("Unable to convert string to type");
   }

   public static final Integer IntegerZero = new Integer(0);
   public static final Float FloatZero = new Float(0.0);
   public static final Double DoubleZero = new Double(0.0);

   public static Object propertyStringToValue(Object propType, String strVal) {
      if (propType instanceof Class) {
         sc.type.Type t = sc.type.Type.get((Class) propType);
         return t.stringToValue(strVal);
      }
      else {
         throw new IllegalArgumentException("Unable to convert to: " + propType + " from string");
      }
   }

}
