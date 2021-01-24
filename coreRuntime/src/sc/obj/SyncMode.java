/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import sc.js.JSSettings;

/** 
 * Represents the various sync modes for a given class or property.  
 * It can be enabled, disabled, set to automatic, enabled only for one direction - client to server or server to client, or
 * restored to it's default value.
 * When you set the SyncMode to Automatic, any objects that exist in more than one runtime are synchronized in those runtimes automatically.
 * Essentially overlapping types and properties are given the @Sync(syncMode=SyncMode.Enabled) annotation by default so that the two runtimes are
 * properly synchronized.  The code-generation phase looks at which properties have initializers to determine the sync behavior.  When a property
 * has a forward binding expression only, it's value is not synchronized since it's computed from other properties.  When it's initialized on
 * the client and server, its initDefault flag is set to false.  When it only exists in one runtime, it's not synchronized at all.
 * <p>
 *    The SyncMode is inherited by a subclass unless the subclass sets SyncMode itself.  In that case, the SyncMode of the base class is
 *    used for all properties in the base class and the SyncMode on the subclass is used only for those fields/properties added by the
 *    subclass.
 * </p>
 * <p>
 *    If the SyncMode is not set on a class at all, the SyncMode of the layer is used if one is set in the layer definition file.
 * </p>
 * <p>
 *    In some cases you may want to explicitly enable or disable synchronization for a class,
 *    but then allow the layer's sync mode to determine whether subclasses of a particular class should synchronize fields in
 *    the subclass.  In this case, setting SyncMode to Default causes us to check the SyncMode of the layer and use that instead
 *    of the mode inherited from the subclass.   For example, for the Element class, we disable synchronization.  But for any subclasses
 *    of HtmlPage, we want to use the layer's syncMode if it's set.  In that case, we set SyncMode=Default on HtmlPage.
 * </p>
 */
@JSSettings(jsModuleFile="js/scgen.js", prefixAlias="sc_")
public enum SyncMode {
   /** Sync mode is on for this type or property */
   Enabled,
   /** Sync mode is off for this type or property */
   Disabled,
   /** Sync on only for overlapping types/properties in the client and server runtimes - i.e. where the definition exists in layers included in both runtimes. */
   Automatic,
   /** Send changes from client to server only or server to client only */
   ClientToServer, ServerToClient,
   /**
    * Use the sync mode from the layer definition file of the type or property. Allows framework classes to define sync
    * behavior in base classes, then revert to the layer's sync mode for any subclass sync behavior.
    */
   Default
}
