package sc.layer;

import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class BuildInfoData implements Serializable {
   public Map<String, Set<String>> syncTypeNames = new HashMap<String,Set<String>>();
   public Map<String,Set<String>> resetSyncTypeNames = new HashMap<String,Set<String>>();

   public Set<String> compiledTypes = new HashSet<String>();

   public Map<String,Set<String>> remoteMethodRuntimes = new HashMap<String,Set<String>>();
}
