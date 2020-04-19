package sc.db;

import java.util.List;

abstract class DBQuery implements Cloneable {
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

   public DBQuery clone() {
      try {
         Object res = super.clone();
         return (DBQuery) res;
      }
      catch (CloneNotSupportedException exc) {
         System.err.println("*** Failed to clone query: " + exc);
      }
      return null;
   }

   public void activate() {}
}
