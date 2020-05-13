package sc.db;

import sc.dyn.DynUtil;
import sc.type.CTypeUtil;

import java.util.HashMap;
import java.util.Map;

public abstract class BaseTypeDescriptor {
   static Map<Object,BaseTypeDescriptor> typeDescriptorsByType = new HashMap<Object,BaseTypeDescriptor>();
   static Map<String,BaseTypeDescriptor> typeDescriptorsByName = new HashMap<String,BaseTypeDescriptor>();

   // Class or ITypeDeclaration for dynamic types
   public Object typeDecl;

   // Configured name for the primary data source
   public String dataSourceName;

   /** Reference, populated on the first request to the database connection or configuration */
   public DBDataSource dataSource;

   boolean initialized = false, started = false, activated = false;

   public boolean tablesInitialized = false;

   public BaseTypeDescriptor(Object td, String dataSourceName) {
      this.typeDecl = td;
      this.dataSourceName = dataSourceName;
   }

   public String getTypeName() {
      return DynUtil.getTypeName(typeDecl, false);
   }

   public String getBaseTypeName() {
      return getTypeName();
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      if (typeDecl != null)
         sb.append(CTypeUtil.getClassName(DynUtil.getTypeName(typeDecl, false)));
      return sb.toString();
   }

   public void init() {
      initialized = true;
   }

   public void start() {
      started = true;
   }

   public void activate() {
      activated = true;
   }

   public abstract StringBuilder getMetadataString();
}
