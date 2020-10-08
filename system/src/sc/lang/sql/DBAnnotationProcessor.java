/*
 * Copyright (c) 2020. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

import sc.lang.DefaultAnnotationProcessor;

public class DBAnnotationProcessor extends DefaultAnnotationProcessor {
   {
      // This will cause all of the DBTypeSettings types to be created when the program starts up.
      // We need all of the sub-types initialized so that when we do a query for a base-type, it can create instances
      // of the sub-type that might be returned
      initOnStartup = true;
      // TODO: do we need to split this template into two pieces - one to define new methods that runs at 'start' and the other
      // to represent the merged view of the DBTypeDescriptor - that should be applied during validate - i.e. the staticMixinTemplate
      staticMixinTemplate = "sc.obj.DBStaticTemplate";
      defineTypesMixinTemplate = "sc.obj.DBDefineTypesTemplate";
      // TODO: for now, these classes are compiled only even if they are in a dynamic layer but it wouldn't be difficult to
      // support dynamic DB types too. When adding or removing properties and types, it would be possible to do full-lifecycle schema-evolution
      // on the fly
      compiledOnly = true;
      setAppendInterfaces(new String[] {"sc.db.IDBObject", "sc.obj.IStoppable"});
   }

   static DBAnnotationProcessor defaultProc = new DBAnnotationProcessor();
   public static DBAnnotationProcessor getDBAnnotationProcessor() {
      return defaultProc;
   }
}
