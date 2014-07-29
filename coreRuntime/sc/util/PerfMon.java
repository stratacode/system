/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.util;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class PerfMon {
   static ThreadLocal<ArrayList<PerfOp>> currentOp = new ThreadLocal<ArrayList<PerfOp>>();

   static ArrayList<String> statOrder = new ArrayList<String>();
   final static ConcurrentHashMap<String,PerfStat> statTable = new ConcurrentHashMap<String,PerfStat>();

   static ConcurrentHashMap<NestedPair,Boolean> nestedPairs = new ConcurrentHashMap<NestedPair,Boolean>();

   static ConcurrentHashMap<String,ArrayList<String>> statChildren = new ConcurrentHashMap<String,ArrayList<String>>();

   public static int maxSamples = 1000;

   public static boolean enabled = false;

   // This involves going up and down the stack.  If an op is highly recursive, it can start impacting results so periodically need to turn this off and check overall performance.
   // If it changes a lot, this stat is skewing the results.
   private static boolean inProgressEnabled = true;

   static class PerfStat {
      String name;
      int count;
      boolean includeAll, includeInParent;
      long minTime;
      long maxTime;
      long totalTime;
      boolean isRoot;
      ArrayList<PerfOp> samples = new ArrayList<PerfOp>(maxSamples);

      public String toString() {
         return "  " +  name + ", " + (includeAll ? "(all)" : (includeInParent ? "" : "(not in parent)")) + ", " + formatNanoTime(totalTime) + ", (" + count + "x), " + formatNanoTime(minTime) + ", " + formatNanoTime(maxTime);
      }

      public void printAll(HashSet<String> visited, int indent) {
         if (visited == null)
            visited = new HashSet<String>();
         if (visited.contains(name))
            return;
         visited.add(name);
         for (int i = 0; i < indent; i++)
            System.out.print("    ");
         System.out.println(toString());
         List<PerfStat> children = getChildren(name);
         if (children != null)
            for (PerfStat st:children)
               st.printAll(visited, indent+1);
      }

   }

   static class PerfOp {
      String name;
      long startTime; // When did this op start
      boolean recording; // Is this op paused or recording
      long runTime;  // How long as this op been running
      boolean includeInParent;
      boolean includeAll;
      int nestedCount = 0;
      boolean isRoot;
      boolean inProgress = false; // Set to true if this is a recursive op - i.e. the same thread calls itself.  NestedCount is an optimization for when it's a direct call.  To compute this we walk up the stack and check all current operations.  This is to avoid double counting.

      void yield(long now) {
         // Main and other global ops should not be disabled ever
         if (!includeAll && recording) {
            runTime += now - startTime;
            recording = false;
         }
      }

      boolean resume(ArrayList<PerfOp> currentList, int op, long now) {
         if (op >= 0) {
            PerfOp prevOp = currentList.get(op);
            if (!prevOp.includeAll && !prevOp.recording) {
               prevOp.startTime = now;
               prevOp.recording = true;
            }
            return prevOp.includeInParent;
         }
         else
            return false;
      }

      public String toString() {
         return name + (includeAll ? "(all)" : (includeInParent ? "(in parent)" : "(not in parent)")) + (recording ? "" : "(disabled)");
      }
   }

   public static boolean isRootStat(String name) {
      return statTable.get(name).isRoot;
   }

   public static List<PerfStat> getChildren(String parentName) {
      ArrayList<String> childNames = statChildren.get(parentName);
      if (childNames == null)
         return null;
      ArrayList<PerfStat> res = new ArrayList<PerfStat>(childNames.size());
      for (int i = 0; i < childNames.size(); i++)
         res.add(statTable.get(childNames.get(i)));
      return res;
   }

   static class NestedPair {
      String parent, child;

      public boolean equals(Object other) {
         if (!(other instanceof NestedPair))
            return false;
         NestedPair otherPair = (NestedPair) other;
         return otherPair.parent.equals(parent) && otherPair.child.equals(child);
      }

      public int hashCode() {
         return parent.hashCode() + child.hashCode();
      }
   }

   public static void start(String name) {
      start(name, true, false);
   }

   public static void start(String name, boolean includeInParent) {
      start(name, includeInParent, false);
   }

   public static boolean inProgress(ArrayList<PerfOp> currentList, String name) {
      if (!inProgressEnabled)
         return false;
      for (PerfOp op:currentList) {
         if (op.name.equals(name) && op.recording)
            return true;
      }
      return false;
   }

   public static void start(String name, boolean includeInParent, boolean includeAll) {
      if (!enabled)
         return;
      long now = System.nanoTime();

      ArrayList<PerfOp> current = currentOp.get();
      if (current == null) {
         current = new ArrayList<PerfOp>(100);
         currentOp.set(current);
      }
      int top = current.size() - 1;
      if (top >= 0) {
         PerfOp curOp = current.get(top);

         if (curOp.name.equals(name) && curOp.recording) {
            curOp.nestedCount++;
            return;
         }

         if (!includeInParent) {
            for (int parIx = top; parIx >= 0; parIx--) {
               PerfOp parOp = current.get(parIx);
               parOp.yield(now);
            }
         }
         NestedPair pair = new NestedPair();
         pair.parent = curOp.name;
         pair.child = name;
         if (nestedPairs.get(pair) == null) {
            nestedPairs.put(pair, Boolean.TRUE);
            ArrayList<String> children = statChildren.get(pair.parent);
            if (children == null) {
               children = new ArrayList<String>();
               statChildren.put(pair.parent, children);
            }
            children.add(pair.child);
         }
      }
      PerfOp op = new PerfOp();
      op.name = name;
      op.startTime = now;
      op.includeInParent = includeInParent;
      op.inProgress = inProgress(current, name);
      op.recording = true;
      op.includeAll = includeAll;
      op.isRoot = top == -1;
      current.add(op);
   }

   public static void end(String name) {
      if (!enabled)
         return;

      long now = System.nanoTime();
      ArrayList<PerfOp> currentList = currentOp.get();
      if (currentList == null) {
         perfMonError("*** Mismatching PerfMon.end call - no start call on the stack for: " + name);
         return;
      }

      int top = currentList.size() - 1;
      if (top < 0) {
         perfMonError("*** Mismatching PerfMon.end call - no start call on the stack for: " + name);
         return;
      }

      PerfOp current = currentList.get(top);

      if (current.name.equals(name)) {
         if (current.nestedCount > 0 && current.recording) {
            current.nestedCount--;
            return;
         }
         PerfStat stats = statTable.get(name);
         if (stats == null) {
            synchronized (statTable) {
               stats = statTable.get(name);
               if (stats == null) {
                  stats = new PerfStat();
                  stats.name = name;
                  stats.includeAll = current.includeAll;
                  stats.includeInParent = current.includeInParent;
                  statTable.put(name, stats);
                  statOrder.add(name);
               }
            }
         }

         if (!current.inProgress) {
            long currentRunTime = current.runTime + (now - (current.startTime));
            stats.totalTime += currentRunTime;
            if (currentRunTime > stats.maxTime)
               stats.maxTime = currentRunTime;
            else if (currentRunTime < stats.minTime || stats.minTime == 0)
               stats.minTime = currentRunTime;
            if (stats.samples.size() < maxSamples)
               stats.samples.add(current);
            stats.count++;
         }
         if (current.isRoot)
            stats.isRoot = true;

         currentList.remove(top);

         if (!current.includeInParent) {
            for (int parIx = top - 1; parIx >= 0; parIx--) {
               if (!current.resume(currentList, parIx, now))
                  break;
            }
         }
      }
      else {
         perfMonError("*** Mismatching PerfMon.end call - closing: " + name + " encountered: " + current.name + " instead");
      }
   }

   public static void yield(String name) {
      if (!enabled)
         return;
      ArrayList<PerfOp> current = currentOp.get();
      int top = current == null ? -1 : current.size() - 1;
      if (top == -1) {
         perfMonError("Invalid yield call for: " + name + ": no current op");
         return;
      }

      for (int i = top; i >= 0; i--) {
         PerfOp currentOp = current.get(i);
         if ((name == null || currentOp.name.equals(name))) {
            if (currentOp.recording)
               currentOp.yield(System.nanoTime());
            return; // success
         }
      }
      perfMonError("*** yield call for: " + name + " not in the current thread's call stack");
   }

   private static void perfMonError(String err) {
      if (!enabled)
         return;
      System.err.println(err + " (perf mon globally disabled)");
      enabled = false;
      new Throwable().printStackTrace();
   }

   public static void resume(String name) {
      if (!enabled)
         return;
      ArrayList<PerfOp> current = currentOp.get();
      int top = current == null ? -1 : current.size() - 1;
      if (top == -1) {
         perfMonError("Invalid resume call for: " + name + ": no current op");
         return;
      }

      for (int i = top; i >= 0; i--) {
         PerfOp currentOp = current.get(i);
         if (name == null || currentOp.name.equals(name)) {
            currentOp.resume(current, i, System.nanoTime());
            return; // success
         }
      }
      perfMonError("*** resume call for: " + name + " not in the current thread's call stack");
   }

   public static void dump() {
      System.out.println("\n\nPerfMon stats\nname, options, total(secs), count(x), min(secs), max(secs)");
      for (String statName:statOrder) {
         PerfStat stat = statTable.get(statName);
         // If this stat does not exist as the first of the two pairs
         if (isRootStat(statName)) {
            stat.printAll(null, 0);
         }
      }
      System.out.println("---");

      /*
      System.out.println("\n\nDependencies ---:");
      for (Map.Entry<NestedPair,Boolean> ent:nestedPairs.entrySet()) {
         NestedPair pair = ent.getKey();
         System.out.println("  " +  pair.parent + " includes " + pair.child);
      }
      System.out.println("---");
      */
   }

   public static void dumpStack() {
      ArrayList<PerfOp> current = currentOp.get();
      if (current == null)
         System.out.println("No current PerfMon operations - PerfMon.enabled = " + enabled);
      else {
         for (int i = 0; i < current.size(); i++) {
            PerfOp op = current.get(i);
            System.out.println(op.name);
         }
      }
   }


   public static String current() {
      StringBuilder sb = new StringBuilder();
      ArrayList<PerfOp> current = currentOp.get();
      if (current != null)
         for (PerfOp cur:current)
            sb.append(cur + " ");
      System.out.println("current:" + current);
      return sb.toString();
   }

   static DecimalFormat formatter = new DecimalFormat("#.##");
   public static String formatNanoTime(long time) {
      return formatter.format(time / 1000000000.0);
   }

}
