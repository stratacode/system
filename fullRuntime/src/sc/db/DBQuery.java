/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.db;

import java.util.List;

abstract class DBQuery {
   DBTypeDescriptor dbTypeDesc;
   int queryNumber;
   String queryName;

   /*
   enum QueryResultType {
      SingleValue, SingleObject, MultiValue, MultiObject, None
   }

   List<Object> resultTypes;

   TableDescriptor primaryTable;
   List<TableDescriptor> outerJoinTables;

   // Class/TypeDeclaration or DBPropertyDescriptor
   List<Object> argTypes;
    */

   public abstract DBQuery cloneForSubType(DBTypeDescriptor subType);

   public void activate() {}
}
