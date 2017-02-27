package sc.dyn;

import java.util.List;

public class ScheduledJob {
   public Runnable toInvoke;
   public int priority;

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
}
