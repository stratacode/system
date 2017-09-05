/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sc;

import sc.lang.SCLanguage;
import sc.lang.SemanticNodeList;
import sc.lang.java.JavaModel;
import sc.parser.IParseNode;
import sc.lang.java.TypeDeclaration;
import sc.parser.Language;
import sc.util.StringUtil;

public class SCModel extends JavaModel {

   public boolean enableExtensions() {
      return true;
   }

   public static SCModel create(String packageName, TypeDeclaration modType) {
      SCModel model = new SCModel();
      if (!StringUtil.isEmpty(packageName)) {
         model.packageDef = sc.lang.java.Package.create(packageName);
      }
      SemanticNodeList<TypeDeclaration> snl = new SemanticNodeList<TypeDeclaration>();
      snl.add(modType);
      model.setProperty("types", snl);
      return model;
   }

   public String toLanguageString() {
      if (parseNode == null) {
         Object genRes = SCLanguage.INSTANCE.generate(this, false);
         if (genRes instanceof IParseNode)
            parseNode = (IParseNode) genRes;
         else
            System.out.println("Generation error for model: " + toModelString());
      }
      return super.toLanguageString();
   }

   public String getUserVisibleName() {
      return "sc file: ";
   }

   public SCLanguage getLanguage() {
      return SCLanguage.getSCLanguage();
   }
}
