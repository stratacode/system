package sc.dyn;

import sc.obj.CurrentScopeContext;
import sc.obj.ScopeDefinition;

import java.util.List;

public class ScheduledJob {
   public Runnable toInvoke;
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
