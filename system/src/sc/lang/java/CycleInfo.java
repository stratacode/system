/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.SemanticNodeList;
import sc.lang.sc.PropertyAssignment;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

public class CycleInfo extends IdentityHashMap<JavaSemanticNode, List<CycleInfo.CycleEntry>> {
   public JavaSemanticNode start;
   /** When we traverse an "a.b" reference, we set the qualifier to "a".  If we detect a cycle, but the qualifiers do not match, it is only a warning */
   public ThisContext context;

   /** Lists possible matches - i.e. same field of same type used in a recursive binding */
   public static boolean debugMode = false;

   public CycleInfo(JavaSemanticNode st) {
      start = st;
   }

   ArrayList<JavaSemanticNode> cycle;

   public void visit(ThisContext thisCtx, Object obj, TypeContext ctx, boolean remove) {
      ThisContext old = context;

      context = thisCtx;
      try {
         visit(obj, ctx, remove);
      }
      finally {
         context = old;
      }
   }

   public void visit(Object obj, TypeContext ctx, boolean remove) {
      if (obj instanceof JavaSemanticNode) {
         JavaSemanticNode node = (JavaSemanticNode) obj;
         // ??/ Performance: this may drag in a lot more types, but we need references resolved to do this analysis
         if (!node.isStarted())
            ModelUtil.ensureStarted(node, true);
         visit(node, ctx, remove);
      }
   }

   public void visit(JavaSemanticNode obj, TypeContext ctx) {
      visit(obj, ctx, true);
   }

   private static boolean nullEquals(Object o1, Object o2) {
      return o1 == o2 || (o1 != null && o2 != null && o1.equals(o2));
   }

   private static boolean contextInList(ThisContext ctx, List<CycleEntry> list) {
      for (CycleEntry e:list)
         if (nullEquals(e.context, ctx))
            return true;
      return false;
   }

   public boolean addToVisitedList(JavaSemanticNode obj) {
      boolean didPut = false;
      if (obj.isReferenceValueObject()) {
         List<CycleEntry> entList;
         if ((entList = get(obj)) != null) {

            if (debugMode || contextInList(context, entList)) {
               cycle = new ArrayList<JavaSemanticNode>();
               addObjectToCycle(obj);
            }

            // If we've visited the node, we always return false so we don't keep searching.
            return false;
         }
         didPut = true;
         add(obj);
      }
      return didPut;
   }

   public void visit(JavaSemanticNode obj, TypeContext ctx, boolean remove) {
      if (obj == null || cycle != null) // Already detected a cycle - keep things simple and only look for one at a time
         return;

      boolean doVisit = addToVisitedList(obj);
      if (cycle != null)
         return;

      if (doVisit) {
         obj.visitTypeReferences(this, ctx);

         if (remove)
            remove(obj);
      }

      // No cycle before, was after - we must be part of the problem!
      if (cycle != null )
         addObjectToCycle(obj);
   }

   public void add(Object obj) {
      if (obj instanceof JavaSemanticNode) {
         JavaSemanticNode node = (JavaSemanticNode) obj;
         List<CycleEntry> l = get(node);
         if (l == null)
            l = new ArrayList<CycleEntry>();
         l.add(new CycleEntry(node, context));
         put(node, l);
      }
   }

   private void addObjectToCycle(JavaSemanticNode obj) {
      if (!cycle.contains(obj)) {
         if (obj instanceof FieldDefinition || obj instanceof PropertyAssignment || obj instanceof MethodDefinition)
            cycle.add(obj);
         if (obj instanceof VariableDefinition)
            addObjectToCycle(((VariableDefinition) obj).getDefinition());
      }
   }

   public void visitList(SemanticNodeList<? extends JavaSemanticNode> args, TypeContext ctx) {
      visitList(args, ctx, true);
   }

   public void visitList(SemanticNodeList<? extends JavaSemanticNode> args, TypeContext ctx, boolean remove) {
      if (args == null)
         return;

      int sz = args.size();
      for (int i = 0; i < sz; i++) {
         visit(args.get(i), ctx, remove);
      }
   }

   public void removeList(SemanticNodeList<? extends JavaSemanticNode> args) {
      if (args == null)
         return;
      
      int sz = args.size();
      for (int i = 0; i < sz; i++) {
         remove(args.get(i));
      }
   }

   public String toString() {
      if (cycle == null)
         return null;
      StringBuilder sb = new StringBuilder();
      sb.append("Recursive loop in bindings:\n");
      for (int i = cycle.size()-1; i >= 0; i--) {
         JavaSemanticNode node = cycle.get(i);
         sb.append("   ");
         sb.append(node.toDefinitionString(1, false, false));
         sb.append(" at ");
         sb.append(node.toFileString());
         sb.append("\n");
      }
      return sb.toString();
   }

   public static class CycleEntry {

      public CycleEntry(JavaSemanticNode node, Object ctx) {
         object = node;
         context = ctx;
      }

      /** The visited node, the field, assignment,  */
      JavaSemanticNode object;

      /** When there is a modifier such as an "a." prefix, this stores that context.
       * If the contexts do not match, it is a debug message, not an error. */
      Object context;
   }

   public static class ThisContext {
      /** The current type which contains this expression */
      Object thisType;
      /** The parent variable used to evaluate the "this" variable, i.e. the "a.b" value.  Null if not part of an identifier or selector expression */
      public Object parentVar;

      public ThisContext(Object tt, Object pv) {
         thisType = tt;
         parentVar = pv;
      }

      public boolean equals(Object otherThisCtx) {
         if (!(otherThisCtx instanceof ThisContext))
            return false;

         ThisContext otherCtx = (ThisContext) otherThisCtx;
         if (!ModelUtil.isAssignableFrom(thisType, otherCtx.thisType) && !ModelUtil.isAssignableFrom(otherCtx.thisType, thisType))
            return false;
         return nullEquals(parentVar, otherCtx.parentVar);
      }

      public int hashCode() {
         return parentVar == null ? 0 : parentVar.hashCode();
      }
   }
}
