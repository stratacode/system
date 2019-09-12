package sc.dyn;

import sc.obj.CurrentScopeContext;
import sc.obj.ScopeDefinition;

import java.util.ArrayList;
import java.util.List;

@sc.js.JSSettings(jsModuleFile="js/scgen.js", prefixAlias="sc_")
public class ScheduledJob {
   public Runnable toInvoke;
   /** higher priority jobs run first */
   public int priority;
   public CurrentScopeContext curScopeCtx;

   public static void addToJobList(List<ScheduledJob> jobList, ScheduledJob newJob) {
      int i;
      int len = jobList.size();
      for (i = 0; i < len; i++) {
         ScheduledJob oldSJ = jobList.get(i);
         if (oldSJ.priority < newJob.priority)
            break;
      }
      if (i == len)
         jobList.add(newJob);
      else
         jobList.add(i, newJob);
   }

   public static boolean removeJobFromList(List<ScheduledJob> jobList, ScheduledJob toRemove) {
      return jobList.remove(toRemove);
   }

   public static void runJobList(List<ScheduledJob> toRunLater, int minPriority, int maxPriority) {
      int runCt = 0;
      ArrayList<ScheduledJob> toRestore = null;
      do {
         runCt++;
         ArrayList<ScheduledJob> toRunNow = new ArrayList<ScheduledJob>(toRunLater);
         toRunLater.clear();
         for (int i = 0; i < toRunNow.size(); i++) {
            ScheduledJob toRun = toRunNow.get(i);
            if (toRun.priority >= minPriority && toRun.priority < maxPriority)
               toRun.run();
            else {
               if (toRestore == null)
                  toRestore = new ArrayList<ScheduledJob>();
               toRestore.add(toRun);
            }
         }
         if (runCt > 16) {
            System.err.println("*** execLaterJobs - exceeded indirection count!");
            return;
         }
      }
      while (toRunLater.size() > 0); // Make sure we run any jobs found when running these jobs

      if (toRestore != null)
         toRunLater.addAll(toRestore);
   }

   public void run() {
      boolean pushed = false;
      boolean released = false;
      CurrentScopeContext envCtx = null;
      if (curScopeCtx != null) {
         envCtx = CurrentScopeContext.getThreadScopeContext();
         if (envCtx != curScopeCtx) {
            if (ScopeDefinition.trace) {
               System.out.println("Restoring scope ctx for scheduled job: " + curScopeCtx + " prev ctx: " + envCtx);
            }
            if (envCtx != null) {
               envCtx.releaseLocks();
               released = true;
            }
            CurrentScopeContext.pushCurrentScopeContext(curScopeCtx, true);
            pushed = true;
         }
      }
      try {
         toInvoke.run();
      }
      finally {
         if (pushed) {
            CurrentScopeContext.popCurrentScopeContext(true);
            if (released)
               envCtx.acquireLocks();
         }
      }
   }
}
