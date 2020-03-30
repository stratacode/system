package sc.lang.sql;

import sc.db.*;
import sc.lang.SQLLanguage;
import sc.lang.java.*;
import sc.lang.template.Template;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.parser.IParseNode;
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

   public static DBPropertyDescriptor getDBPropertyDescriptor(LayeredSystem sys, Layer refLayer, Object propObj) {
      Object enclType = ModelUtil.getEnclosingType(propObj);
      if (enclType != null) {
         DBTypeDescriptor typeDesc = getDBTypeDescriptor(sys, refLayer, enclType, true);
         String propName = ModelUtil.getPropertyName(propObj);
         if (typeDesc != null) {
            typeDesc.init();
            return typeDesc.getPropertyDescriptor(propName);
         }
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
      DBTypeDescriptor typeDesc = getDBTypeDescriptor(sys, ModelUtil.getLayerForType(sys, typeObj), typeObj, false);
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
      Object enclType = ModelUtil.getEnclosingType(propObj);
      String propName = ModelUtil.getPropertyName(propObj);
      DBTypeDescriptor typeDesc = enclType == null ? null : getDBTypeDescriptor(sys, ModelUtil.getLayerForMember(sys, propObj), enclType, true);
      if (typeDesc != null) {
         DBPropertyDescriptor propDesc = typeDesc.getPropertyDescriptor(propName);
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

   public static DBTypeDescriptor getDBTypeDescriptor(LayeredSystem sys, Layer refLayer, Object typeDecl, boolean initTables) {
      if (typeDecl instanceof ITypeDeclaration) {
         DBTypeDescriptor res = ((ITypeDeclaration) typeDecl).getDBTypeDescriptor();
         if (res != null && initTables && !res.tablesInitialized)
            completeDBTypeDescriptor(res, sys, refLayer, typeDecl);
         return res;
      }
      else {
         DBTypeDescriptor res = initDBTypeDescriptor(sys, refLayer, typeDecl);
         if (res != null && initTables && !res.tablesInitialized)
            completeDBTypeDescriptor(res, sys, refLayer, typeDecl);
         return res;
      }
   }

   /**
    * Defines the part of the DBTypeDescriptor that includes the primaryTable and idColumns - but does not try to resolve
    * other DBTypeDescriptors for references. This part only can be used to define new interface methods in the DBDefinesTypeTemplate
    */
   public static DBTypeDescriptor initDBTypeDescriptor(LayeredSystem sys, Layer refLayer, Object typeDecl) {
      ArrayList<Object> typeSettings = ModelUtil.getAllInheritedAnnotations(sys, typeDecl, "sc.db.DBTypeSettings", false, refLayer, false);
      // TODO: should check for annotations on the Layer of all types in the type tree. For each attribute, check the layer annotation if it's not set at the type level
      // TODO: need getAllLayerAnnotations(typeDecl, annotName)
      if (typeSettings != null) {
         boolean persist = true;
         String dataSourceName = null;
         String primaryTableName = null;
         for (Object annot:typeSettings) {
            Boolean tmpPersist  = (Boolean) ModelUtil.getAnnotationValue(annot, "persist");
            if (tmpPersist != null)
               persist = tmpPersist;
            String tmpDataSourceName  = (String) ModelUtil.getAnnotationValue(annot, "dataSourceName");
            if (tmpDataSourceName != null)
               dataSourceName = tmpDataSourceName;
            String tmpPrimaryTableName = (String) ModelUtil.getAnnotationValue(annot, "primaryTable");
            if (tmpPrimaryTableName != null) {
               primaryTableName = tmpPrimaryTableName;
            }
         }

         if (persist) {
            if (dataSourceName == null) {
               DBDataSource def = sys.defaultDataSource;
               dataSourceName = def == null ? null : def.jndiName;
               if (dataSourceName == null)
                  return null;
            }
            Object baseType = ModelUtil.getExtendsClass(typeDecl);
            DBTypeDescriptor baseTD = baseType != null && baseType != Object.class ? getDBTypeDescriptor(sys, refLayer, baseType, false) : null;

            String fullTypeName = ModelUtil.getTypeName(typeDecl);

            // For ModifyDeclaration don't use the cached type descriptor since it might need to be refined
            DBTypeDescriptor dbTypeDesc = null;
            if (!(typeDecl instanceof BodyTypeDeclaration)) {
               dbTypeDesc = sys.getDBTypeDescriptor(fullTypeName);
               if (dbTypeDesc != null)
                  return dbTypeDesc;
            }

            String typeName = CTypeUtil.getClassName(fullTypeName);
            if (primaryTableName == null)
               primaryTableName = SQLUtil.getSQLName(typeName);
            TableDescriptor primaryTable = new TableDescriptor(primaryTableName);

            List<FindByDescriptor> findByQueries = null;

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

                     String fetchGroup = (String) ModelUtil.getAnnotationValue(findByProp, "fetchGroup");
                     Boolean paged = (Boolean) ModelUtil.getAnnotationValue(findByProp, "paged");

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

                     Object propSettings = ModelUtil.getAnnotation(property, "sc.db.DBPropertySettings");
                     if (propSettings != null) {
                        Boolean tmpUnique  = (Boolean) ModelUtil.getAnnotationValue(propSettings, "unique");
                        if (tmpUnique != null && tmpUnique) {
                           multiRowQuery = false;
                        }
                        if (fetchGroup == null) {
                           String tmpFetchGroup = (String) ModelUtil.getAnnotationValue(propSettings, "fetchGroup");
                           if (tmpFetchGroup != null) {
                              fetchGroup = tmpFetchGroup;
                           }
                        }
                     }

                     FindByDescriptor fbDesc = new FindByDescriptor(propName, fbProps, fbOptions, orderByProps, orderByOption, multiRowQuery, fetchGroup, paged != null && paged);
                     if (findByQueries == null)
                        findByQueries = new ArrayList<FindByDescriptor>();
                     findByQueries.add(fbDesc);

                     initFindByParamTypes(sys, typeDecl, fbDesc, false);
                  }

                  Object idSettings = ModelUtil.getAnnotation(property, "sc.db.IdSettings");
                  if (idSettings != null) {
                     String idColumnName = null;
                     String idColumnType = null;
                     boolean definedByDB = true;

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

                     IdPropertyDescriptor idDesc = new IdPropertyDescriptor(propName, idColumnName, idColumnType, definedByDB);

                     primaryTable.addIdColumnProperty(idDesc);
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

                  String fetchGroup = (String) ModelUtil.getAnnotationValue(findByCl, "fetchGroup");
                  Boolean paged = (Boolean) ModelUtil.getAnnotationValue(findByCl, "paged");
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

                  FindByDescriptor fbDesc = new FindByDescriptor(name, fbProps, fbOptions, orderByProps, orderByOption, multiRowQuery, fetchGroup, paged != null && paged);
                  if (findByQueries == null)
                     findByQueries = new ArrayList<FindByDescriptor>();
                  findByQueries.add(fbDesc);

                  initFindByParamTypes(sys, typeDecl, fbDesc, false);
               }
            }

            dbTypeDesc = new DBTypeDescriptor(typeDecl, baseTD, dataSourceName, primaryTable, findByQueries);

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

   public static void completeDBTypeDescriptor(DBTypeDescriptor dbTypeDesc, LayeredSystem sys, Layer refLayer, Object typeDecl) {
      if (dbTypeDesc == null)
         return;

      ArrayList<Object> typeSettings = ModelUtil.getAllInheritedAnnotations(sys, typeDecl, "sc.db.DBTypeSettings", false, refLayer, false);
      dbTypeDesc.tablesInitialized = true;

      Object[] properties = ModelUtil.getDeclaredProperties(typeDecl, null, false, true, false);

      String versionProp = null;
      ArrayList<String> auxTableNames = null;

      TableDescriptor primaryTable = dbTypeDesc.primaryTable;

      if (typeSettings != null) {
         for (Object annot:typeSettings) {
            String tmpVersionProp = (String) ModelUtil.getAnnotationValue(annot, "versionProp");
            if (tmpVersionProp != null) {
               versionProp = tmpVersionProp;
            }
            String tmpAuxTableNames = (String) ModelUtil.getAnnotationValue(annot, "auxTables");
            if (tmpAuxTableNames != null) {
               auxTableNames = new ArrayList<String>(Arrays.asList(StringUtil.split(tmpAuxTableNames, ',')));
            }
         }

         ArrayList<TableDescriptor> auxTables = new ArrayList<TableDescriptor>();
         Map<String,TableDescriptor> auxTablesIndex = new TreeMap<String,TableDescriptor>();
         ArrayList<TableDescriptor> multiTables = new ArrayList<TableDescriptor>();
         if (auxTableNames != null) {
            for (String auxTableName:auxTableNames)
               auxTables.add(new TableDescriptor(auxTableName));
         }
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

               Object propSettings = ModelUtil.getAnnotation(property, "sc.db.DBPropertySettings");
               String propTableName = null;
               String propColumnName = null;
               String propColumnType = null;
               boolean propOnDemand = false;
               boolean propRequired = false;
               boolean propUnique = false;
               String propDataSourceName = null;
               String propFetchGroup = null;
               String propReverseProperty = null;
               String propDBDefault = null;
               if (propSettings != null) {
                  Boolean tmpPersist  = (Boolean) ModelUtil.getAnnotationValue(propSettings, "persist");
                  if (tmpPersist != null && !tmpPersist) {
                     continue;
                  }

                  String tmpTableName = (String) ModelUtil.getAnnotationValue(propSettings, "tableName");
                  if (tmpTableName != null) {
                     propTableName = tmpTableName;
                  }

                  // TODO: should we specify columnNames and Types here as comma separated lists to deal with multi-column primary key properties (and possibly others that need more than one column?)
                  String tmpColumnName = (String) ModelUtil.getAnnotationValue(propSettings, "columnName");
                  if (tmpColumnName != null) {
                     propColumnName = tmpColumnName;
                  }

                  String tmpColumnType = (String) ModelUtil.getAnnotationValue(propSettings, "columnType");
                  if (tmpColumnType != null) {
                     propColumnType = tmpColumnType;
                  }

                  Boolean tmpOnDemand  = (Boolean) ModelUtil.getAnnotationValue(propSettings, "onDemand");
                  if (tmpOnDemand != null) {
                     propOnDemand = tmpOnDemand;
                  }
                  Boolean tmpRequired  = (Boolean) ModelUtil.getAnnotationValue(propSettings, "required");
                  if (tmpRequired != null) {
                     propRequired = tmpRequired;
                  }
                  Boolean tmpUnique  = (Boolean) ModelUtil.getAnnotationValue(propSettings, "unique");
                  if (tmpUnique != null) {
                     propUnique = tmpUnique;
                  }

                  String tmpDataSourceName = (String) ModelUtil.getAnnotationValue(propSettings, "dataSourceName");
                  if (tmpDataSourceName != null) {
                     propDataSourceName = tmpDataSourceName;
                  }

                  String tmpFetchGroup = (String) ModelUtil.getAnnotationValue(propSettings, "fetchGroup");
                  if (tmpFetchGroup != null) {
                     propFetchGroup = tmpFetchGroup;
                  }

                  String tmpReverseProperty = (String) ModelUtil.getAnnotationValue(propSettings, "reverseProperty");
                  if (tmpReverseProperty != null) {
                     propReverseProperty = tmpReverseProperty;
                  }
                  String tmpDBDefault = (String) ModelUtil.getAnnotationValue(propSettings, "dbDefault");
                  if (tmpDBDefault != null) {
                     propDBDefault = tmpDBDefault;
                  }
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

               if (propColumnName == null)
                  propColumnName = SQLUtil.getSQLName(propName);
               DBTypeDescriptor refDBTypeDesc = null;

               boolean isMultiCol = false;

               // TODO: should we handle array and set properties here?
               boolean isArrayProperty = ModelUtil.isAssignableFrom(List.class, propType);
               if (isArrayProperty) {
                  if (ModelUtil.hasTypeParameters(propType)) {
                     propType = ModelUtil.getTypeParameter(propType, 0);
                  }
                  else
                     propType = Object.class;
               }

               if (propColumnType == null) {
                  propColumnType = DBUtil.getDefaultSQLType(propType, false);
                  if (propColumnType == null) {
                     refDBTypeDesc = getDBTypeDescriptor(sys, refLayer, propType, true);
                     if (refDBTypeDesc == null) {
                        propColumnType = "jsonb";
                     }
                     else {
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
               if (isArrayProperty && refDBTypeDesc != null)
                  multiRow = true;

               DBPropertyDescriptor propDesc;

               if (isMultiCol) {
                  propDesc = new MultiColPropertyDescriptor(propName, propColumnName,
                          propColumnType, propTableName, propRequired, propUnique, propOnDemand,
                          propDataSourceName, propFetchGroup,
                          refDBTypeDesc == null ? null : refDBTypeDesc.getTypeName(),
                          multiRow, propReverseProperty, propDBDefault);
               }
               else {
                  propDesc = new DBPropertyDescriptor(propName, propColumnName,
                          propColumnType, propTableName, propRequired, propUnique, propOnDemand,
                          propDataSourceName, propFetchGroup,
                          refDBTypeDesc == null ? null : refDBTypeDesc.getTypeName(),
                          multiRow, propReverseProperty, propDBDefault);
               }

               propDesc.refDBTypeDesc = refDBTypeDesc;

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
            }
         }
         dbTypeDesc.initTables(auxTables, multiTables, versionProp);

         if (dbTypeDesc.findByQueries != null) {
            for (FindByDescriptor fbDesc:dbTypeDesc.findByQueries)
               initFindByParamTypes(sys, typeDecl, fbDesc, true);
         }
      }
   }

   public boolean getNeedsGetSet() {
      return true;
   }

   private static String GET_PROP_TEMPLATE = "<% if (!dbPropDesc.isId()) { %>\n     sc.db.PropUpdate _pu = sc.db.DBObject.fetch(<%= dbObjVarName %>,\"<%= lowerPropertyName %>\");\n" +
                                                "     if (_pu != null) return (<%= propertyTypeName %><%= arrayDimensions %>) _pu.value; <% } %>";

   private static String UPDATE_PROP_TEMPLATE = "\n      if (<%= dbObjPrefix%><%= dbSetPropMethod %>(\"<%= lowerPropertyName %>\", _<%=lowerPropertyName%>) != null) return;";

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

   public void processGeneratedFiles(BodyTypeDeclaration type, DBTypeDescriptor typeDesc) {
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
}
