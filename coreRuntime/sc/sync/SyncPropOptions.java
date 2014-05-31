/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.sync;

import sc.obj.Sync;
import sc.obj.SyncMode;

@sc.js.JSSettings(jsModuleFile="js/sync.js", prefixAlias="sc_")
@Sync(syncMode=SyncMode.Disabled)
public class SyncPropOptions {
   // Synchronization this property when the object or class is intialized.  The default is to assume that sync properties
   // are initialized 'in sync' to avoid this initial sync.
   public final static int SYNC_INIT = 1;
   public final static int SYNC_ON_DEMAND = 2;
   public final static int SYNC_SERVER = 4; // For on-demand properties, is this class the client or the server?
   public final static int SYNC_CLIENT = 8; // For on-demand properties, is this class the client or the server?

   public String propName;
   public int flags;

   public SyncPropOptions(String propName, int flags) {
      this.propName = propName;
      this.flags = flags;
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append(this.propName);
      if ((flags & SYNC_INIT) != 0)
         sb.append(" (init)");
      if ((flags & SYNC_ON_DEMAND) != 0)
         sb.append(" (on demand)");
      if ((flags & SYNC_SERVER) != 0)
         sb.append(" (server)");
      if ((flags & SYNC_CLIENT) != 0)
         sb.append(" (server)");
      return sb.toString();
   }
}
