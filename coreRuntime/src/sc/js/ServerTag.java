package sc.js;

import java.util.ArrayList;

/**
 * Stores information for a tag object which is rendered on the server but needs to be notified of events that
 * happen on the client.  When a page is first rendered on the server, the collection of ServerTags in that page
 * (if any) is built up during the tag.start() method.  We use the tag's parent node as the way to find which
 * page a given tag lives.  This is more flexible than just using the tag object hierarchy because it can include
 * nodes dynamically generated in code, as long as we know the parent tag element and start the tag.
 */
@JSSettings(jsModuleFile="js/scgen.js", prefixAlias="sc_")
public class ServerTag {

   /** The value of the id attribute for the tag */
   public String id;

   /** List of property names (String) or SyncPropOption for which we need to override the default behavior for this tag. */
   public ArrayList<Object> props;

   /** Set to true for server tags which generate events on the client */
   public boolean eventSource = false;

   public String toString() {
      return "id=" + id + "(" + props + ")";
   }
}
