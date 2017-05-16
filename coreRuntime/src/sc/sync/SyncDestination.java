/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.sync;

import sc.bind.BindingContext;
import sc.obj.Sync;
import sc.obj.SyncMode;
import sc.type.IResponseListener;

import java.util.ArrayList;
import java.util.HashSet;

/** Represents the connection to the client, server or remote system */
@sc.js.JSSettings(jsModuleFile="js/sync.js", prefixAlias="sc_")
@Sync(syncMode= SyncMode.Disabled)
public abstract class SyncDestination {
   public final static String SYNC_LAYER_START = "sync:";
   public final static int SYNC_LAYER_START_LEN = SYNC_LAYER_START.length();

   public String name;

   public SyncManager syncManager;

   /** The name of the language (aka format) we use for this client/server or server/client connection */
   public String sendLanguage;

   /** The name of the we actually send to the remote side after possibly converting it (i.e. for stratacode, it's js but for json it's still json) */
   public String outputLanguage;

   /** Set to true if we are allowed to run code generated on the remote side.  For example, the client can set this to true because the server is trusted but not vice-versa */
   public boolean allowCodeEval = false;

   /** The name of the scope on which we apply synchronized changes */
   public String defaultScope;

   /** Is this a server or client destination?  Object's received by the client destination are not pushed back to the server on a reset. */
   public boolean clientDestination = false;

   /** Set by components like ServletSyncDestination via the initOnStartup hook */
   public static SyncDestination defaultDestination;

   /** Takes a sync request string, already converted to the format required by the destination (e.g. JS for JS) */
   public abstract void writeToDestination(String syncRequestStr, String syncGroup, IResponseListener listener, String paramStr);

   /**
    * Takes as input a serialized sync layer and translates it to the format required by this destination.
    * If no language conversion is required, just returns the input string.
    * For Java to Javascript, this parses the code found in the layer def into a Java model, translates that to Javascript, and returns the format including the Javascript
    */
   public abstract StringBuilder translateSyncLayer(String layerDef);

   /** Applies the layer definition received from the remote definition.  For JS, we'll eval the returned Javascript.  For stratacode or json, we'll parse the layer definition and apply it as a set of changes to the instances.
    * The receiveLanguage may be specified or if null, we look for SYNC_LAYER_START and pull the receive language out of the text
    * */
   public void applySyncLayer(String layerDef, String receiveLanguage, boolean resetSync) {
      SyncManager.SyncState oldSyncState = SyncManager.getSyncState();

      // After we've resolved all of the objects which are referenced on the client, we do a "reset" since this will line us
      // up with the state that's on the client.
      // TODO: maybe the client should send two different layers during a reset - for the committed and uncommitted changes.
      // That way, all of the committed changes would be applied before the resetSync.  As it is now, those changes will be applied after this sendSync call.
      if (resetSync) {
         SyncManager.sendSync(name, null, true);
      }

      // Queuing up events so they are all fired after the sync has completed
      BindingContext ctx = new BindingContext();
      BindingContext oldBindCtx = BindingContext.getBindingContext();
      BindingContext.setBindingContext(ctx);

      if (layerDef.length() > 0) {
         // When no receiveLanguage is known at this time, we must parse it from the header in the value itself
         if (receiveLanguage == null) {
            if (layerDef.startsWith(SYNC_LAYER_START)) {
               int endLangIx = layerDef.indexOf(':', SYNC_LAYER_START_LEN);
               if (endLangIx != -1) {
                  if (layerDef.length() > endLangIx + 1) {
                     receiveLanguage = layerDef.substring(SYNC_LAYER_START_LEN, endLangIx);
                     layerDef = layerDef.substring(endLangIx + 1);
                  }
               }
            }
            if (receiveLanguage == null)
               throw new IllegalArgumentException("Invalid sync layer header for applySyncLayer");
         }

         try {
            SyncManager.setSyncState(SyncManager.SyncState.ApplyingChanges);
            SerializerFormat format = SerializerFormat.getFormat(receiveLanguage);
            if (format != null) {
               format.applySyncLayer(name, defaultScope, layerDef, resetSync, allowCodeEval);
            }
            else
               throw new IllegalArgumentException("Unrecognized receive language for applySyncLayer: " + receiveLanguage);
         }
         finally {
            BindingContext.setBindingContext(oldBindCtx);
            ctx.dispatchEvents(null);
            SyncManager.setSyncState(oldSyncState);
         }
      }
   }

   public SyncSerializer createSerializer() {
      return SerializerFormat.getFormat(getSendLanguage()).createSerializer(syncManager);
   }

   public String getSendLanguage() {
      if (sendLanguage == null)
         return SyncManager.defaultLanguage;
      return sendLanguage;
   }

   public String getOutputLanguage() {
      if (outputLanguage != null)
         return outputLanguage;
      return getSendLanguage().equals("stratacode") ? "js" : getSendLanguage();
   }

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
            applySyncLayer(responseText, null, false);
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
            CharSequence initSync = SyncManager.getInitialSync(name, clientContext.scope.getScopeDefinition().scopeId, false, null);
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
            applySyncLayer((String) error, null, false);
         }
      }
   }

   public void updateInProgress(boolean start) {
      // TODO: do we need to sync here for thread safety?  Right now we are only sending on the client where it's not an issue
      if (isSendingSync())
         syncManager.setNumSendsInProgress(syncManager.getNumSendsInProgress() + (start ? 1 : -1));
   }

   public boolean sendSync(SyncManager.SyncContext parentContext, ArrayList<SyncLayer> layers, String syncGroup, boolean resetSync) {
      SyncSerializer lastSer = null;
      HashSet<String> createdTypes = new HashSet<String>();
      // Going in sorted order - e.g. global, then session.
      // Changes found during the traversal of the global graph adds changes to the session so we miss these if we go in the reverse order.
      for (int i = 0; i < layers.size(); i++) {
         SyncLayer layer = layers.get(i);
         SyncSerializer nextSer = layer.serialize(parentContext, createdTypes, false);
         layer.markSyncPending();
         if (lastSer == null)
            lastSer = nextSer;
         else
            lastSer.appendSerializer(nextSer);
      }
      boolean complete = false;
      String errorMessage = null;
      try {
         String layerDef = lastSer == null ? "" : lastSer.getOutput().toString();
         if (SyncManager.trace) {
            // For scn, want easy way to debug the sc version, not the JS code
            String debugDef = lastSer == null ? "" : lastSer.getDebugOutput().toString();
            if (resetSync)
               System.out.println("Reset sync to destination: " + name + " size: " + debugDef.length() + "\n" + debugDef);
            else if (layerDef.trim().length() == 0)
               System.out.println("Empty sync to destination: " + name);
            else
               System.out.println("Sending sync to destination: " + name + " size: " + debugDef.length() + "\n" + debugDef);
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
