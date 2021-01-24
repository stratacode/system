/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.sync;

import sc.obj.Sync;
import sc.obj.SyncMode;

@sc.js.JSSettings(jsModuleFile="js/sync.js", prefixAlias="sc_")
@Sync(syncMode= SyncMode.Disabled)
/**
 * Flags which you can combine using the 'or' operator for the flags parameter when building a SyncProperties for a given type or
 * instance.  These flags modify the default behavior for all properties specified in that SyncProperties object, i.e. those specified
 * with just a property name in the list of properties.   For custom control over an individual property, use an instance of
 * SyncPropOption in the list of properties when you build the SyncProperties object.
 */
public class SyncOptions {
   /**
    * Specifies that by default each initial property value should be explicitly initialized on the remote side when the object is created.
    * When this is not set, the client is assumed to already have the initial value
    */
   public final static int SYNC_INIT_DEFAULT = 1;
   /** Specifies that this property will not change.  It's state may still be sent across if init default is set to true but no listeners will be added. */
   public final static int SYNC_CONSTANT = 2;
}
