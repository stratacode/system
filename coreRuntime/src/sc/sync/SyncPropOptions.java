/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.sync;

import sc.obj.Sync;
import sc.obj.SyncMode;

@sc.js.JSSettings(jsModuleFile="js/sync.js", prefixAlias="sc_")
@Sync(syncMode=SyncMode.Disabled)
/**
 * Use an instance of this class in the list of properties for your SyncProperties instance to control specified behavior for
 * a given property.  Add one or more flags to override the default SyncOptions specified in SyncProperties.
 */
public class SyncPropOptions {
   /** Send the initial property value to the remote side during the initial sync.  If SYNC_INIT is not set, the remote side is
    assumed to start out synchronized - i.e. the class has compiled in the same default for this property. */
   public final static int SYNC_INIT = 1;
   /** Do not send the value of this property until it's value is requested by the remote side. */
   public final static int SYNC_ON_DEMAND = 2;
   /** For on-demand properties, is this class the client or the server? */
   public final static int SYNC_SERVER = 4;
   /** For on-demand properties, is this class the client or the server? */
   public final static int SYNC_CLIENT = 8;
   /** For clientToServer properties when compiled for the server. We need to add the sync property for authorization. Don't a sync listener for these properties. */
   public final static int SYNC_RECEIVE_ONLY = 16;
   /** Do not add the sync listener for this property, but do send the initial value to the other side */
   public final static int SYNC_CONSTANT = 32;

   /** A static property */
   public final static int SYNC_STATIC = 64;

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
         sb.append(" (client)");
      if ((flags & SYNC_RECEIVE_ONLY) != 0)
         sb.append(" (receive only)");
      if ((flags & SYNC_CONSTANT) != 0)
         sb.append(" (constant)");
      if ((flags & SYNC_STATIC) != 0)
         sb.append(" (static)");
      return sb.toString();
   }
}
