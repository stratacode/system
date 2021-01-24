/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.db;

import sc.dyn.DynUtil;
import sc.type.CTypeUtil;

import java.util.List;

public class DBEnumDescriptor extends BaseTypeDescriptor {
   public List<String> enumConstants;

   public String sqlTypeName;

   public DBEnumDescriptor(Object typeDecl, String sqlTypeName, String dataSourceName, List<String> constNames) {
      super(typeDecl, dataSourceName);
      this.sqlTypeName = sqlTypeName;
      enumConstants = constNames;
   }

   public static DBEnumDescriptor getByType(Object propertyType, boolean start) {
      BaseTypeDescriptor base = DBTypeDescriptor.getBaseByType(propertyType, start);
      if (base instanceof DBEnumDescriptor)
         return (DBEnumDescriptor) base;
      return null;
   }

   public StringBuilder getMetadataString() {
      StringBuilder res = new StringBuilder();
      res.append(" enum ");
      res.append("(");
      if (enumConstants != null) {
         for (int i = 0; i < enumConstants.size(); i++) {
            if (i != 0)
               res.append(", ");
            res.append(enumConstants.get(i));
            res.append(" = ");
            res.append(i);
         }
      }
      res.append(")");
      return res;
   }

   public static DBEnumDescriptor create(Object typeDecl, String sqlTypeName, String dataSourceName, List<String> constNames) {
      DBEnumDescriptor dbEnumDesc = new DBEnumDescriptor(typeDecl, sqlTypeName, dataSourceName, constNames);

      BaseTypeDescriptor oldType = typeDescriptorsByType.put(typeDecl, dbEnumDesc);
      String typeName = DynUtil.getTypeName(typeDecl, false);
      BaseTypeDescriptor oldName = typeDescriptorsByName.put(typeName, dbEnumDesc);

      dbEnumDesc.init();

      if ((oldType != null && oldType != dbEnumDesc) | (oldName != null && oldName != dbEnumDesc)) {
         System.err.println("Replacing db enum descriptor for type: " + typeName);
      }
      return dbEnumDesc;
   }
}
