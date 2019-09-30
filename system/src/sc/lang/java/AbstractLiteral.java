/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.bind.BindingDirection;
import sc.bind.ConstantBinding;
import sc.lang.ILanguageModel;
import sc.lang.SemanticNodeList;
import sc.type.*;
import sc.util.StringUtil;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Set;

public abstract class AbstractLiteral extends Expression implements IValueConverter {
   // Value as it was parsed
   public String value;

   // The typed value - int would be returned as Integer
   public abstract Object getLiteralValue();

   // Lets these instances be used as values from the TypeUtil system, such as when setting semantic properties as part of the pattern evaluation.
   public Object getConvertedValue() {
      return getLiteralValue();
   }

   public Object eval(Class expectedType, ExecutionContext ctx) {
      if (bindingDirection != null) {
         if (nestedBinding)
            return new ConstantBinding(getLiteralValue());
         else {
            return super.evalBinding(expectedType, ctx);
         }
      }

      return getLiteralValue();
   }
   
   public long evalLong(Class expectedType, ExecutionContext ctx) {
      Object valObj = eval(expectedType, ctx);
      return ((Number) valObj).longValue();
   }

   public double evalDouble(Class expectedType, ExecutionContext ctx) {
      Object valObj = eval(expectedType, ctx);
      return ((Number) valObj).doubleValue();
   }

   // new ConstantBinding(this)
   public void transformBinding(ILanguageModel.RuntimeType runtime) {
      if (nestedBinding) {
         NewExpression boundExpr = new NewExpression();
         boundExpr.typeIdentifier = "sc.bind.ConstantBinding";
         SemanticNodeList<Expression> args = new SemanticNodeList<Expression>(1);
         boundExpr.setProperty("arguments", args);
         parentNode.replaceChild(this, boundExpr);
         args.add(this);
      }
      else {
         // Need to turn this into Bind.constant(...)
         // just so that we can clear any old binding that might be set.
         super.transformBinding(runtime);
      }
   }

   public void transformBindingArgs(SemanticNodeList<Expression> bindArgs, BindDescriptor bd) {
      bindArgs.add(this);

      // Need to clear this so we don't try to transform it again the second time.
      bindingStatement = null;
   }

   public void evalBindingArgs(List<Object> bindArgs, boolean isStatic, Class expectedType, ExecutionContext ctx) {
      bindArgs.add(getLiteralValue());
   }

   public void setBindingInfo(BindingDirection dir, Statement dest, boolean nested) {
      super.setBindingInfo(dir, dest, nested);
      if (!nested && dir.doReverse())
         displayError("Constant expressions do not support reverse binding (the '=:' operator) ");
   }

   public boolean isConstant() {
      return true;
   }

   public boolean isReferenceInitializer() {
      // If we are bound, we need to be a reference initializer because of the side-effect of clearing the binding
      return bindingDirection != null;
   }

   public String getBindingTypeName() {
      if (nestedBinding)
         return "constantP";
      else
         return "constant";
   }

   public static Expression createFromValue(Object literalValue, boolean isInitializer) {
      Class literalClass = literalValue.getClass();
      switch (Type.get(literalClass)) {
         case Boolean:
            return BooleanLiteral.create((Boolean) literalValue);
         case Byte:
         case Short:
         case Integer:
            return IntegerLiteral.create((Integer) literalValue);
         case Long:
            Expression il = IntegerLiteral.create((Integer) literalValue, "l");
            return il;
         case Float:
         case Double:
            return FloatLiteral.create(literalValue);
         case Character:
            return CharacterLiteral.create((Character) literalValue);
         case String:
            return StringLiteral.create((String) literalValue);
         case Object:
            if (literalValue instanceof List) {
               return Expression.createFromValue(((List) literalValue).toArray(), isInitializer);
            }
            else if (literalValue instanceof Set) {
               return Expression.createFromValue(((Set) literalValue).toArray(), isInitializer);
            }
            else if (literalValue instanceof Object[]) {
               return Expression.createFromValue(literalValue, isInitializer);
            }
            /* TODO: create a 'new Date' or SimpleDateFormat.parse("xxx")
            else if (literalValue instanceof Date) {
            }
            */
            else {
               // TODO: need a way a class can override this using annotations.  Right now we support only 'transient' to remove it from the list
               IBeanMapper[] props = RTypeUtil.getPersistProperties(literalClass);
               Class[] propTypes = new Class[props.length];
               for (int i = 0; i < props.length; i++) {
                  propTypes[i] = (Class) props[i].getPropertyType();
               }
               Constructor cs = RTypeUtil.getConstructor(literalClass, propTypes);
               if (cs != null) {
                  SemanticNodeList<Expression> args = new SemanticNodeList<Expression>(props.length);
                  for (int i = 0; i < props.length; i++) {
                     args.add(Expression.createFromValue(props[i].getPropertyValue(literalValue, false, false), false));
                  }
                  return NewExpression.create(TypeUtil.getTypeName(literalClass, false), args);
               }
               else {
                  throw new IllegalArgumentException("Type: " + literalClass + " is not a known expression type does not have a constructor which matches its public properties: " + StringUtil.arrayToString(props));
               }
            }
         default:
            throw new UnsupportedOperationException();
      }
   }

   public boolean isStaticTarget() {
      return true;
   }

   public boolean refreshBoundTypes(int flags) {
      return false;
   }

   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {}

   public void setAccessTimeForRefs(long time) {}

   public String toString() {
      if (value != null)
         return value;
      Object val = getLiteralValue();
      if (val == null)
         return "null";
      return val.toString();
   }

   public Statement transformToJS() { return this; }

   public Object getExprValue() {
      return getLiteralValue();
   }

   public String getUserVisibleName() {
      return value;
   }

   public String toGenerateString() {
      return value;
   }

   public boolean isIncompleteStatement() {
      return false;
   }
}
