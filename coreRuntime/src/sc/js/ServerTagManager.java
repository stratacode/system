package sc.js;

import sc.bind.Bind;
import sc.obj.Exec;
import sc.obj.IObjectId;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@JSSettings(jsLibFiles="js/tags.js", prefixAlias="sc_")
public class ServerTagManager implements IObjectId {
   private Map<String,ServerTag> serverTags;
   public void setServerTags(Map<String,ServerTag> sts) {
      serverTags = sts;
      Bind.sendChangedEvent(this, "serverTags");
   }
   public Map<String,ServerTag> getServerTags() {
      return serverTags;
   }

   private Map<String,ServerTag> newServerTags;
   public void setNewServerTags(Map<String,ServerTag> sts) {
      newServerTags = sts;
      Bind.sendChangedEvent(this, "newServerTags");
   }
   public Map<String,ServerTag> getNewServerTags() {
      return newServerTags;
   }

   private Set<String> removedServerTags;
   public void setRemovedServerTags(Set<String> sts) {
      removedServerTags = sts;
      Bind.sendChangedEvent(this, "removedServerTags");
   }
   public Set<String> getRemovedServerTags() {
      return removedServerTags;
   }

   @Exec(serverOnly=true)
   private Set<String> serverTagTypes;
   public void setServerTagTypes(Set<String> sts) {
      serverTagTypes = sts;
   }
   public Set<String> getServerTagTypes() {
      return serverTagTypes;
   }

   @Override
   public String getObjectId() {
      return "sc.js.PageServerTagManager";
   }

   public void updateServerTags(ServerTagContext stCtx) {
      if (stCtx.firstTime) {
         if (stCtx.serverTags == null)
            return;

         setServerTags(stCtx.serverTags);
         serverTagTypes = stCtx.serverTagTypes;
      }
      else {
         if (stCtx.newServerTags != null)
            setNewServerTags(stCtx.newServerTags);
         if (stCtx.removedServerTags != null)
            setRemovedServerTags(stCtx.removedServerTags);
      }
   }
}
