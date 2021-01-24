/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.js;

import sc.obj.IObjectId;

import java.util.ArrayList;

/**
 * Stores information for a tag object which is rendered on the server but needs to be notified of events that
 * happen on the client.  When a page is first rendered on the server, the collection of ServerTags in that page
 * (if any) is built up during the tag.start() method.  We use the tag's parent node as the way to find which
 * page a given tag lives.  This is more flexible than just using the tag object hierarchy because it can include
 * nodes dynamically generated in code, as long as we know the parent tag element and start the tag.
 */
@JSSettings(jsModuleFile="js/schtml.js", prefixAlias="sc_")
public class ServerTag implements IObjectId {

   /** The value of the id attribute for the tag */
   public String id;

   /** List of property names (String) or SyncPropOption for which we need to override the default behavior for this tag. */
   public ArrayList<Object> props;

   /** Set to true for server tags which generate events on the client */
   public boolean eventSource = false;

   /** Set to 'on', 'off', or 'change' to control how the Input tag sends events */
   public String liveEdit = "on";

   /** Set to the number of millis to pause */
   public int liveEditDelay = 0;

   public String initScript = null;

   public String stopScript = null;

   public boolean listenersValid = true;

   public String toString() {
      return "id=" + id + "(" + props + ")";
   }

   public transient boolean marked;

   public int hashCode() {
      return id.hashCode();
   }

   public boolean equals(Object other) {
      if (other instanceof ServerTag) {
         ServerTag ot = (ServerTag) other;
         if (!id.equals(ot.id))
            return false;

         if (this == ot)
            return true;

         if (props != ot.props && (props == null || ot.props == null))
            return false;

         if (eventSource != ot.eventSource)
            return false;

         return true;
      }
      return false;
   }

   public String getObjectId() {
      return "sc.js.st_" + id;
   }
}
