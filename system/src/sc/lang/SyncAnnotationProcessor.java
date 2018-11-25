/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

public class SyncAnnotationProcessor extends DefaultAnnotationProcessor {
   {
      // This is the name of the template to use for generating 'static init' code for this scope
      staticMixinTemplate = "sc.obj.SyncTemplate";
      // Here's code that gets added for each synchronized instance.  This code is run after an object member variable is assigned to support recursive references.
      postAssignment = "<% if (needsSync) { %>\n      sc.sync.SyncManager.addSyncInst(<%=instanceVariableName%>, <%=syncOnDemand%>, <%=syncInitDefault%>, <%=formatString(derivedScopeName)%>, null<%=nextConstructorParams%>);\n<% } %>";
      accessHook = "<% if (needsSyncAccessHook) { %>\n       sc.sync.SyncManager.accessSyncInst(<%=instanceVariableName%>, <%=formatString(derivedScopeName)%>);\n<% } %>";
   }

   static SyncAnnotationProcessor defaultProc = new SyncAnnotationProcessor();
   public static SyncAnnotationProcessor getSyncAnnotationProcessor() {
      return defaultProc;
   }
}
