/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.dyn;

public interface IScheduler {
   final int NO_MIN = Integer.MIN_VALUE;
   final int NO_MAX = Integer.MAX_VALUE;
   void invokeLater(Runnable r, int priority);
   void execLaterJobs(int minPriority, int maxPriority);
   boolean hasPendingJobs();
}
