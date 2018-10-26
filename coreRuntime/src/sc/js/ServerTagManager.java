package sc.js;

import sc.bind.Bind;
import sc.obj.IObjectId;

import java.util.Map;

@JSSettings(jsModuleFile="js/schtml.js", prefixAlias="sc_")
public class ServerTagManager implements IObjectId {
   private Map<String,ServerTag> serverTags;
   public void setServerTags(Map<String,ServerTag> sts) {
      serverTags = sts;
      Bind.sendChangedEvent(this, "serverTags");
   }

   public Map<String,ServerTag> getServerTags() {
      return serverTags;
   }

   @Override
   public String getObjectId() {
      return "sc.js.PageServerTagManager";
   }

   public void updateServerTags(Map<String,ServerTag> newSts) {
      if (newSts == serverTags)
         return;
      if (newSts == null)
         return;
      if (serverTags == null || !newSts.equals(serverTags))
         setServerTags(newSts); // TODO - once we add incremental map updates for the sync system, plug that in here
   }
}
