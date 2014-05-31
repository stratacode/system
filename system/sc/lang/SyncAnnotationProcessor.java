/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

public class SyncAnnotationProcessor extends DefaultAnnotationProcessor {
   {
      staticMixinTemplate = "sc.obj.SyncTemplate";
      postAssignment = "<% if (needsSync) { %>\n      sc.sync.SyncManager.addSyncInst(<%=instanceVariableName%>, <%=syncOnDemand%>, <%=syncInitDefault%>, <%=formatString(derivedScopeName)%><%=nextConstructorParams%>);\n <% } %>";
   }

   static SyncAnnotationProcessor defaultProc = new SyncAnnotationProcessor();
   public static SyncAnnotationProcessor getSyncAnnotationProcessor() {
      return defaultProc;
   }
}
