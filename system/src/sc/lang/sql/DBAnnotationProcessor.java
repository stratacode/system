/*
 * Copyright (c) 2020. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

import sc.lang.DefaultAnnotationProcessor;

public class DBAnnotationProcessor extends DefaultAnnotationProcessor {
   {
      // This is the name of the template to use for generating 'static init' code for a persistent object
      staticMixinTemplate = "sc.obj.DBTemplate";
      setAppendInterfaces(new String[] {"sc.db.IDBObject", "sc.obj.IStoppable"});
      // Because this template defines new methods in the interface contract, it needs to be processed and stored in the hiddenBody
      definesNewMembers = true;
   }

   static DBAnnotationProcessor defaultProc = new DBAnnotationProcessor();
   public static DBAnnotationProcessor getDBAnnotationProcessor() {
      return defaultProc;
   }
}
