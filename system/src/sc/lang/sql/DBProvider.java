package sc.lang.sql;

import sc.db.DBTypeDescriptor;
import sc.lang.SQLLanguage;
import sc.lang.java.BodyTypeDeclaration;
import sc.lang.java.JavaModel;
import sc.lang.java.PropertyDefinitionParameters;
import sc.lang.java.TransformUtil;
import sc.lang.template.Template;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.layer.SrcEntry;
import sc.layer.SrcIndexEntry;
import sc.parser.IParseNode;
import sc.parser.ParseUtil;
import sc.util.FileUtil;
import sc.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class DBProvider {
   public String providerName;
   public Layer definedInLayer;

   public DBProvider(String providerName) {
      this.providerName = providerName;
   }

   public boolean getNeedsGetSet() {
      return true;
   }

   private static String GET_PROP_TEMPLATE = "<% if (!dbPropDesc.isId()) { %>\n     sc.db.PropUpdate _pu = sc.db.DBObject.fetch(<%= dbObjVarName %>,\"<%= lowerPropertyName %>\");\n" +
                                                "     if (_pu != null) return (<%= propertyTypeName %><%= arrayDimensions %>) _pu.value; <% } %>";

   private static String UPDATE_PROP_TEMPLATE = "\n      if (<%= dbObjPrefix%><%= dbSetPropMethod %>(\"<%= lowerPropertyName %>\", _<%=lowerPropertyName%>) != null) return;";

   private static Template getPropertyTemplate;
   private static Template updatePropertyTemplate;

   public Map<String, ArrayList<SQLFileModel>> dbSchemas = null;

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

      LayeredSystem sys = model.layeredSystem;
      sys.schemaManager.schemasByType.put(type.getFullTypeName(), sqlModel);

      addDBSchema(typeDesc.dataSourceName, sqlModel);

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

   private void addDBSchema(String dataSourceName, SQLFileModel fileModel) {
      if (dbSchemas == null)
         dbSchemas = new HashMap<String,ArrayList<SQLFileModel>>();

      ArrayList<SQLFileModel> models = dbSchemas.get(dataSourceName);
      int ix = -1;
      if (models == null) {
         models = new ArrayList<SQLFileModel>();
         dbSchemas.put(dataSourceName, models);
      }
      else {
         for (int i = 0; i < models.size(); i++) {
            SQLFileModel curModel = models.get(i);
            if (curModel.hasTableReference(fileModel)) {
               ix = i;
               break;
            }
         }
      }

      if (ix == -1)
         models.add(fileModel);
      else
         models.add(ix, fileModel);
   }

   public void generateSchemas(Layer buildLayer) {
      if (dbSchemas != null) {
         for (Map.Entry<String,ArrayList<SQLFileModel>> ent:dbSchemas.entrySet()) {
            String dataSource = ent.getKey();
            ArrayList<SQLFileModel> sqlFileModels = ent.getValue();

            boolean any = false;
            StringBuilder schemaSB = new StringBuilder();
            schemaSB.append("/* Database schema for datasource: " + dataSource + " */\n");
            for (SQLFileModel sqlFileModel:sqlFileModels) {
               schemaSB.append("\n\n/* schema for type: " + sqlFileModel.srcType.getFullTypeName() + " */\n\n");
               schemaSB.append(sqlFileModel.toLanguageString());
               schemaSB.append("\n");
               any = true;
            }

            if (any) {
               // TODO: should we use the dbname here?
               String dataSourceFile = dataSource.replace("/", "_");
               String schemaStr = schemaSB.toString();
               SrcEntry sqlSrcEnt = new SrcEntry(buildLayer, buildLayer.buildSrcDir, "",  FileUtil.addExtension(dataSourceFile, "sql"), false, null);
               sqlSrcEnt.hash = StringUtil.computeHash(schemaStr.getBytes());

               SrcIndexEntry srcIndex = buildLayer.getSrcFileIndex(sqlSrcEnt.relFileName);
               // Avoid rewriting unchanged files
               if (srcIndex == null || !Arrays.equals(srcIndex.hash, sqlSrcEnt.hash)) {
                  FileUtil.saveStringAsReadOnlyFile(sqlSrcEnt.absFileName, schemaSB.toString(), false);
                  buildLayer.addSrcFileIndex(sqlSrcEnt.relFileName, sqlSrcEnt.hash, null, sqlSrcEnt.absFileName);
               }
            }
         }
      }
   }

}
