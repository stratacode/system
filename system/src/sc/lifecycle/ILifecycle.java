/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lifecycle;

public interface ILifecycle  {
   public void init();

   public void start();

   public void validate();

   public void process();

   public boolean isInitialized();

   public boolean isStarted();

   public boolean isValidated();

   public boolean isProcessed();

   public void stop();
}
