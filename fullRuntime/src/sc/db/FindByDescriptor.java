package sc.db;

import sc.dyn.DynUtil;
import sc.type.IBeanMapper;
import sc.util.StringUtil;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The metadata for a FindBy query - generated from the @FindBy annotation
 */
public class FindByDescriptor {
   public String name;
   public List<String> propNames;
   public List<String> optionNames;
   public List<String> orderByProps;
   public boolean orderByOption;
   public boolean multiRow;
   public String fetchGroup;
   public boolean paged;

   public List<Object> propTypes;
   public List<Object> optionTypes;

   /**
    * The list of parent properties in relationships in this descriptor that need to have prototype properties
    * initialized - for a.b.c, it would be a and a.b
     */
   public List<String> protoProps;

   public FindByDescriptor(String name, List<String> properties, List<String> options, List<String> orderByProps, boolean orderByOption, boolean multiRow, String fetchGroup, boolean paged) {
      this.name = name;
      this.propNames = properties;
      this.optionNames = options;
      this.multiRow = multiRow;
      this.fetchGroup = fetchGroup;
      this.orderByProps = orderByProps;
      this.orderByOption = orderByOption;
      this.paged = paged;
   }

   public void initTypes(Object typeDecl) {
      if (propTypes == null) {
         propTypes = new ArrayList<Object>(propNames.size());
         initPropTypeList(typeDecl, propNames, propTypes);
         if (optionNames != null) {
            optionTypes = new ArrayList<Object>(optionNames.size());
            initPropTypeList(typeDecl, optionNames, optionTypes);
         }
      }
   }

   private void initPropTypeList(Object typeDecl, List<String> inPropNames, List<Object> resPropTypes) {
      for (String propPathName:inPropNames) {
         String[] propPathArr = StringUtil.split(propPathName,'.');
         Object curType = typeDecl;
         String curPrefix = null;
         int pathLen = propPathArr.length;
         for (int p = 0; p < pathLen; p++) {
            String propName = propPathArr[p];
            Object propType = DynUtil.getPropertyType(curType, propName);
            if (propType == null) {
               DBUtil.error("Missing property type for property: " + propName + " in FindBy query: " + name + " for type: " + typeDecl);
               break;
            }
            else
               curType = propType;

            // Skip leaf properties - here we only need the parents
            if (pathLen > 1 && p != pathLen - 1) {
               if (p == 0) {
                  curPrefix = propName;
               }
               else {
                  curPrefix = curPrefix + '.' + propName;
               }
               if (protoProps == null) {
                  protoProps = new ArrayList<String>();
               }
               if (!protoProps.contains(curPrefix))
                  protoProps.add(curPrefix);
            }
         }
         resPropTypes.add(curType);
      }
   }

   public String toString() {
      return name;
   }
}
