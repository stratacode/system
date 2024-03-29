/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.sync;

import sc.dyn.DynUtil;
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

   public final static int SYNC_RESET_STATE = 128;

   /** For serverToClient properties on the server, indicates that these properties are not settable from the client. Do add
    * a sync listener and do send changes to the client as normal. */
   public final static int SYNC_SEND_ONLY = 256;

   public String propName;
   public int flags;
   public boolean hasDefault;
   public Object defaultValue;

   public SyncPropOptions(String propName, int flags) {
      this.propName = propName;
      this.flags = flags;
   }

   public SyncPropOptions(String propName, int flags, Object defaultValue) {
      this(propName, flags);
      hasDefault = true;
      this.defaultValue = defaultValue;
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
      if ((flags & SYNC_SEND_ONLY) != 0)
         sb.append(" (send only)");
      if ((flags & SYNC_CONSTANT) != 0)
         sb.append(" (constant)");
      if ((flags & SYNC_STATIC) != 0)
         sb.append(" (static)");
      if ((flags & SYNC_RESET_STATE) != 0)
         sb.append(" (resetState)");
      if (hasDefault)
         sb.append(" fixed default: " + defaultValue);
      return sb.toString();
   }

   public int hashCode() {
      return propName == null ? 0 : propName.hashCode();
   }

   public boolean equals(Object other) {
      if (!(other instanceof SyncPropOptions))
         return false;
      SyncPropOptions op = (SyncPropOptions) other;
      if (op.flags != flags)
         return false;
      if (DynUtil.equalObjects(op.propName, propName))
         return false;
      return true;
   }
}
