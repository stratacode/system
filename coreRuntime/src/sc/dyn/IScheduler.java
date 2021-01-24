/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.dyn;

public interface IScheduler {
   final int NO_MIN = Integer.MIN_VALUE;
   final int NO_MAX = Integer.MAX_VALUE;
   ScheduledJob invokeLater(Runnable r, int priority);
   boolean clearInvokeLater(ScheduledJob job);
   void execLaterJobs(int minPriority, int maxPriority);
   boolean hasPendingJobs();
}
