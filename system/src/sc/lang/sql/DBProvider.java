package sc.lang.sql;

import sc.db.DBTypeDescriptor;
import sc.lang.SQLLanguage;
import sc.lang.java.BodyTypeDeclaration;
import sc.lang.java.JavaModel;
import sc.lang.java.PropertyDefinitionParameters;
import sc.lang.java.TransformUtil;
import sc.lang.template.Template;
import sc.parser.IParseNode;
import sc.parser.ParseUtil;

public class DBProvider {
   public String providerName;

   public DBProvider(String providerName) {
      this.providerName = providerName;
   }

   public boolean getNeedsGetSet() {
      return true;
   }

   private static String GET_PROP_TEMPLATE = "<% if (!dbPropDesc.isId()) { %>\n     sc.db.PropUpdate _pu = <%= dbObjPrefix%>dbFetch(\"<%= lowerPropertyName %>\");\n" +
                                                "     if (_pu != null) return (<%= propertyTypeName %><%= arrayDimensions %>) _pu.value; <% } %>";

   private static String UPDATE_PROP_TEMPLATE = "\n      if (<%= dbObjPrefix%><%= dbSetPropMethod %>(\"<%= lowerPropertyName %>\", _<%=lowerPropertyName%>) != null) return;";

   private static Template getPropertyTemplate;
   private static Template updatePropertyTemplate;

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

      SQLFileModel fileModel = SQLUtil.convertTypeToSQLFileModel(model, typeDesc);

      Object parseNode = ParseUtil.getParseNode(SQLLanguage.getSQLLanguage().sqlFileModel, fileModel);
      if (parseNode instanceof IParseNode) {
         String sqlFileBody = parseNode.toString();
         model.addExtraFile(type.typeName + ".sql", sqlFileBody);
      }
      else {
         System.err.println("*** Error generating parse node for sql model: " + parseNode);
      }
   }
}
