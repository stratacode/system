/*
 * Copyright (c) 2020. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

import sc.lang.DefaultAnnotationProcessor;

public class DBAnnotationProcessor extends DefaultAnnotationProcessor {
   {
      // TODO: do we need to split this template into two pieces - one to define new methods that runs at 'start' and the other
      // to represent the merged view of the DBTypeDescriptor - that should be applied during validate - i.e. the staticMixinTemplate
      defineTypesMixinTemplate = "sc.obj.DBTemplate";
      setAppendInterfaces(new String[] {"sc.db.IDBObject", "sc.obj.IStoppable"});
      // Because this template defines new methods in the interface contract, it needs to be processed and stored in the hiddenBody
      definesNewMembers = true;
   }

   static DBAnnotationProcessor defaultProc = new DBAnnotationProcessor();
   public static DBAnnotationProcessor getDBAnnotationProcessor() {
      return defaultProc;
   }
}
