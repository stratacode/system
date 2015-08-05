/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.dyn;

public interface IScheduler {
   void invokeLater(Runnable r, int priority);
   void execLaterJobs();
}
