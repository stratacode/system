/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.SemanticNodeList;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class VariableStatement extends TypedDefinition {
   public SemanticNodeList<Object> variableModifiers;
   public List<VariableDefinition> definitions;

   public static VariableStatement create(JavaType type, String variableName) {
      VariableStatement stmt = new VariableStatement();
      stmt.setProperty("type", type);
      List l = new SemanticNodeList(1);
      VariableDefinition def = new VariableDefinition();
      def.variableName = variableName;
      l.add(def);
      stmt.setProperty("definitions", l);
      return stmt;
   }

   public static VariableStatement create(JavaType type, String variableName, String op, Expression initializer) {
      VariableStatement vs = create(type, variableName);
      VariableDefinition def = vs.definitions.get(0);
      def.operator = op;
      def.setProperty("initializer", initializer);
      return vs;
   }

   public Object definesMember(String name, EnumSet<MemberType> mtype, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed) {
      if (mtype.contains(MemberType.Variable))
         for (VariableDefinition v:definitions)
            if (v.variableName.equals(name))
               return v;
      return super.definesMember(name, mtype, refType, ctx, skipIfaces, isTransformed);
   }

   public ExecResult exec(ExecutionContext ctx) {
      for (VariableDefinition v:definitions) {
         Expression init = v.initializer;
         ctx.defineVariable(v.variableName, init != null ? init.eval(v.getRuntimeClass(), ctx) : null);
      }

      return ExecResult.Next;
   }

   public boolean isProperty() {
      return false;
   }

   public void refreshBoundTypes() {
      super.refreshBoundTypes();
      if (definitions != null)
         for (VariableDefinition v:definitions)
            v.refreshBoundType();
   }

   public int transformTemplate(int ix, boolean statefulContext) {
      ix = super.transformTemplate(ix, statefulContext);
      if (definitions != null)
         for (VariableDefinition v:definitions)
            ix = v.transformTemplate(ix, statefulContext);
      return ix;
   }

   public void addDependentTypes(Set<Object> types) {
      super.addDependentTypes(types);
      if (definitions != null)
         for (VariableDefinition v:definitions)
            v.addDependentTypes(types);
   }

   public Statement transformToJS() {
      // Before we erase the type, tell the variable def to freeze it
      if (definitions != null)
         for (VariableDefinition v:definitions)
            v.freezeType();

      setProperty("type", ClassType.createStarted(Object.class, "var"));
      if (definitions != null)
         for (VariableDefinition v:definitions)
            v.transformToJS();

      if (variableModifiers != null) {
         setProperty("variableModifiers", null);
      }

      return this;
   }

   public boolean canInsertStatementBefore(Expression from) {
      return false;
   }

   public String getUserVisibleName() {
      return "VariableStatement";
   }

   public String toSafeLanguageString() {
      if (parseNode == null || parseNodeInvalid) {
         StringBuilder sb = new StringBuilder();
         if (type != null)
            sb.append(type.toSafeLanguageString());
         if (definitions != null) {
            int ix = 0;
            for (VariableDefinition def:definitions) {
               if (ix != 0)
                  sb.append(", ");
               else
                  sb.append(" ");
               if (def != null)
                  sb.append(def.toSafeLanguageString());
               ix++;
            }
         }
         return sb.toString();
      }
      return super.toSafeLanguageString();
   }
}
