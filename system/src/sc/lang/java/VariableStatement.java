/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ISrcStatement;
import sc.lang.SemanticNodeList;
import sc.util.StringUtil;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class VariableStatement extends TypedDefinition implements IClassBodyStatement {
   public SemanticNodeList<Object> variableModifiers;
   public List<VariableDefinition> definitions;

   // Used to disable so we do not resolve to this Statement
   public transient boolean inactive = false;

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
      if (definitions != null && mtype.contains(MemberType.Variable) && !inactive) {
         for (VariableDefinition v : definitions)
            if (StringUtil.equalStrings(v.variableName, name))
               return v;
      }
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

   public boolean refreshBoundTypes(int flags) {
      boolean res = super.refreshBoundTypes(flags);
      if (definitions != null) {
         for (VariableDefinition v:definitions) {
            if (v.refreshBoundType(flags))
               res = true;
         }
      }
      return res;
   }

   public int transformTemplate(int ix, boolean statefulContext) {
      ix = super.transformTemplate(ix, statefulContext);
      if (definitions != null)
         for (VariableDefinition v:definitions)
            ix = v.transformTemplate(ix, statefulContext);
      return ix;
   }

   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {
      super.addDependentTypes(types, mode);
      if (definitions != null)
         for (VariableDefinition v:definitions)
            v.addDependentTypes(types, mode);
   }

   public void setAccessTimeForRefs(long time) {
      super.setAccessTimeForRefs(time);
      if (definitions != null)
         for (VariableDefinition v:definitions)
            v.setAccessTimeForRefs(time);
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

   public String toString() {
      return toSafeLanguageString();
   }

   public ISrcStatement findFromStatement(ISrcStatement st) {
      ISrcStatement fromSt = super.findFromStatement(st);
      if (fromSt != null)
         return fromSt;
      return null;
   }

   public void addBreakpointNodes(List<ISrcStatement> res, ISrcStatement st) {
      super.addBreakpointNodes(res, st);
      if (fromStatement instanceof VariableStatement) {
         VariableStatement fromVarSt = (VariableStatement) fromStatement;
         if (fromVarSt.definitions != null) {
            int ix = 0;
            for (VariableDefinition varDef : fromVarSt.definitions) {
               if (st.getNodeContainsPart(varDef)) {
                  // Return the corresponding definition in the transformed model
                  if (definitions != null && definitions.size() > ix)
                     res.add(definitions.get(ix));
               }
               ix++;
            }
         }
      }
   }

   public boolean getNodeContainsPart(ISrcStatement fromSt) {
      if (super.getNodeContainsPart(fromSt))
         return true;
      if (definitions != null) {
         for (VariableDefinition varDef:definitions)
            if (varDef == fromSt || varDef.getNodeContainsPart(fromSt))
               return true;
      }
      return false;
   }

   // TODO: code cleanup: this method (and probably some other methods) could be put into a new base class shared with FieldDefinition if we renamed variableDefinitions and definitions
   public List<Statement> getBodyStatements() {
      List<Statement> res = null;
      if (definitions != null) {
         for (VariableDefinition varDef:definitions) {
            Expression initExpr = varDef.getInitializerExpr();
            if (initExpr != null && !initExpr.isLeafStatement()) {
               if (res == null)
                  res = new ArrayList<Statement>();
               res.add(initExpr);
            }
         }
      }
      return res;
   }

   public boolean isLeafStatement() {
      return getBodyStatements() == null;
   }
}
