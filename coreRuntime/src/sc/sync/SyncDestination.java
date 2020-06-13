/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.sync;

import sc.bind.Bind;
import sc.bind.Bindable;
import sc.bind.BindingContext;
import sc.bind.IListener;
import sc.dyn.DynUtil;
import sc.obj.ScopeDefinition;
import sc.obj.Sync;
import sc.obj.SyncMode;
import sc.type.IResponseListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import static sc.sync.SyncManager.verbose;

/** Represents the connection to the client, server or remote system */
@sc.js.JSSettings(jsModuleFile="js/sync.js", prefixAlias="sc_")
@Sync(syncMode= SyncMode.Disabled)
public abstract class SyncDestination {
   public final static String SYNC_LAYER_START = "sync:";
   public final static int SYNC_LAYER_START_LEN = SYNC_LAYER_START.length();

   /** General error for sync failure */
   public final int SYNC_FAILED_ERROR = -2;

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

   /** Stores the runtime name used for determining what methods are exposed for this runtime. */ // TODO: would we ever want to use one destination for more than one runtime context?  Maybe this belongs in the Context object?
   public String remoteRuntimeName = "java";

   /** The number of sync requests that send changes that are in progress against this destination */
   public int numSendsInProgress = 0;

   /** Number of sync requests which have the 'waitTime' set - i.e. the number of syncs where we are only receiving changes */
   public int numWaitsInProgress = 0;

   /** Should this destination implement real-time semantics - i.e. where the client receives changes from the server automatically */
   public boolean realTime = true;

   /** How much time should we wait on the client after completing a sync before we start the next sync in case the server has more changes */
   public int pollTime = realTime ? 500 : -1;

   /** Are we currently connected to the remote destination?  Set to true after a successful response and to false after a server error */
   private boolean connected = false;

   public int defaultReconnectTime = 500;

   public int maxReconnectTime = 60*5*1000; // try to reconnect at least once every 5 minutes when in real time mode

   public int currentReconnectTime = defaultReconnectTime;

   public int defaultTimeout = 60000;

   /** Set by components like ServletSyncDestination via the initOnStartup hook */
   public static SyncDestination defaultDestination;

   /**
    * Takes a sync request string, already converted to the format required by the destination (e.g. JS for JS).  There's an optional list of parameters, a listener
    * from which you can receive response events (if this is writing a client request), and an optional buffer of codeUpdates
    *
    * May thrown a RuntimeIOException if the write fails due to a connection problem.
    */
   public abstract void writeToDestination(String syncRequestStr, String syncGroup, IResponseListener listener, String paramStr, CharSequence codeUpdates);

   /**
    * Takes as input a serialized sync layer and translates it to the format required by this destination.
    * If no language conversion is required, just returns the input string.
    * For Java to Javascript, this parses the code found in the layer def into a Java model, translates that to Javascript, and returns the format including the Javascript
    */
   public abstract StringBuilder translateSyncLayer(String layerDef);

   /**
    * Applies the changes received from the sync layers received from the remote definition. Called on both the client and server to apply received
    * changes and handles different formats.
    * When receiving JS on the client, eval the returned Javascript.
    * When receiving SC or json, parse the layer definition and apply it as a set of changes to the instances.
    * The receiveLanguage may be specified or if null, looks for SYNC_LAYER_START to find the receive language in the text received.
    * The detail is for debug messages - in what context is the applySyncLayer being performed (e.g. init, response)
    * Returns true if any changes were applied.
    */
   public boolean applySyncLayer(String input, String receiveLanguage, ScopeDefinition syncScope, boolean applyingRemoteReset, String detail) {
      SyncManager.SyncState oldSyncState = SyncManager.getSyncState();

      // applyingRemoteReset is true when this received layer is restoring sync'd state from a new client - either
      // we lost the session or are using request scope components.
      // After we've resolved all of the objects which are referenced on the client, we call sendSync with 'markAsSentOnly=true'
      // to skip the send of stuff we already sent when we initialized this client.
      // TODO: maybe the client should send two different layers during a reset - for the committed and uncommitted changes.
      // That way, all of the committed changes would be applied before the resetSync.  As it is now, those changes will be applied after this sendSync call.
      if (applyingRemoteReset) {
         SyncManager.sendSync(name, null, false, true, null, null);
      }

      // We'll queue only the validate events. That way, listeners still receive invalidate events while deserializing the stream.
      // This helps for a use case involving properties that dynamically expose new component trees for synchronization that are
      // based on properties set earlier in the sync. If we don't deliver the events, we don't know the list value to use for
      // creating the components which are set later on. So basically that component needs to flush out current events, sync it's
      // repeat value, then turn the queue back on again. We do want to queue events to batch up changes, eliminating costly or
      // invalid intermediate results caused when trying to send the events using syncType=immediate. The list components are
      // framework components so they can do a little more work. They listen for the immediate invalidate events to mark their
      // state as invalid. When an attempt is made to resolve a child, it will flush the events, then validate it's children.
      BindingContext ctx = new BindingContext(IListener.SyncType.QUEUE_VALIDATE_EVENTS);
      BindingContext oldBindCtx = BindingContext.getBindingContext();
      BindingContext.setBindingContext(ctx);

      boolean anyChanges = false;

      while (input != null && input.length() > 0) {
         String layerDef = input;
         boolean success = false;
         // When no receiveLanguage is known at this time, we must parse it from the header in the value itself
         if (receiveLanguage == null) {
            // Otherwise, it's a string of the form sync:language:len:data:sync:language:len:data
            // Supporting more than one format - e.g. json and js when there are code updates to be applied
            if (layerDef.startsWith(SYNC_LAYER_START)) {
               int endLangIx = layerDef.indexOf(':', SYNC_LAYER_START_LEN);
               if (endLangIx != -1) {
                  receiveLanguage = layerDef.substring(SYNC_LAYER_START_LEN, endLangIx);
                  int lenStart = endLangIx + 1;
                  if (layerDef.length() > lenStart) {
                     int endLenIx = layerDef.indexOf(':', lenStart);
                     if (endLenIx != -1) {
                        String lenStr = layerDef.substring(lenStart, endLenIx);
                        try {
                           int syncLen = Integer.parseInt(lenStr);
                           int layerDefStart = endLenIx + 1;
                           layerDef = layerDef.substring(layerDefStart, layerDefStart + syncLen);
                           // Process the next chunk on the next iteration of the loop.
                           input = input.substring(layerDefStart + syncLen + 1);
                           success = true;
                        }
                        catch (NumberFormatException exc) {
                        }
                     }
                  }
               }
            }
         }
         else {
            success = true;
            input = null;
         }

         try {
            if (receiveLanguage == null || !success)
               throw new IllegalArgumentException("Invalid sync layer header for applySyncLayer");
            SyncManager.setSyncState(SyncManager.SyncState.ApplyingChanges);
            SerializerFormat format = SerializerFormat.getFormat(receiveLanguage);
            if (format != null) {
               if (format.applySyncLayer(name, syncScope == null ? defaultScope : syncScope.name, layerDef, applyingRemoteReset, allowCodeEval, ctx))
                  anyChanges = true;
            }
            else
               throw new IllegalArgumentException("Unrecognized receive language for applySyncLayer: " + receiveLanguage);
         }
         finally {
            BindingContext.setBindingContext(oldBindCtx);
            ctx.dispatchEvents(null);
            SyncManager.setSyncState(oldSyncState);
         }

         if (input != null)
            receiveLanguage = null; // Reset this so we look for it the next time
      }

      if (anyChanges)
         SyncManager.callAfterApplySync();

      return anyChanges;
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

   /** True if this destination is the client that is sending a sync - returns false for the server */
   public abstract boolean isClient();

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
      boolean anyChanges; // Did the sync request contain any changes
      public SyncListener(ArrayList<SyncLayer> sls, boolean anyChanges) {
         syncLayers = sls;
         clientContext = sls.get(sls.size()-1).syncContext;
         this.anyChanges = anyChanges;
      }

      public void completeSync(Integer errorCode, String message) {
         //System.out.println("Completing sync with changes: " + anyChanges + " numSends=" + numSendsInProgress + " numWaits=" + numWaitsInProgress);
         updateInProgress(false, anyChanges);
         if (syncLayers != null) {
            for (SyncLayer syncLayer:syncLayers) {
               syncLayer.completeSync(clientContext, errorCode, message);
            }
         }
         postCompleteSync();
      }

      public void response(Object response) {
         String responseText = (String) response;
         setConnected(true);
         completeSync(null, null);
         SyncManager.setCurrentSyncLayers(syncLayers);
         SyncManager.setSyncState(SyncManager.SyncState.ApplyingChanges);
         try {
            applySyncLayer(responseText, null, null, false, "response");
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
            System.out.println("Error 205 for sync with changes: " + anyChanges + " numSends=" + numSendsInProgress + " numWaits=" + numWaitsInProgress);
            // TODO: do we need this call here?
            //updateInProgress(false, anyChanges);
            setConnected(true);
            ArrayList<SyncLayer> toSend = clientContext.getChangedSyncLayers(null);
            SyncResult res = sendResetSync(clientContext, toSend);
         }
         // Server went away and told us it wasn't coming back so turn off realTime and we are now disconnected
         else if (errorCode == 410) {
            System.out.println("Error 410 for sync with changes: " + anyChanges + " numSends=" + numSendsInProgress + " numWaits=" + numWaitsInProgress);
            // TODO: do we need this call here?
            //updateInProgress(false, anyChanges);
            setConnected(false);
            realTime = false;
            System.out.println("*** Server shutdown");
         }
         else {
            boolean serverError = errorCode == 500 || errorCode == 0;
            if (serverError) {
               System.out.println("*** Server error - code: " + errorCode);
            }
            // If we're on the client and we get a server error, mark us as disconnected
            if (serverError && isClient()) {
               if (connected) {
                  setConnected(false);
                  currentReconnectTime = defaultReconnectTime;
               }
               else {
                  currentReconnectTime *= 2;
                  if (currentReconnectTime > maxReconnectTime)
                     currentReconnectTime = maxReconnectTime;
               }

               // And for real time situations, try to reconnect - doubling the waitTime with each attempt
               if (realTime) {
                  syncManager.scheduleConnectSync(currentReconnectTime);
               }
            }
            System.out.println("Other Error for sync with changes: " + anyChanges + " numSends=" + numSendsInProgress + " numWaits=" + numWaitsInProgress);
            completeSync(errorCode, error == null ? null : error.toString());
            if (!serverError)
               applySyncLayer((String) error, null, null, false, "error response");
         }
      }
   }

   public SyncResult sendResetSync(SyncManager.SyncContext clientContext, ArrayList<SyncLayer> layers) {
      String errorMessage = null;
      boolean anyChanges;
      boolean complete = false;
      try {
         // Although we are sending the initial sync layers - for the client, the changes since the initial sync, not just pending changes we still need to mark these
         // changed layers as pending since they are included in the initial sync. When we get the response, we need to clear them out so they don't trigger another sync
         for (SyncLayer layer:layers) {
            layer.markSyncPending();
         }

         CharSequence initSync = SyncManager.getInitialSync(name, clientContext.scope.getScopeDefinition().scopeId, false, null, null);
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
         // We've serialized all of the client's changes in the resync, including changes in the requests which had an error.
         // But if there were any fetch properties, those will just be lost so we need to collect them and add them to the end
         // of the resync.
               /*
               for (SyncLayer sl:syncLayers) {
                  sb.append(sl.serialize(clientContext, null, true));
               }
               */
         anyChanges = sb.length() > 0;
         System.out.println("Sending reset sync with changes: " + anyChanges + " numSends=" + numSendsInProgress + " numWaits=" + numWaitsInProgress);
         updateInProgress(true, anyChanges);
         writeToDestination(sb.toString(), null, new SyncListener(layers, anyChanges), "reset=true", null);
         complete = true;
      }
      finally {
         completeSync(layers, complete, errorMessage);
      }
      return new SyncResult(anyChanges, errorMessage);
   }

   public void updateInProgress(boolean start, boolean anyChanges) {
      // TODO: do we need to sync here for thread safety?  Right now we are only sending on the client where it's not an issue
      int incr = (start ? 1 : -1);

      if (anyChanges) {
         numSendsInProgress += incr;
         if (isClient())
            syncManager.setNumSendsInProgress(syncManager.getNumSendsInProgress() + incr);
      }
      else {
         numWaitsInProgress += incr;
      }

      if (numSendsInProgress < 0 || numSendsInProgress > 10 || numWaitsInProgress < 0 || numWaitsInProgress > 10) {
         System.err.println("*** Warning: sync destination - invalid parameters: numSends=" + numSendsInProgress + " numWaits=" + numWaitsInProgress);
      }
   }

   /** Called after we've finished the entire sync.  For client destinations, this is the hook to see if we need to start another sync to obtain real-time changes */
   public void postCompleteSync() {
   }

   /**
    * Use markAsSentOnly = true to not send any changes made yet on this connection. It's used as an optimization
    * during the client-side 'reset' to avoid resending changes queued up during page initialization - since, the client
    * will have received those changes on it's initial request.
    */
   public SyncResult sendSync(SyncManager.SyncContext parentContext, ArrayList<SyncLayer> layers, String syncGroup, boolean markAsSentOnly, CharSequence codeUpdates, Set<String> syncTypeFilter) {
      SyncSerializer lastSer = null;
      HashSet<String> createdTypes = new HashSet<String>();
      // Going in sorted order - e.g. global, then session.
      // Changes found during the traversal of the global graph adds changes to the session so we miss these if we go in the reverse order.
      for (int i = 0; i < layers.size(); i++) {
         SyncLayer layer = layers.get(i);
         SyncSerializer nextSer = layer.serialize(parentContext, createdTypes, false, syncTypeFilter);
         layer.markSyncPending();
         if (lastSer == null)
            lastSer = nextSer;
         else
            lastSer.appendSerializer(nextSer);
      }
      boolean complete = false;
      boolean anyChanges = false;
      String errorMessage = null;
      try {
         String layerDef = lastSer == null ? "" : lastSer.getOutput().toString();
         anyChanges = layerDef.length() > 0;
         if (SyncManager.trace) {
            // For scn, want easy way to debug the sc version, not the JS code
            String debugDef = lastSer == null ? "" : lastSer.getDebugOutput().toString();
            if (markAsSentOnly)
               System.out.println("Marking changes as sent for " + parentContext + " to: " + name + " size: " + debugDef.length() + "\n" + debugDef);
            else if (layerDef.trim().length() > 0)
               System.out.println("Sending sync from " + parentContext + " thread:" + DynUtil.getCurrentThreadString() + " to: " + name + " size: " + debugDef.length() + "\n" + debugDef);
         }
         if (!markAsSentOnly) {
            //if (numWaitsInProgress > 5)
            //   System.out.println("*** More than 5 waits in progress");
            //System.out.println("Sending sync with changes: " + anyChanges + " numSends=" + numSendsInProgress + " numWaits=" + numWaitsInProgress);
            updateInProgress(true, anyChanges);
            writeToDestination(layerDef, syncGroup, new SyncListener(layers, anyChanges), null, codeUpdates);
         }
         complete = true;
      }
      catch (RuntimeIOException ioexc) {
         if (verbose)
            System.out.println("RuntimeIOException writing to destination: " + ioexc);
         complete = true; // don't want to log the error for this - just let the caller figure out how to reconnect or end the connection
         throw ioexc;
      }
      catch (Exception exc) {
         errorMessage = exc.toString();
         System.out.println("Error occurred sending sync: " + errorMessage);
         exc.printStackTrace();
      }
      finally {
         completeSync(layers, complete, errorMessage);
      }
      return new SyncResult(anyChanges, errorMessage);
   }

   private void completeSync(ArrayList<SyncLayer> layers, boolean complete, String errorMessage) {
      if (!complete && errorMessage == null)
         errorMessage = "Sync failed.";
      SyncManager.SyncContext clientContext = layers.get(layers.size()-1).syncContext;
      for (SyncLayer syncLayer:layers) {
         syncLayer.completeSync(clientContext, errorMessage == null ? null : SYNC_FAILED_ERROR, errorMessage);
      }
      if (errorMessage != null)
         System.err.println("*** Sync failed: " + errorMessage);
   }

   public int getDefaultSyncPropOptions() {
      return SyncPropOptions.SYNC_SERVER;
   }

   @Bindable(manual = true)
   public void setConnected(boolean c) {
      if (c != connected) {
         connected = c;
         Bind.sendChange(this, "connected", c);
      }
   }

   public boolean getConnected() {
      return connected;
   }
}
