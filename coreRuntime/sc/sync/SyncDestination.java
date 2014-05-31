/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.sync;

import sc.obj.Sync;
import sc.obj.SyncMode;
import sc.type.IResponseListener;

import java.util.ArrayList;
import java.util.HashSet;

/** Represents the connection to the client, server or remote system */
@sc.js.JSSettings(jsModuleFile="js/sync.js", prefixAlias="sc_")
@Sync(syncMode= SyncMode.Disabled)
public abstract class SyncDestination {
   public String name;

   public SyncManager syncManager;

   /** Set by components like ServletSyncDestination via the initOnStartup hook */
   public static SyncDestination defaultDestination;

   /** Takes a sync request string, already converted to the format required by the destination (e.g. JS for JS) */
   public abstract void writeToDestination(String syncRequestStr, String syncGroup, IResponseListener listener, String paramStr);

   /** Takes as input the streamed version of the SyncVersion - i.e. a SC language definition.  For Java to Java, this returns the layerDef input string.  For Java to Javascript, this parses the language into a Java model, translates that to Javascript, and returns the Javascript  */
   public abstract CharSequence translateSyncLayer(String layerDef);

   /** Applies the layer definition received from the remote definition.  For JS, we'll eval the returned Javascript.  For Java, we'll parse the layer definition and apply it as a set of changes to the instances.  */
   public abstract void applySyncLayer(String layerDef, boolean isReset);

   /** True if this destination is a sending a current sync now (i.e. the client) or receiving one (i.e. the server) */
   public abstract boolean isSendingSync();

   /** Allows this class to be override in subclasses */
   public void initSyncManager() {
      syncManager = new SyncManager(this);
   }

   public SyncDestination() {
      if (defaultDestination == null)
         defaultDestination = this;
   }

   @Sync(syncMode= SyncMode.Disabled)
   public class SyncListener implements IResponseListener {
      ArrayList<SyncLayer> syncLayers;
      SyncManager.SyncContext clientContext;
      public SyncListener(ArrayList<SyncLayer> sls) {
         syncLayers = sls;
         clientContext = sls.get(0).syncContext;
      }

      public void completeSync(boolean error) {
         updateInProgress(false);
         for (SyncLayer syncLayer:syncLayers) {
            syncLayer.completeSync(clientContext, error);
         }
      }

      public void response(Object response) {
         String responseText = (String) response;
         completeSync(false);
         SyncManager.setCurrentSyncLayers(syncLayers);
         SyncManager.setSyncState(SyncManager.SyncState.ApplyingChanges);
         try {
            applySyncLayer(responseText, false);
         }
         finally {
            SyncManager.setCurrentSyncLayers(null);
            SyncManager.setSyncState(null);
         }
      }

      public void error(int errorCode, Object error) {
         // The server reset it's session which means it cannot respond to the sync.  We respond by sending the initial layer
         // to the server to reset it's data to what we have.
         if (errorCode == 205) {
            CharSequence initSync = SyncManager.getInitialSync(name, clientContext.scope.getScopeDefinition().scopeId, false);
            if (SyncManager.trace) {
               if (initSync.length() == 0) {
                  System.out.println("No initial sync for reset");
               }
               else
                  System.out.println("Initial sync for reset: " + initSync);
            }
            StringBuilder sb = new StringBuilder();
            sb.append(initSync.toString());
            // TODO: Remove this?   Right now, this works because we sync the properties which manage the fetched state - so the fetched changes get pushed automatically.
            // We've serialized all of the client's changes in the resync, including changes in the requests which errored.
            // But if there were any fetch properties, those will just be lost so we need to collect them and add them to the end
            // of the resync.
            /*
            for (SyncLayer sl:syncLayers) {
               sb.append(sl.serialize(clientContext, null, true));
            }
            */
            writeToDestination(sb.toString(), null, this, "reset=true");
         }
         else {
            completeSync(true);
            applySyncLayer((String) error, false);
         }
      }
   }

   public void updateInProgress(boolean start) {
      // TODO: do we need to sync here for thread safety?  Right now we are only sending on the client where it's not an issue
      if (isSendingSync())
         syncManager.setNumSendsInProgress(syncManager.getNumSendsInProgress() + (start ? 1 : -1));
   }

   public boolean sendSync(SyncManager.SyncContext parentContext, ArrayList<SyncLayer> layers, String syncGroup, boolean resetSync) {
      StringBuilder sb = new StringBuilder();
      HashSet<String> createdTypes = new HashSet<String>();
      // Going in reverse order - global, then session.
      // Changes found during the traversal of the global graph adds changes to the session so we miss these if we go in the reverse order.
      for (int i = layers.size() - 1; i >= 0; i--) {
         SyncLayer layer = layers.get(i);
         CharSequence syncRequest = layer.serialize(parentContext, createdTypes, false);
         layer.markSyncPending();
         sb.append(syncRequest);
      }
      boolean complete = false;
      String errorMessage = null;
      try {
         String layerDef = sb.toString();
         if (SyncManager.trace) {
            if (resetSync)
               System.out.println("Reset sync to destination: " + name + " size: " + layerDef.length());
            else if (layerDef.trim().length() == 0)
               System.out.println("Empty sync to destination: " + name);
            else
               System.out.println("Sending sync to destination: " + name + " size: " + layerDef.length());
         }
         if (!resetSync) {
            updateInProgress(true);
            writeToDestination(layerDef, syncGroup, new SyncListener(layers), null);
         }
         complete = true;
      }
      catch (Exception exc) {
         errorMessage = exc.toString();
         System.out.println("Error occurred sending sync: " + errorMessage);
         exc.printStackTrace();
      }
      finally {
         if (!complete && errorMessage == null)
            errorMessage = "Sync failed.";
         SyncManager.SyncContext clientContext = layers.get(0).syncContext;
         for (SyncLayer syncLayer:layers) {
            syncLayer.completeSync(clientContext, errorMessage != null);
         }
         if (errorMessage != null)
            System.err.println("*** Sync failed: " + errorMessage);
      }
      return errorMessage != null;
   }

   public int getDefaultSyncPropOptions() {
      return SyncPropOptions.SYNC_SERVER;
   }
}
