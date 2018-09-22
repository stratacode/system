package sc.js;

import sc.obj.IObjectId;

import java.util.Map;

@JSSettings(jsModuleFile="js/scgen.js", prefixAlias="sc_")
public class ServerTagManager implements IObjectId {
   public Map<String,ServerTag> serverTags;

   @Override
   public String getObjectId() {
      return "sc.js.PageServerTagManager";
   }
}
