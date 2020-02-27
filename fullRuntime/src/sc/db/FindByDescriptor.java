package sc.db;

import sc.dyn.DynUtil;
import sc.type.IBeanMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The metadata for a FindBy query - generated from the FindByProp annotation
 */
public class FindByDescriptor {
   public String name;
   public List<String> propNames;
   public List<String> optionNames;
   public boolean multiRow;
   public String fetchGroup;

   public List<Object> propTypes;
   public List<Object> optionTypes;

   public FindByDescriptor(String name, List<String> properties, List<String> options, boolean multiRow, String fetchGroup) {
      this.name = name;
      this.propNames = properties;
      this.optionNames = options;
      this.multiRow = multiRow;
      this.fetchGroup = fetchGroup;
   }

   public void initTypes(Object typeDecl) {
      if (propTypes == null) {
         propTypes = new ArrayList<Object>(propNames.size());
         for (String propName:propNames) {
            Object propType = DynUtil.getPropertyType(typeDecl, propName);
            propTypes.add(propType);
         }
         if (optionNames != null) {
            optionTypes = new ArrayList<Object>(optionNames.size());
            for (String optName:optionNames) {
               Object propType = DynUtil.getPropertyType(typeDecl, optName);
               optionTypes.add(propType);
            }
         }
      }
   }


}
