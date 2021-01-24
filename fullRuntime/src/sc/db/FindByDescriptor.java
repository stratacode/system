/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.db;

import sc.dyn.DynUtil;
import sc.util.StringUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * The metadata for a FindBy query - generated from the @FindBy annotation
 */
public class FindByDescriptor extends BaseQueryDescriptor {
   public List<String> propNames;
   public List<String> optionNames;
   public List<String> orderByProps;
   public boolean orderByOption;
   public boolean multiRow;
   public String selectGroup;
   public boolean paged;
   public boolean findOne;

   public List<Object> propTypes;
   public List<Object> optionTypes;

   /**
    * The list of parent properties in relationships in this descriptor that need to have prototype properties
    * initialized - for a.b.c, it would be a and a.b
     */
   public List<String> protoProps;

   public FindByDescriptor(String name, List<String> properties, List<String> options, List<String> orderByProps, boolean orderByOption, boolean multiRow, String selectGroup, boolean paged, boolean findOne) {
      this.queryName = name;
      this.propNames = properties;
      this.optionNames = options;
      this.multiRow = multiRow;
      this.selectGroup = selectGroup;
      this.orderByProps = orderByProps;
      this.orderByOption = orderByOption;
      this.paged = paged;
      this.findOne = findOne;
   }

   public boolean typesInited() {
      return propTypes != null;
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
               DBUtil.error("Missing property type for property: " + propName + " in FindBy query: " + queryName + " for type: " + typeDecl);
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
      return queryName;
   }
}
