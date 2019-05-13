package sc.js;

import java.util.*;

/** Used to gather up the changes in the serverTags for a given server request (does not run in the client) */
public class ServerTagContext {
   /** Pass in null for oldServerTags the first time.  In that case,  */
   public ServerTagContext(ServerTagManager mgr) {
      if (mgr != null) {
         this.serverTags = mgr.getServerTags();
         this.serverTagTypes = mgr.getServerTagTypes();
         this.firstTime = serverTags == null;
         if (serverTags != null) {
            // Clear the marked flag. It gets set when we find this server tag in the tree so we can detect tags that get removed
            for (ServerTag st:serverTags.values()) {
               st.marked = false;
            }
         }
      }
      else
         firstTime = true;
   }
   /** Stores the previous set of serverTags */
   public Map<String,ServerTag> serverTags = null;

   public void addServerTag(String tagId, ServerTag st) {
      if (serverTags == null)
         serverTags = new LinkedHashMap<String,ServerTag>();
      serverTags.put(tagId, st);
   }

   /**
    * True when the server tags are brand new - i.e. the serverTags list contains the complete list and we need to
    * send that entirely over to the client each time.
    */
   public boolean firstTime;

   /** If a syncTypeFilter is used, contains the set of the type names we need to approve for updating for ServerTags in the sync system */
   public Set<String> serverTagTypes = null;

   public Map<String,ServerTag> newServerTags = null;
   public Set<String> removedServerTags = null;

   public void addNewServerTag(String tagId, ServerTag newSt) {
      if (newServerTags == null)
         newServerTags = new LinkedHashMap<String,ServerTag>();
      newServerTags.put(tagId, newSt);
   }

   public void removeServerTag(String tagId) {
      if (removedServerTags == null)
         removedServerTags = new LinkedHashSet<String>();
      removedServerTags.add(tagId);
   }

   public void updateServerTag(String tagId, ServerTag tag) {
      if (firstTime) {
         if (tag != null)
            addServerTag(tagId, tag);
      }
      else {
         if (tag != null) {
            ServerTag old = serverTags.get(tagId);
            if (old == null)
               addNewServerTag(tagId, tag);
            else {
               old.marked = true;
               // TODO: not handling changes to the event props for a tag right now.
            }
         }
         else if (serverTags.get(tagId) != null)
            removeServerTag(tagId);
      }
   }

   public void removeUnused() {
      if (firstTime)
         return;
      if (serverTags != null) {
         for (ServerTag st:serverTags.values()) {
            if (!st.marked) {
               removeServerTag(st.id);
            }
         }
      }
   }
}
