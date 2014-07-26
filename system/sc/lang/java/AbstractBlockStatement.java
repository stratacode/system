/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ISemanticNode;
import sc.lang.ISrcStatement;
import sc.lang.SemanticNodeList;

import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public abstract class AbstractBlockStatement extends Statement implements IBlockStatement {
   public SemanticNodeList<Statement> statements;
   public boolean staticEnabled;
   public boolean visible;

   public transient int frameSize;

   public void initialize() {
      if (initialized) return;
      super.initialize();

      frameSize = ModelUtil.computeFrameSize(statements);

      if (parentNode instanceof SemanticNodeList) {
         ISemanticNode parentParent = parentNode.getParentNode();
         if (parentParent != null &&
              (parentParent instanceof BlockStatement || parentParent instanceof SwitchStatement ||
               parentParent instanceof ClassDeclaration))
            visible = true;
      }
      else if (parentNode instanceof IfStatement)
         visible = true;
   }

   /** Block statement itself does not increase the indent */
   public int getChildNestingDepth() {
      if (parentNode != null) {
         if (visible)
            return parentNode.getChildNestingDepth() + 1;
         return parentNode.getChildNestingDepth();
      }
      return 0;
   }

   public Object definesMember(String name, EnumSet<MemberType> mtype, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed) {
      return definesMember(name, mtype, null, refType, ctx, skipIfaces, isTransformed);
   }

   public Object definesMember(String name, EnumSet<MemberType> mtype, Object fromChild, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed) {
      Object v = null;
      // Propagate only variable searches
      if (statements != null) {
         if (mtype.contains(MemberType.Variable)) {
            EnumSet<MemberType> subMtype;
            if (mtype.size() != 1)
               subMtype = MemberType.VariableSet;
            else
               subMtype = mtype;

            int startIx = statements.size()-1;
            if (fromChild != null) {
               int childIx = statements.indexOf(fromChild);
               if (childIx != -1)
                  startIx = childIx;
            }

            for (int i = 0; i <= startIx; i++) {
               Statement st = statements.get(i);
               if ((v = st.definesMember(name, subMtype, refType, ctx, skipIfaces, isTransformed)) != null)
                  return v;
            }
         }

         if (mtype.contains(MemberType.Assignment) && parentNode.getParentNode() instanceof TypeDeclaration) {
            for (int i = statements.size()-1; i >= 0; i--) {
               Statement s = statements.get(i);
               v = s.definesMember(name, MemberType.AssignmentSet, refType, ctx, skipIfaces, isTransformed);
               if (v != null)
                  return v;
            }
         }
      }
      return v;
   }

   /*
   public void addToMemberCache(MemberCache cache, EnumSet<MemberType> filter) {
      // Propagate only variable searches
      if (statements != null) {
         for (Statement s:statements)
            s.addToMemberCache(cache, MemberType.VariableSet);

         // If this is a property assignment defined at the type level also need to add that to the member cache
         // as an assignment.  Here if there are multiple assignments for the same property, they go in last to first order since that's
         // order of precedence.
         if (parentNode.getParentNode() instanceof TypeDeclaration) {
            for (int i = statements.size() - 1; i >= 0; i--) {
               Statement s = statements.get(i);
               s.addToMemberCache(cache, MemberType.AssignmentSet);
            }
         }
      }
   }
   */

   public Object findMember(String name, EnumSet<MemberType> mtype, Object fromChild, Object refType, TypeContext ctx, boolean skipIfaces) {
      Object v = definesMember(name, mtype, fromChild, refType, ctx, skipIfaces, false);
      if (v != null)
         return v;

      return super.findMember(name, mtype, this, refType, ctx, skipIfaces);
   }

   /** Just append this definition to the base type */
   public Definition modifyDefinition(BodyTypeDeclaration base, boolean doMerge, boolean inTransformed) {
      base.body.add(this);
      return this;
   }

   public void collectReferenceInitializers(List<Statement> refInits) {
      if (!staticEnabled) {
         BlockStatement st = new BlockStatement();
         st.setProperty("statements", statements);
         setProperty("statements", null);
         refInits.add(st);
      }
   }

   public ExecResult execForObj(Object inst, ExecutionContext ctx) {
      ExecResult res = null;
      try {
         ctx.pushCurrentObject(inst);

         res = exec(ctx);
      }
      finally {
         ctx.popCurrentObject();
      }
      return res;
   }

   public ExecResult exec(ExecutionContext ctx) {
      if (!isStarted()) {
         ensureValidated();
         if (getJavaModel().hasErrors()) {
            ctx.currentReturnValue = null;
            System.err.println("*** Executing model: " + getJavaModel() + " which has errors.");
            return ExecResult.Return; // ??? throw here
         }
      }
      ctx.pushFrame(false, frameSize);
      try {
         return ModelUtil.execStatements(ctx, statements);
      }
      finally {
         ctx.popFrame();
      }
   }

   /** Returns true if this block statement is directly under the type definition */
   private boolean isTopLevelStatement() {
      return parentNode != null && parentNode.getParentNode() instanceof TypeDeclaration;
   }

   /** If we are a top-level block statement, we define whether constructs below us are static or not */
   public boolean isStatic() {
      if (isTopLevelStatement())
         return staticEnabled;
      return super.isStatic();
   }

   public void initDynStatement(Object inst, ExecutionContext ctx, TypeDeclaration.InitStatementMode mode, boolean inherit) {
      if (!isStatic()) {
         if (mode.evalStatements()) {
            ExecResult execResult = exec(ctx);
            if (execResult != execResult.Next)
               System.err.println("Illegal " + execResult + " out of a break statement");
         }
      }
   }

   public void addStatementAt(int i, Statement st) {
      initStatements();
      statements.add(i, st);
   }

   public void addAllStatementsAt(int i, SemanticNodeList<Statement> toAdd) {
      initStatements();
      statements.addAll(i, toAdd);
   }

   public void initStatements() {
      if (statements == null)
         setProperty("statements", new SemanticNodeList<Statement>());
   }

   public void insertStatementBefore(Statement current, Statement newStatement) {
      initStatements();
      int ix = statements.indexOf(current);
      if (ix == -1)
         System.err.println("*** Can't find statement for insertSTatementBefore operation");
      else
         statements.add(ix, newStatement);
   }

   public boolean canInsertStatementBefore(Expression fromExpr) {
      return true;
   }

   public int getNumStatements() {
      return statements == null ? 0 : statements.size();
   }

   public boolean callsSuper() {
      if (statements == null)
         return false;

      for (Statement st:statements)
         if (st.callsSuper())
            return true;

      return false;
   }

   public boolean callsThis() {
      if (statements == null)
         return false;

      for (Statement st:statements)
         if (st.callsThis())
            return true;

      return false;
   }

   public void refreshBoundTypes() {
      if (statements != null) {
         for (Statement st:statements)
            st.refreshBoundTypes();
      }
   }


   public void addDependentTypes(Set<Object> types) {
      if (statements != null) {
         for (Statement st:statements)
            st.addDependentTypes(types);
      }
   }

   public void addChildBodyStatements(List<Object> types) {
      if (statements != null) {
         for (Statement st:statements)
            st.addChildBodyStatements(types);
      }
   }

   public Statement transformToJS() {
      if (statements != null) {
         for (Statement st:statements)
            st.transformToJS();
      }
      return this;
   }

   public void addInitStatements(List<Statement> res, InitStatementsMode mode) {
      if (staticEnabled != mode.doStatic() || mode == InitStatementsMode.PreInit)
         return;

      if (statements != null) {
         for (Statement st:statements) {
            res.add(st);
         }
      }
   }

   public boolean needsDataBinding() {
      if (statements != null) {
         for (Statement st:statements) {
            if (st.needsDataBinding())
               return true;
         }
      }
      return false;
   }

   public int transformTemplate(int ix, boolean statefulContext) {
      if (statements != null) {
         for (Statement st:statements)
            ix = st.transformTemplate(ix, statefulContext);
      }
      return ix;
   }

   public AbstractBlockStatement deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap) {
      AbstractBlockStatement res = (AbstractBlockStatement) super.deepCopy(options, oldNewMap);
      if ((options & CopyInitLevels) != 0) {
         res.frameSize = frameSize;
      }
      return res;
   }

   public List<Statement> getBlockStatements() {
      return statements;
   }

   public ISrcStatement findFromStatement(ISrcStatement st) {
      return findFromBlockStatement(this, st);
   }

   public static ISrcStatement findFromBlockStatement(IBlockStatement bst, ISrcStatement toFind) {
      if (toFind == bst)
         return (ISrcStatement) bst;
      List<Statement> sts = bst.getBlockStatements();
      if (sts != null) {
         for (Statement st:sts) {
            ISrcStatement res = st.findFromStatement(toFind);
            if (res != null)
               return res;
         }
      }
      return null;
   }

   public boolean updateFromStatementRef(Statement fromSt) {
      return blockUpdateFromStatementRef(this, fromSt);
   }

   public static boolean blockUpdateFromStatementRef(IBlockStatement bst, Statement fromSt) {
      List<Statement> sts = bst.getBlockStatements();
      if (sts != null) {
         for (Statement st:sts) {
            if (st.updateFromStatementRef(fromSt))
               return true;
         }
      }
      return false;
   }
}
