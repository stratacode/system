package sc.lang.sql;

import sc.db.*;
import sc.lang.SQLLanguage;
import sc.lang.java.*;
import sc.lang.sc.PropertyAssignment;
import sc.lang.template.Template;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.parser.IParseNode;
import sc.parser.ParseError;
import sc.parser.ParseUtil;
import sc.type.CTypeUtil;
import sc.util.StringUtil;

import java.util.*;

/**
 * Contains the code processing features for DB. One instance is created for a given implementation type - e.g. postgresql.
 *  Also has static methods for the DB domain model classes - schemas, type descriptor, property descriptor.
 */
public class DBProvider {
   public String providerName;
   public Layer definedInLayer;

   private ISchemaUpdater schemaUpdater;

   public DBProvider(String providerName) {
      this.providerName = providerName;
   }

   /**
    * Returns the DBPropertyDescriptor for a given property. Pass in includeSubTypeProps=true to return a property
    * descriptor used as a DB property in a sub-type but not in this type. We do need to force these properties
    * to have convertGetSet and binding events but do not define a type descriptor so don't do db specific convert
    * get/set code.  Instead, the sub-type will add them as getX/setX methods wrapping the base-types methods.
    */
   public static DBPropertyDescriptor getDBPropertyDescriptor(LayeredSystem sys, Layer refLayer, Object propObj,
                                                              boolean includeSubTypeProps) {
      if (includeSubTypeProps) {
         if (propObj instanceof PropertyAssignment) {
            propObj = ((PropertyAssignment) propObj).getAssignedProperty();
         }
         if (propObj instanceof VariableDefinition) {
            VariableDefinition varDef = (VariableDefinition) propObj;
            if (varDef.dbPropDesc != null)
               return varDef.dbPropDesc;
         }
      }
      Object enclType = ModelUtil.getEnclosingType(propObj);
      if (enclType != null) {
         BaseTypeDescriptor typeDesc = getDBTypeDescriptor(sys, refLayer, enclType, true);
         String propName = ModelUtil.getPropertyName(propObj);
         if (typeDesc instanceof DBTypeDescriptor) {
            DBTypeDescriptor dbTypeDesc = (DBTypeDescriptor) typeDesc;
            dbTypeDesc.init();
            dbTypeDesc.resolve(); // Must be resolved so we know about all 'reverse property' references at this point to determine whether or not this property is bindable
            return dbTypeDesc.getPropertyDescriptor(propName);
         }
      }
      return null;
   }

   public static DBPropertyDescriptor getDBPropertyDescriptor(LayeredSystem sys, Layer refLayer, Object typeDecl, String propName) {
      BaseTypeDescriptor typeDesc = getDBTypeDescriptor(sys, refLayer, typeDecl, true);
      if (typeDesc instanceof DBTypeDescriptor) {
         DBTypeDescriptor dbTypeDesc = (DBTypeDescriptor) typeDesc;
         dbTypeDesc.init();
         dbTypeDesc.resolve(); // Must be resolved so we know about all 'reverse property' references at this point to determine whether or not this property is bindable
         return dbTypeDesc.getPropertyDescriptor(propName);
      }
      return null;
   }

   public static DBProvider getDBProviderForDataSource(LayeredSystem sys, Layer refLayer, String dataSourceName) {
      DBDataSource dataSource = sys.getDataSource(dataSourceName, refLayer.activated);
      if (dataSource != null) {
         return sys.getDBProvider(dataSource.provider, refLayer);
      }
      return null;
   }

   public static DBProvider getDBProviderForType(LayeredSystem sys, Layer refLayer, Object typeObj) {
      BaseTypeDescriptor typeDesc = getDBTypeDescriptor(sys, ModelUtil.getLayerForType(sys, typeObj), typeObj, false);
      if (typeDesc != null) {
         return getDBProviderForDataSource(sys, refLayer, typeDesc.dataSourceName);
      }
      return null;
   }

   public static DBProvider getDBProviderForPropertyDesc(LayeredSystem sys, Layer refLayer, DBPropertyDescriptor propDesc) {
      String dataSourceName = propDesc.getDataSourceForProp();
      DBDataSource dataSource = sys.getDataSource(dataSourceName, refLayer.activated);
      if (dataSource != null) {
         return sys.getDBProvider(dataSource.provider, refLayer);
      }
      return null;
   }

   public static DBProvider getDBProviderForProperty(LayeredSystem sys, Layer refLayer, Object propObj) {
      /*
      if (propObj instanceof PropertyAssignment) {
         propObj = ((PropertyAssignment) propObj).getAssignedProperty();
      }
      if (propObj instanceof VariableDefinition) {
         VariableDefinition varDef = (VariableDefinition) propObj;
         if (varDef.dbPropDesc != null) {
            return getDBProviderForPropertyDesc(sys, refLayer, varDef.dbPropDesc);
         }
      }
      */
      Object enclType = ModelUtil.getEnclosingType(propObj);
      String propName = ModelUtil.getPropertyName(propObj);
      BaseTypeDescriptor typeDesc = enclType == null ? null : getDBTypeDescriptor(sys, ModelUtil.getLayerForMember(sys, propObj), enclType, true);
      if (typeDesc instanceof DBTypeDescriptor) {
         DBPropertyDescriptor propDesc = ((DBTypeDescriptor) typeDesc).getPropertyDescriptor(propName);
         if (propDesc != null) {
            String dataSourceName = propDesc.getDataSourceForProp();
            DBDataSource dataSource = sys.getDataSource(dataSourceName, refLayer.activated);
            if (dataSource != null) {
               return sys.getDBProvider(dataSource.provider, refLayer);
            }
         }
      }
      return null;
   }

   public static BaseTypeDescriptor getDBTypeDescriptor(LayeredSystem sys, Layer refLayer, Object typeDecl, boolean initTables) {
      if (typeDecl instanceof ITypeDeclaration) {
         BaseTypeDescriptor res = ((ITypeDeclaration) typeDecl).getDBTypeDescriptor();
         if (res != null && initTables && !res.tablesInitialized)
            completeDBTypeDescriptor(res, sys, refLayer, typeDecl);
         return res;
      }
      else {
         BaseTypeDescriptor res = initDBTypeDescriptor(sys, refLayer, typeDecl);
         if (res != null && initTables && !res.tablesInitialized)
            completeDBTypeDescriptor(res, sys, refLayer, typeDecl);
         return res;
      }
   }

   /**
    * Defines the part of the DBTypeDescriptor that includes the primaryTable and idColumns - but does not try to resolve
    * other DBTypeDescriptors for references. This part only can be used to define new interface methods in the DBDefinesTypeTemplate
    */
   public static BaseTypeDescriptor initDBTypeDescriptor(LayeredSystem sys, Layer refLayer, Object typeDecl) {
      // These end up inheriting the DBTypeSettings annotation of the parent and we don't need them as separate types
      if (typeDecl instanceof EnumConstant)
         return null;

      ArrayList<Object> typeSettings = ModelUtil.getAllInheritedAnnotations(sys, typeDecl, "sc.db.DBTypeSettings", false, refLayer, false);
      // TODO: should check for annotations on the Layer of all types in the type tree. For each attribute, check the layer annotation if it's not set at the type level
      // TODO: need getAllLayerAnnotations(typeDecl, annotName)
      if (typeSettings != null) {
         boolean persist = true;
         String dataSourceName = null;
         String primaryTableName = null;
         String schemaSQL = null;
         SQLFileModel schemaSQLModel = null;

         boolean storeInExtendsTable = true;

         List<BaseQueryDescriptor> queries = null;
         int typeId = ModelUtil.hasModifier(typeDecl, "abstract") ? DBTypeDescriptor.DBAbstractTypeId : DBTypeDescriptor.DBUnsetTypeId;

         Boolean tmpPersist = null;
         String tmpDataSourceName = null, tmpPrimaryTableName = null;
         Integer tmpTypeId = null;

         for (Object annot:typeSettings) {
            if (tmpPersist == null) {
               tmpPersist  = (Boolean) ModelUtil.getAnnotationValue(annot, "persist");
               if (tmpPersist != null)
                  persist = tmpPersist;
            }
            if (tmpDataSourceName == null) {
               tmpDataSourceName  = (String) ModelUtil.getAnnotationValue(annot, "dataSourceName");
               if (tmpDataSourceName != null)
                  dataSourceName = tmpDataSourceName;
            }
            if (tmpPrimaryTableName == null) {
               tmpPrimaryTableName = (String) ModelUtil.getAnnotationValue(annot, "tableName");
               if (tmpPrimaryTableName != null) {
                  primaryTableName = tmpPrimaryTableName;
               }
            }
            if (tmpTypeId == null) {
               tmpTypeId  = (Integer) ModelUtil.getAnnotationValue(annot, "typeId");
               if (tmpTypeId != null)
                  typeId = tmpTypeId;
            }
         }

         // Look for this annotation only on this specific class - don't want inherit the sub-types value by putting in the loop above.
         Boolean tmpStoreInExtendsTable = (Boolean) ModelUtil.getAnnotationValue(typeDecl, "sc.db.DBTypeSettings", "storeInExtendsTable");
         if (tmpStoreInExtendsTable != null)
            storeInExtendsTable = tmpStoreInExtendsTable;

         String tmpSchemaSQL = (String) ModelUtil.getAnnotationValue(typeDecl, "sc.db.SchemaSQL", "value");
         if (tmpSchemaSQL != null) {
            schemaSQL = tmpSchemaSQL;

            if (schemaSQL.length() > 0) {
               Object parseRes = SQLLanguage.getSQLLanguage().parseString(schemaSQL);
               if (parseRes instanceof ParseError) {
                  System.err.println("*** SchemaSQL - parse error: " + parseRes);
               }
               else {
                  schemaSQLModel = (SQLFileModel) ParseUtil.nodeToSemanticValue(parseRes);
                  if (schemaSQLModel.sqlCommands != null) {
                     for (SQLCommand schemaCmd: schemaSQLModel.sqlCommands) {
                        if (schemaCmd instanceof CreateFunction) {
                           NamedQueryDescriptor namedQuery = ((CreateFunction) schemaCmd).convertToNamedQuery(sys);
                           if (queries == null)
                              queries = new ArrayList<BaseQueryDescriptor>();
                           queries.add(namedQuery);
                        }
                     }
                  }
               }
            }
         }

         if (persist) {
            if (dataSourceName == null) {
               DBDataSource def = refLayer.getDefaultDataSource();
               dataSourceName = def == null ? null : def.jndiName;
               if (dataSourceName == null)
                  return null;
            }
            String fullTypeName = ModelUtil.getTypeName(typeDecl);
            String typeName = CTypeUtil.getClassName(fullTypeName);
            if (primaryTableName == null)
               primaryTableName = SQLUtil.getSQLName(typeName);

            boolean isEnum = ModelUtil.isEnumType(typeDecl);
            if (isEnum) {
               List<Object> enumConstants;
               if (typeDecl instanceof TypeDeclaration) {
                  enumConstants = ((TypeDeclaration) typeDecl).getEnumConstants();
               }
               else {
                  Object[] ecArray = ModelUtil.getEnumConstants(typeDecl);
                  enumConstants = ecArray == null ? null : Arrays.asList(ecArray);
               }
               ArrayList<String> enumConstNames = new ArrayList<String>();
               if (enumConstants != null) {
                  for (Object ec:enumConstants) {
                     enumConstNames.add(ModelUtil.getEnumConstantName(ec));
                  }
               }
               DBEnumDescriptor enumDesc = new DBEnumDescriptor(typeDecl, primaryTableName, dataSourceName, enumConstNames);
               sys.addDBTypeDescriptor(fullTypeName, enumDesc);
               return enumDesc;
            }

            Object baseType = ModelUtil.getExtendsClass(typeDecl);
            BaseTypeDescriptor baseTypeDesc = baseType != null && baseType != Object.class ? getDBTypeDescriptor(sys, refLayer, baseType, false) : null;
            DBTypeDescriptor baseTD = baseTypeDesc instanceof DBTypeDescriptor ? (DBTypeDescriptor) baseTypeDesc : null;

            // Ignore the baseTD and just build up a new DBTypeDescriptor from our properties as though
            // this is a new type
            if (baseTD != null && !storeInExtendsTable)
               baseTD = null;

            // For ModifyDeclaration don't use the cached type descriptor since it might need to be refined
            DBTypeDescriptor dbTypeDesc = null;
            if (!(typeDecl instanceof BodyTypeDeclaration)) {
               dbTypeDesc = sys.getDBTypeDescriptor(fullTypeName);
               if (dbTypeDesc != null)
                  return dbTypeDesc;
            }


            // We reuse the primary table here - by default adding subclass properties into the same table unless they set their own table.
            TableDescriptor primaryTable = baseTD == null ? new TableDescriptor(primaryTableName) : baseTD.primaryTable;

            Object[] properties = ModelUtil.getDeclaredProperties(typeDecl, null, true, true, false);
            if (properties != null) {
               for (Object property:properties) {
                  String propName = ModelUtil.getPropertyName(property);
                  Object propType = ModelUtil.getPropertyType(property);

                  if (propName == null || propType == null)
                     continue;

                  if (ModelUtil.hasModifier(property, "static"))
                     continue;

                  Object findByProp = ModelUtil.getAnnotation(property, "sc.db.FindBy");
                  if (findByProp != null) {
                     ArrayList<String> fbProps = new ArrayList<String>();
                     fbProps.add(propName);
                     boolean multiRowQuery = initFindByPropertyList(sys, typeDecl, fbProps, "with", findByProp, propName, properties, typeName);

                     String selectGroup = (String) ModelUtil.getAnnotationValue(findByProp, "selectGroup");
                     Boolean paged = (Boolean) ModelUtil.getAnnotationValue(findByProp, "paged");
                     Boolean findOne = (Boolean) ModelUtil.getAnnotationValue(findByProp, "findOne");

                     String orderByStr = (String) ModelUtil.getAnnotationValue(findByProp, "orderBy");

                     List<String> orderByProps = null;
                     boolean orderByOption = false;
                     if (orderByStr != null && orderByStr.length() > 0) {
                        if (orderByStr.equals("?"))
                           orderByOption = true;
                        else {
                           orderByProps = Arrays.asList(StringUtil.split(orderByStr, ","));
                        }
                     }

                     ArrayList<String> fbOptions = new ArrayList<String>();
                     // Even if an options property is unique, we don't consider that a single value query because that
                     // property might not be in the query and so we might need to return more than one result.
                     initFindByPropertyList(sys, typeDecl, fbOptions, "options", findByProp, propName, properties, typeName);
                     if (fbOptions.size() == 0)
                        fbOptions = null;

                     List<Object> propSettings = ModelUtil.getAllInheritedAnnotations(sys, property, "sc.db.DBPropertySettings", false, refLayer, false);
                     if (propSettings != null) {
                        Boolean tmpUnique = null;
                        String tmpSelectGroup = null;
                        for (Object propSetting:propSettings) {
                           if (tmpUnique == null) {
                              tmpUnique  = (Boolean) ModelUtil.getAnnotationValue(propSetting, "unique");
                              if (tmpUnique != null && tmpUnique) {
                                 multiRowQuery = false;
                              }
                           }
                           if (selectGroup == null) {
                              if (tmpSelectGroup == null) {
                                 tmpSelectGroup = (String) ModelUtil.getAnnotationValue(propSetting, "selectGroup");
                                 if (tmpSelectGroup != null) {
                                    selectGroup = tmpSelectGroup;
                                 }
                              }
                           }
                        }
                     }

                     FindByDescriptor fbDesc = new FindByDescriptor(propName, fbProps, fbOptions, orderByProps, orderByOption, multiRowQuery, selectGroup, paged != null && paged, findOne != null && findOne);
                     if (queries == null)
                        queries = new ArrayList<BaseQueryDescriptor>();
                     queries.add(fbDesc);

                     initFindByParamTypes(sys, typeDecl, fbDesc, false);
                  }

                  Object idSettings = ModelUtil.getAnnotation(property, "sc.db.IdSettings");
                  if (idSettings != null) {
                     String idColumnName = null;
                     String idColumnType = null;
                     boolean definedByDB = true;

                     // If we modify another type, it might have the generated id which we ignore here because we are replacing it anyway
                     Boolean generated = (Boolean) ModelUtil.getAnnotationValue(idSettings, "generated");
                     if (generated != null && generated) {
                        Object propEnclType = ModelUtil.getEnclosingType(property);
                        if (ModelUtil.sameTypes(propEnclType, typeDecl))
                           continue;
                     }

                     String tmpColumnName = (String) ModelUtil.getAnnotationValue(idSettings, "columnName");
                     if (tmpColumnName != null) {
                        idColumnName = tmpColumnName;
                     }

                     String tmpColumnType = (String) ModelUtil.getAnnotationValue(idSettings, "columnType");
                     if (tmpColumnType != null) {
                        idColumnType = tmpColumnType;
                     }

                     Boolean tmpDefinedByDB  = (Boolean) ModelUtil.getAnnotationValue(idSettings, "definedByDB");
                     if (tmpDefinedByDB != null) {
                        definedByDB = tmpDefinedByDB;
                     }

                     if (idColumnName == null)
                        idColumnName = SQLUtil.getSQLName(propName);
                     if (idColumnType == null) {
                        idColumnType = DBUtil.getDefaultSQLType(propType, definedByDB);
                        if (idColumnType == null)
                           throw new IllegalArgumentException("Invalid property type: " + propType + " for id: " + idColumnName);
                     }

                     if (baseTD != null) {
                        boolean found = false;
                        for (IdPropertyDescriptor baseIdProp:baseTD.getIdProperties()) {
                           if (baseIdProp.propertyName.equals(propName)) {
                              found = true;
                              break;
                           }
                        }
                        if (!found)
                           DBUtil.error("IdSettings set on property: " + propName + " in class: " + typeName + " conflicts with id properties in base class: " + baseTD);
                     }
                     else {
                        IdPropertyDescriptor idDesc = new IdPropertyDescriptor(propName, idColumnName, idColumnType, definedByDB);
                        primaryTable.addIdColumnProperty(idDesc);
                        idDesc.propertyType = propType;
                     }
                  }
               }
            }

            List<Object> findByClList = ModelUtil.getRepeatingAnnotation(typeDecl, "sc.db.FindBy");
            if (findByClList != null) {
               for (Object findByCl:findByClList) {
                  String name = (String) ModelUtil.getAnnotationValue(findByCl, "name");
                  if (name == null) {
                     sys.error("@FindBy annotation on class: " + typeDecl + " missing name for findBy method");
                     continue;
                  }
                  ArrayList<String> fbProps = new ArrayList<String>();
                  boolean multiRowQuery = initFindByPropertyList(sys, typeDecl, fbProps, "with", findByCl, null, properties, typeName);

                  ArrayList<String> fbOptions = new ArrayList<String>();
                  // Even if an options property is unique, we don't consider that a single value query because that
                  // property might not be in the query and so we might need to return more than one result.
                  initFindByPropertyList(sys, typeDecl, fbOptions, "options", findByCl, null, properties, typeName);
                  if (fbOptions.size() == 0)
                     fbOptions = null;

                  String selectGroup = (String) ModelUtil.getAnnotationValue(findByCl, "selectGroup");
                  Boolean paged = (Boolean) ModelUtil.getAnnotationValue(findByCl, "paged");
                  Boolean findOne = (Boolean) ModelUtil.getAnnotationValue(findByCl, "findOne");
                  String orderByStr = (String) ModelUtil.getAnnotationValue(findByCl, "orderBy");

                  List<String> orderByProps = null;
                  boolean orderByOption = false;
                  if (orderByStr != null && orderByStr.length() > 0) {
                     if (orderByStr.equals("?"))
                        orderByOption = true;
                     else {
                        orderByProps = Arrays.asList(StringUtil.split(orderByStr, ","));
                     }
                  }

                  FindByDescriptor fbDesc = new FindByDescriptor(name, fbProps, fbOptions, orderByProps, orderByOption, multiRowQuery, selectGroup, paged != null && paged, findOne != null && findOne);
                  if (queries == null)
                     queries = new ArrayList<BaseQueryDescriptor>();
                  queries.add(fbDesc);

                  initFindByParamTypes(sys, typeDecl, fbDesc, false);
               }
            }

            dbTypeDesc = new DBTypeDescriptor(typeDecl, baseTD, typeId, dataSourceName, primaryTable, queries, schemaSQL);

            sys.addDBTypeDescriptor(fullTypeName, dbTypeDesc);

            return dbTypeDesc;
         }
      }
      return null;
   }

   static boolean initFindByPropertyList(LayeredSystem sys, Object typeDecl, ArrayList<String> fbProps, String attName, Object findByAnnot, String propName, Object[] properties, String typeName) {
      boolean multiRowQuery = true;
      String withPropsStr = (String) ModelUtil.getAnnotationValue(findByAnnot, attName);
      if (withPropsStr != null && withPropsStr.length() > 0) {
         String[] withPropNames = StringUtil.split(withPropsStr, ',');
         for (String withPropName:withPropNames) {
            Object withProp = findPropertyInList(sys, typeDecl, properties, withPropName);
            if (withProp == null) {
               sys.error("No '" + attName + "' property: " + withPropName + " for @FindBy " +
                       (propName == null ? "for class: " + typeName : "for property: " + typeName + "." + propName));
            }
            else {
               if (isUniqueProperty(withProp))
                  multiRowQuery = false;
               fbProps.add(withPropName);
            }
         }
      }
      return multiRowQuery;

   }

   static boolean isUniqueProperty(Object property) {
      Object propSettings = ModelUtil.getAnnotation(property, "sc.db.DBPropertySettings");
      if (propSettings != null) {
         Boolean tmpUnique  = (Boolean) ModelUtil.getAnnotationValue(propSettings, "unique");
         if (tmpUnique != null && tmpUnique) {
            return true;
         }
      }
      return false;
   }

   static Object findPropertyInList(LayeredSystem sys, Object startType, Object[] properties, String propNamePath) {
      String[] propNameArr = StringUtil.split(propNamePath, '.');
      int ix = 0;
      Object curProp = null;
      Object curType = startType;
      int pathLen = propNameArr.length;
      while (ix < pathLen) {
         String propName = propNameArr[ix++];
         if (ix == 1) {
            for (Object prop:properties) {
               if (ModelUtil.getPropertyName(prop).equals(propName)) {
                  curProp = prop;
                  Object propType = ModelUtil.getPropertyType(prop);
                  if (propType == null)
                     return ix < pathLen ? null : prop;
                  curType = propType;
                  break;
               }
            }
         }
         else {
            curProp = ModelUtil.definesMember(curType, propName, JavaSemanticNode.MemberType.PropertyAnySet, null, null, sys);
         }
      }
      return curProp;
   }

   public static void initQueryParamTypes(LayeredSystem sys, Layer refLayer, Object typeDecl, BaseQueryDescriptor query, boolean resolve) {
      if (query instanceof NamedQueryDescriptor)
         initNamedQueryParamTypes(sys, refLayer, typeDecl, (NamedQueryDescriptor) query, resolve);
      else
         initFindByParamTypes(sys, typeDecl, (FindByDescriptor) query, resolve);;
   }

   public static void initNamedQueryParamTypes(LayeredSystem sys, Layer refLayer, Object typeDecl, NamedQueryDescriptor namedQuery, boolean resolve) {
      int sz = namedQuery.paramDBTypeNames.size();
      namedQuery.paramTypes = new ArrayList<Object>(sz);
      namedQuery.paramTypeNames = new ArrayList<String>(sz);
      for (int i = 0; i < sz; i++) {
         String paramDBTypeName = namedQuery.paramDBTypeNames.get(i);
         String paramTypeName = SQLUtil.convertSQLToJavaTypeName(sys, paramDBTypeName);
         namedQuery.paramTypeNames.add(paramTypeName);
         Object paramType = resolveType(sys, refLayer, typeDecl, paramTypeName);
         namedQuery.paramTypes.add(paramType);
      }
      String returnDBTypeName = namedQuery.returnDBTypeName;
      if (returnDBTypeName != null) {
         namedQuery.returnTypeName = SQLUtil.convertSQLToJavaTypeName(sys, returnDBTypeName);
         namedQuery.returnType = resolveType(sys, refLayer, typeDecl, namedQuery.returnTypeName);
      }
   }

   private static Object resolveType(LayeredSystem sys, Layer refLayer, Object typeDecl, String toResolveTypeName) {
      return typeDecl instanceof ITypeDeclaration ? ((ITypeDeclaration) typeDecl).findTypeDeclaration(toResolveTypeName, true) :
              sys.getTypeDeclaration(toResolveTypeName, false, refLayer, false);
   }

   public static void initFindByParamTypes(LayeredSystem sys, Object typeDecl, FindByDescriptor fbDesc, boolean resolve) {
      fbDesc.propTypes = new ArrayList<Object>(fbDesc.propNames.size());
      initFindByPropListTypes(sys, typeDecl, fbDesc, fbDesc.propNames, fbDesc.propTypes, resolve);
      if (fbDesc.optionNames != null) {
         fbDesc.optionTypes = new ArrayList<Object>(fbDesc.optionNames.size());
         initFindByPropListTypes(sys, typeDecl, fbDesc, fbDesc.optionNames, fbDesc.optionTypes, resolve);
      }
   }

   static void initFindByPropListTypes(LayeredSystem sys, Object typeDecl, FindByDescriptor fbDesc, List<String> propNameList, List<Object> resTypesList, boolean resolve) {
      for (String propNamePath:propNameList) {
         String[] propNameArr = StringUtil.split(propNamePath, '.');
         int pathLen = propNameArr.length;
         Object curType = typeDecl;
         String curPrefix = null;
         if (!resolve && pathLen > 1) {
            curType = Object.class;
         }
         else {
            for (int p = 0; p < pathLen; p++) {
               String propName = propNameArr[p];
               Object propMember = ModelUtil.definesMember(curType, propName, JavaSemanticNode.MemberType.PropertyAnySet, curType, null, sys);
               if (propMember == null) {
                  if (p == pathLen - 1)
                     sys.error("FindByProp - no property named: " + propName);
                  else
                     sys.error("FindByProp - no property named: " + propName + " in type: " + curType + " for: " + propNamePath + " on type: " + typeDecl);
                  return;
               }
               else {
                  Object propType = ModelUtil.getPropertyType(propMember);
                  if (propType == null) {
                     sys.error("Missing type for property: " + propName);
                     return;
                  }
                  curType = propType;
               }

               if (pathLen > 1 && p != pathLen - 1) {
                  if (p == 0) {
                     curPrefix = propName;
                  }
                  else {
                     curPrefix = curPrefix + '.' + propName;
                  }
                  if (fbDesc.protoProps == null) {
                     fbDesc.protoProps = new ArrayList<String>();
                  }
                  if (!fbDesc.protoProps.contains(curPrefix))
                     fbDesc.protoProps.add(curPrefix);
               }
            }
         }
         if (curType != null)
            resTypesList.add(curType);
      }
   }

   static class DBRegisteredProperty {
      DBPropertyDescriptor propDesc;
      Object property;
      DBRegisteredProperty(DBPropertyDescriptor pd, Object p) {
         this.propDesc = pd;
         this.property = p;
      }
   }

   public static void completeDBTypeDescriptor(BaseTypeDescriptor baseTypeDesc, LayeredSystem sys, Layer refLayer, Object typeDecl) {
      if (baseTypeDesc == null)
         return;

      ArrayList<Object> typeSettings = ModelUtil.getAllInheritedAnnotations(sys, typeDecl, "sc.db.DBTypeSettings", false, refLayer, false);
      baseTypeDesc.tablesInitialized = true;

      if (baseTypeDesc instanceof DBEnumDescriptor)
         return;

      DBTypeDescriptor dbTypeDesc = (DBTypeDescriptor) baseTypeDesc;
      DBTypeDescriptor baseTD = dbTypeDesc.baseType;

      String versionProp = null;
      ArrayList<String> auxTableNames = null;

      boolean inheritProperties = baseTD == null;

      TableDescriptor primaryTable = dbTypeDesc.primaryTable;

      boolean defaultDynColumn = false;

      String tmpVersionProp = null, tmpAuxTableNames = null;
      Boolean tmpInheritProperties = null, tmpDefaultDynColumn = null;

      if (typeSettings != null) {
         for (Object annot:typeSettings) {
            if (tmpVersionProp == null) {
               tmpVersionProp = (String) ModelUtil.getAnnotationValue(annot, "versionProp");
               if (tmpVersionProp != null) {
                  versionProp = tmpVersionProp;
               }
            }
            if (tmpAuxTableNames == null) {
               tmpAuxTableNames = (String) ModelUtil.getAnnotationValue(annot, "auxTables");
               if (tmpAuxTableNames != null) {
                  auxTableNames = new ArrayList<String>(Arrays.asList(StringUtil.split(tmpAuxTableNames, ',')));
               }
            }
            if (tmpInheritProperties == null) {
               tmpInheritProperties = (Boolean) ModelUtil.getAnnotationValue(annot, "inheritProperties");
               if (tmpInheritProperties != null)
                  inheritProperties = tmpInheritProperties;
            }
            if (tmpDefaultDynColumn == null) {
               tmpDefaultDynColumn = (Boolean) ModelUtil.getAnnotationValue(annot, "defaultDynColumn");
               if (tmpDefaultDynColumn != null) {
                  defaultDynColumn = tmpDefaultDynColumn;
               }
            }
         }

         ArrayList<TableDescriptor> auxTables = new ArrayList<TableDescriptor>();
         Map<String,TableDescriptor> auxTablesIndex = new TreeMap<String,TableDescriptor>();
         ArrayList<TableDescriptor> multiTables = new ArrayList<TableDescriptor>();
         if (auxTableNames != null) {
            for (String auxTableName:auxTableNames)
               auxTables.add(new TableDescriptor(auxTableName));
         }

         Object[] properties = !inheritProperties ?
                 ModelUtil.getDeclaredProperties(typeDecl, null, true, true, false) :
                 ModelUtil.getProperties(typeDecl, null, true);

         ArrayList<DBRegisteredProperty> newProps = new ArrayList<DBRegisteredProperty>();

         if (properties != null) {
            for (Object property:properties) {
               Object idSettings = ModelUtil.getAnnotation(property, "sc.db.IdSettings");
               String propName = ModelUtil.getPropertyName(property);
               Object propType = ModelUtil.getPropertyType(property);

               if (propName == null || propType == null)
                  continue;

               if (idSettings != null) { // handled above
                  continue;
               }
               // Skip transient fields
               if (ModelUtil.hasModifier(property, "transient"))
                  continue;

               if (ModelUtil.hasModifier(property, "static"))
                  continue;

               if (!ModelUtil.isWritableProperty(property))
                  continue;

               List<Object> propSettings = ModelUtil.getAllInheritedAnnotations(sys, property, "sc.db.DBPropertySettings", false, refLayer, false);

               String propTableName = null;
               String propColumnName = null;
               String propColumnType = null;
               Boolean setPropOnDemand = null;
               boolean propRequired = false;
               boolean propUnique = false;
               boolean propIndexed = false;
               boolean propDynColumn = defaultDynColumn;
               String propDataSourceName = null;
               String propSelectGroup = null;
               String propReverseProperty = null;
               String propDBDefault = null;
               boolean explicitPersist = false;
               Boolean tmpPersist = null;
               String tmpTableName = null;
               String tmpColumnName = null;
               String tmpColumnType = null;
               Boolean tmpOnDemand  = null;
               Boolean tmpRequired  = null;
               Boolean tmpUnique  = null;
               Boolean tmpIndexed = null;
               String tmpDataSourceName = null;
               String tmpSelectGroup = null;
               String tmpReverseProperty = null;
               String tmpDBDefault = null;
               Boolean tmpDynColumn = null;
               if (propSettings != null) {
                  boolean persistProperty = true;

                  // Might have inherited multiple annotations for this property - we want to pick the first one we
                  // find in this list since it's sorted with this most specific annotation first.
                  for (Object propSetting:propSettings) {
                     if (tmpPersist == null) {
                        tmpPersist  = (Boolean) ModelUtil.getAnnotationValue(propSetting, "persist");
                        if (tmpPersist != null) {
                           if (!tmpPersist) {
                              persistProperty = false;
                           }
                           else
                              explicitPersist = true;
                        }
                     }

                     if (tmpTableName == null) {
                        tmpTableName = (String) ModelUtil.getAnnotationValue(propSetting, "tableName");
                        if (tmpTableName != null) {
                           propTableName = tmpTableName;
                        }
                     }

                     // TODO: should we specify columnNames and Types here as comma separated lists to deal with multi-column primary key properties (and possibly others that need more than one column?)
                     if (tmpColumnName == null) {
                        tmpColumnName = (String) ModelUtil.getAnnotationValue(propSetting, "columnName");
                        if (tmpColumnName != null) {
                           propColumnName = tmpColumnName;
                        }
                     }

                     if (tmpColumnType == null) {
                        tmpColumnType = (String) ModelUtil.getAnnotationValue(propSetting, "columnType");
                        if (tmpColumnType != null) {
                           propColumnType = tmpColumnType;
                        }
                     }

                     if (tmpOnDemand == null) {
                        tmpOnDemand  = (Boolean) ModelUtil.getAnnotationValue(propSetting, "onDemand");
                        if (tmpOnDemand != null) {
                           setPropOnDemand = tmpOnDemand;
                        }
                     }
                     if (tmpRequired == null) {
                        tmpRequired  = (Boolean) ModelUtil.getAnnotationValue(propSetting, "required");
                        if (tmpRequired != null) {
                           propRequired = tmpRequired;
                        }
                     }
                     if (tmpUnique == null) {
                        tmpUnique  = (Boolean) ModelUtil.getAnnotationValue(propSetting, "unique");
                        if (tmpUnique != null) {
                           propUnique = tmpUnique;
                        }
                     }
                     if (tmpIndexed == null) {
                        tmpIndexed  = (Boolean) ModelUtil.getAnnotationValue(propSetting, "indexed");
                        if (tmpIndexed != null) {
                           propIndexed = tmpIndexed;
                        }
                     }

                     if (tmpDataSourceName == null) {
                        tmpDataSourceName = (String) ModelUtil.getAnnotationValue(propSetting, "dataSourceName");
                        if (tmpDataSourceName != null) {
                           propDataSourceName = tmpDataSourceName;
                        }
                     }

                     if (tmpSelectGroup == null) {
                        tmpSelectGroup = (String) ModelUtil.getAnnotationValue(propSetting, "selectGroup");
                        if (tmpSelectGroup != null) {
                           propSelectGroup = tmpSelectGroup;
                        }
                     }

                     if (tmpReverseProperty == null) {
                        tmpReverseProperty = (String) ModelUtil.getAnnotationValue(propSetting, "reverseProperty");
                        if (tmpReverseProperty != null) {
                           propReverseProperty = tmpReverseProperty;
                        }
                     }
                     if (tmpDBDefault == null) {
                        tmpDBDefault = (String) ModelUtil.getAnnotationValue(propSetting, "dbDefault");
                        if (tmpDBDefault != null) {
                           propDBDefault = tmpDBDefault;
                        }
                     }

                     if (tmpDynColumn == null) {
                        tmpDynColumn = (Boolean) ModelUtil.getAnnotationValue(propSetting, "dynColumn");
                        if (tmpDynColumn != null) {
                           propDynColumn = tmpDynColumn;
                        }
                     }
                  }
                  if (!persistProperty)
                     continue;
               }
               else {
                  // By default if there's a forward binding the value is derived and so usually does not have to be stored
                  // Use the forward bindings to add new 'rule' properties to the model that can be used easily as query properties
                  if (property instanceof VariableDefinition) {
                     VariableDefinition varDef = (VariableDefinition) property;
                     if (varDef.bindingDirection != null && varDef.bindingDirection.doForward())
                        continue;
                  }
               }

               // For inherited properties, need an extra check in case the type the property is defined in
               // has DBTypeSettings(persist=false) - meaning that property should not be persisted.  In that
               // case we would have had to inherit and override the typeSettings from the base class so we
               // only need to check if there's more than one annotation set for this type.
               if (!explicitPersist && inheritProperties) {
                  Object propEnclType = ModelUtil.getEnclosingType(property);
                  if (!ModelUtil.sameTypes(propEnclType, typeDecl) && typeSettings.size() > 1) {
                     Boolean persistPropType = getPersistSetting(sys, refLayer, propEnclType);
                     if (persistPropType != null && !persistPropType)
                        continue;
                  }
               }

               if (propColumnName == null)
                  propColumnName = SQLUtil.getSQLName(propName);
               BaseTypeDescriptor refBaseTypeDesc = null;
               DBTypeDescriptor refDBTypeDesc = null;

               boolean isMultiCol = false;

               // TODO: should we handle array and set properties here?
               boolean isArrayProperty = ModelUtil.isAssignableFrom(List.class, propType);
               if (isArrayProperty) {
                  if (ModelUtil.hasTypeParameters(propType)) {
                     Object componentType = ModelUtil.getTypeParameter(propType, 0);
                     if (ModelUtil.isTypeVariable(componentType)) {
                        componentType = Object.class; // Might be an unresolved type reference
                     }
                     if (!DBUtil.isDefaultJSONComponentType(componentType)) {
                        propType = componentType;
                     }
                     else {
                        propColumnType = "jsonb";
                        isArrayProperty = false;
                     }
                  }
                  else
                     propType = Object.class;
               }

               if (propColumnType == null) {
                  propColumnType = DBUtil.getDefaultSQLType(propType, false);
                  if (propColumnType == null) {
                     refBaseTypeDesc = getDBTypeDescriptor(sys, refLayer, propType, true);
                     if (refBaseTypeDesc == null) {
                        if (ModelUtil.isEnumType(propType))
                           propColumnType = "integer";
                        else
                           propColumnType = "jsonb";
                     }
                     else if (refBaseTypeDesc instanceof DBEnumDescriptor) {
                        propColumnType = ((DBEnumDescriptor) refBaseTypeDesc).sqlTypeName;
                     }
                     else {
                        refDBTypeDesc = (DBTypeDescriptor) refBaseTypeDesc;
                        TableDescriptor refTable = refDBTypeDesc.primaryTable;
                        if (refTable.idColumns.size() == 1)
                           propColumnType = DBUtil.getKeyIdColumnType(refTable.getIdColumns().get(0).columnType);
                        else {
                           isMultiCol = true;
                           StringBuilder cns = new StringBuilder();
                           StringBuilder cts = new StringBuilder();
                           for (int idx = 0; idx < refTable.idColumns.size(); idx++) {
                              if (idx != 0) {
                                 cns.append(",");
                                 cts.append(",");
                              }
                              IdPropertyDescriptor idCol = refTable.idColumns.get(idx);
                              cns.append(propColumnName);
                              cns.append("_");
                              cns.append(idCol.columnName);
                              cts.append(DBUtil.getKeyIdColumnType(idCol.columnType));
                           }
                           propColumnName = cns.toString();
                           propColumnType = cts.toString();
                        }
                     }
                  }
               }

               boolean multiRow = false;
               // TODO: should we allow scalar arrays - arrays of strings and stuff like that?
               if (isArrayProperty && refBaseTypeDesc != null)
                  multiRow = true;

               DBPropertyDescriptor propDesc;
               String fullTypeName = ModelUtil.getTypeName(typeDecl);

               boolean propOnDemand;
               if (setPropOnDemand != null)
                  propOnDemand = setPropOnDemand;
               // For associations, the default propOnDemand should be 'true' - don't load the associated property
               // For other properties, onDemand puts the property into it's own select group - so that property
               // gets loaded the first time it's accessed.
               // TODO: should we have a separate flag for association onDemand? It's probably common that we want to load the id
               // with the parent table but don't want to load associated item - so it's a mix of onDemand and eager.
               else
                  propOnDemand = refDBTypeDesc != null;

               if (isMultiCol) {
                  propDesc = new MultiColPropertyDescriptor(propName, propColumnName,
                          propColumnType, propTableName, propRequired, propUnique, propOnDemand, propIndexed,
                          propDataSourceName, propSelectGroup,
                          refBaseTypeDesc == null ? null : refBaseTypeDesc.getTypeName(),
                          multiRow, propReverseProperty, propDBDefault, fullTypeName);
               }
               else {
                  propDesc = new DBPropertyDescriptor(propName, propColumnName,
                          propColumnType, propTableName, propRequired, propUnique, propOnDemand, propIndexed, propDynColumn,
                          propDataSourceName, propSelectGroup,
                          refBaseTypeDesc == null ? null : refBaseTypeDesc.getTypeName(),
                          multiRow, propReverseProperty, propDBDefault, fullTypeName);
               }

               propDesc.refDBTypeDesc = refDBTypeDesc;
               if (refBaseTypeDesc instanceof DBEnumDescriptor)
                  propDesc.refEnumTypeDesc = (DBEnumDescriptor) refBaseTypeDesc;

               TableDescriptor propTable = primaryTable;

               if (multiRow) {
                  // NOTE: this table name may be reassigned after we resolve references to other type descriptors
                  // since the reference semantics determine how the reference is stored.  For example, if this is a
                  // one-to-many relationship, we'll use the table for the 'one' side to avoid an extra multi-table
                  if (propTableName == null)
                     propTableName = primaryTable.tableName + "_" + propColumnName;

                  TableDescriptor multiTable = new TableDescriptor(propTableName);
                  propTable = multiTable;
                  multiTable.multiRow = true;
                  multiTables.add(multiTable);
               }
               else if (propTableName != null && !propTableName.equals(propTable.tableName)) {
                  propTable = auxTablesIndex.get(propTableName);
                  if (propTable == null) {
                     TableDescriptor auxTable = new TableDescriptor(propTableName);
                     auxTablesIndex.put(propTableName, auxTable);
                     auxTables.add(auxTable);
                     propTable = auxTable;
                  }
               }
               propTable.addColumnProperty(propDesc);
               propDesc.propertyType = propType;

               newProps.add(new DBRegisteredProperty(propDesc, property));
            }
         }
         // Need to add the db_dyn_props column even for a table with no dyn properties so that we can add the
         // first one. Use the defaultDynColumn flag to signal this.
         if (defaultDynColumn && !primaryTable.hasDynColumns)
            primaryTable.hasDynColumns = true;
         dbTypeDesc.initTables(auxTables, multiTables, versionProp, false);
         initTableProperties(sys, refLayer, dbTypeDesc);

         if (dbTypeDesc.queries != null) {
            for (BaseQueryDescriptor query:dbTypeDesc.queries)
               initQueryParamTypes(sys, refLayer, typeDecl, query, true);
         }

         // Now go through the new properties we just added and see if there are additional getX/setX conversions
         // that need to be done.
         for (DBRegisteredProperty newProp:newProps) {
            Object property = newProp.property;
            DBPropertyDescriptor propDesc = newProp.propDesc;
            if (property instanceof PropertyAssignment)
               property = ((PropertyAssignment) property).getAssignedProperty();
            if (property instanceof VariableDefinition) {
               VariableDefinition varDef = (VariableDefinition) property;
               if (propDesc == null)
                  continue;
               if (varDef.isStarted()) {
                  varDef.addDBPropertyDescriptor(propDesc, sys, refLayer);
               }
               else
                  varDef.dbPropDesc = propDesc;
            }
            Object enclType = ModelUtil.getEnclosingType(property);
            if (enclType == null)
               continue;
            if (!ModelUtil.sameTypes(typeDecl, enclType)) {
               DBPropertyDescriptor enclPropDesc = getDBPropertyDescriptor(sys, refLayer, property, false);
               if (enclPropDesc == null) {
                  if (typeDecl instanceof TypeDeclaration) {
                     TypeDeclaration td = (TypeDeclaration) typeDecl;
                     td.addPropertyToMakeBindable(ModelUtil.getPropertyName(property), property, null, false, null);
                  }
                  else {
                     DBUtil.error("Unable to add persistence for property: " + ModelUtil.getPropertyName(property) + " of compiled type: " + ModelUtil.getTypeName(typeDecl));
                  }
               }
            }
         }
      }
   }

   private static void initTableProperties(LayeredSystem sys, Layer refLayer, DBTypeDescriptor dbTypeDesc) {
      for (DBPropertyDescriptor prop:dbTypeDesc.allDBProps) {
         if (prop.dbTypeDesc == null && prop.ownerTypeName != null) {
            prop.dbTypeDesc = sys.getDBTypeDescriptor(prop.ownerTypeName);
            if (prop.dbTypeDesc == null)
               DBUtil.error("No ownerTypeName: " + prop.ownerTypeName + " for property: " + prop);
         }
      }
   }

   private static Boolean getPersistSetting(LayeredSystem sys, Layer refLayer, Object typeDecl) {
      ArrayList<Object> typeSettings = ModelUtil.getAllInheritedAnnotations(sys, typeDecl, "sc.db.DBTypeSettings", false, refLayer, false);
      if (typeSettings == null)
         return null;

      for (Object annot:typeSettings) {
         Boolean tmpPersist  = (Boolean) ModelUtil.getAnnotationValue(annot, "persist");
         if (tmpPersist != null)
            return tmpPersist;
      }
      return null;
   }

   public boolean getNeedsGetSet() {
         return true;
   }

   private static String GET_PROP_TEMPLATE = "<% if (!dbPropDesc.isId()) { %>\n     sc.db.PropUpdate _pu = sc.db.DBObject.dbGetProperty<%= dbPropDesc.getNeedsRefId() ? \"WithRefId\" : \"\" %>(<%= dbObjVarName %>,\"<%= lowerPropertyName %>\");\n" +
                                                "     if (_pu != null) return (<%= propertyTypeName %><%= arrayDimensions %>) _pu.value; <% } %>";

   private static String UPDATE_PROP_TEMPLATE = "\n      if (sc.db.DBObject.<%= dbPropDesc.isId() ? \"dbSetIdProperty\" : \"dbSetProperty\"%>(<%= dbObjVarName %>, \"<%= lowerPropertyName %>\", _<%=lowerPropertyName%>) != null) return;";

   private static Template getPropertyTemplate;
   private static Template updatePropertyTemplate;

   public Map<String, SchemaManager> dbSchemas = null;

   static Template getGetPropertyTemplate() {
      if (getPropertyTemplate != null)
         return getPropertyTemplate;

      return getPropertyTemplate = TransformUtil.parseTemplate(GET_PROP_TEMPLATE,  PropertyDefinitionParameters.class, false, false, null, null);
   }

   static Template getUpdatePropertyTemplate() {
      if (updatePropertyTemplate != null)
         return updatePropertyTemplate;

      return updatePropertyTemplate = TransformUtil.parseTemplate(UPDATE_PROP_TEMPLATE,  PropertyDefinitionParameters.class, false, false, null, null);
   }

   public String evalGetPropertyTemplate(PropertyDefinitionParameters params) {
      return TransformUtil.evalTemplate(params, getGetPropertyTemplate());
   }

   public String evalUpdatePropertyTemplate(PropertyDefinitionParameters params) {
      return TransformUtil.evalTemplate(params, getUpdatePropertyTemplate());
   }

   public void processGeneratedFiles(BodyTypeDeclaration type, BaseTypeDescriptor typeDesc) {
      JavaModel model = type.getJavaModel();

      SQLFileModel sqlModel = SQLUtil.convertTypeToSQLFileModel(model, typeDesc);

      sqlModel.srcType = type;

      Object parseNode = ParseUtil.getParseNode(SQLLanguage.getSQLLanguage().sqlFileModel, sqlModel);
      if (parseNode instanceof IParseNode) {
         String sqlFileBody = parseNode.toString();
         model.addExtraFile(type.typeName + ".sql", sqlFileBody);
      }
      else {
         System.err.println("*** Error generating parse node for sql model: " + parseNode);
      }
   }

   public SQLFileModel addSchema(String dataSourceName, String typeName, SQLFileModel sqlModel) {
      if (dbSchemas == null)
         dbSchemas = new HashMap<String,SchemaManager>();

      SchemaManager schemaMgr = dbSchemas.get(dataSourceName);
      if (schemaMgr == null)
         dbSchemas.put(dataSourceName, schemaMgr = new SchemaManager(sqlModel.layeredSystem, this, dataSourceName));

      SQLFileModel oldModel = schemaMgr.schemasByType.put(typeName, sqlModel);

      if (oldModel != null)
         SchemaManager.replaceInSchemaList(schemaMgr.currentSchema, oldModel, sqlModel);
      else
         SchemaManager.addToSchemaList(schemaMgr.currentSchema, sqlModel);

      return oldModel;
   }

   public SQLFileModel getSchemaForType(String dataSourceName, String typeName) {
      if (dbSchemas != null) {
         SchemaManager mgr = dbSchemas.get(dataSourceName);
         if (mgr != null) {
            return mgr.schemasByType.get(typeName);
         }
      }
      return null;
   }

   public void generateSchemas(Layer buildLayer) {
      if (dbSchemas != null) {
         for (Map.Entry<String,SchemaManager> ent:dbSchemas.entrySet()) {
            SchemaManager schemaMgr = ent.getValue();
            //String dataSource = ent.getKey();

            schemaMgr.saveCurrentSchema(buildLayer);
         }
      }
   }

   public ISchemaUpdater getSchemaUpdater() {
      if (schemaUpdater != null)
         return schemaUpdater;
      // This gets defined at when the program starts up so we access them from DataSourceManager.
      schemaUpdater = DataSourceManager.getSchemaUpdater(providerName);
      return schemaUpdater;
   }

   public void applySchemaOperation(Layer buildLayer, SchemaManager.SchemaMode schemaMode) {
      if (dbSchemas != null) {
         for (Map.Entry<String,SchemaManager> ent:dbSchemas.entrySet()) {
            SchemaManager schemaMgr = ent.getValue();
            switch (schemaMode) {
               case Update:
                  DBUtil.info("Updating schema for: " + schemaMgr.dataSourceName);
                  if (schemaMgr.updateSchema(buildLayer, true)) {
                     schemaMgr.initFromDB(buildLayer, true);
                  }
                  break;
               case Accept:
                  DBUtil.info("Accepting current schema for: " + schemaMgr.dataSourceName);
                  if (schemaMgr.updateSchema(buildLayer, false)) {
                     schemaMgr.initFromDB(buildLayer, true);
                  }
                  break;
               default:
                  System.err.println("*** Unrecognized schema apply mode");
                  break;
            }
         }
      }
   }
}
