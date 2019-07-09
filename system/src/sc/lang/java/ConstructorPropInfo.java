package sc.lang.java;

import sc.lang.ISemanticNode;
import sc.lang.SCLanguage;
import sc.lang.SemanticNodeList;
import sc.parser.ParseUtil;

import java.util.List;

public class ConstructorPropInfo {
   public List<String> propNames;

   public List<JavaType> propJavaTypes;
   public List<Object> propTypes;
   public SemanticNodeList<Statement> initStatements;

   public ConstructorDefinition constr;

   public String getBeforeNewObject() {
      if (initStatements == null || initStatements.size() == 0)
         return "";

      return ParseUtil.toLanguageString(SCLanguage.INSTANCE.blockStatements, initStatements);
   }

   public ConstructorPropInfo copy() {
      ConstructorPropInfo cpi = new ConstructorPropInfo();
      cpi.propNames = propNames;
      cpi.propJavaTypes = propJavaTypes;
      cpi.propTypes = propTypes;
      cpi.initStatements = (SemanticNodeList<Statement>) initStatements.deepCopy(ISemanticNode.CopyNormal, null);
      if (constr != null) {
         cpi.constr = (ConstructorDefinition) constr.deepCopy(ISemanticNode.CopyNormal, null);
         cpi.constr.fromStatement = null;
      }
      return cpi;
   }
}
