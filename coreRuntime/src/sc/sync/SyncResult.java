/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.sync;

/**
 * Returned from sendSync to indicate the status of the sync
 */
public class SyncResult {
   public boolean anyChanges; // true if the sync wrote any changes
   public String errorMessage; // or null for no error

   public SyncResult(boolean anyChanges, String errorMessage) {
      this.anyChanges = anyChanges;
      this.errorMessage = errorMessage;
   }
}
