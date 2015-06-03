/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.parser.Sequence;
import sc.parser.Symbol;

public class XMLLanguage extends HTMLLanguage {
   public final static XMLLanguage INSTANCE = new XMLLanguage();

   SymbolSpace xmlControlStart = new SymbolSpace("<?xml");
   SymbolSpace xmlControlClose = new SymbolSpace("?>");

   public boolean validTagChar(char c) {
      return Character.isLetterOrDigit(c) || c == '-' || c == ':' || c == '_' || c == '.';
   }

   Sequence xmlControlTag = new Sequence("XMLControlTag(,attributeList,)", OPTIONAL, xmlControlStart, tagAttributes, xmlControlClose);

   {
      UNESCAPED_SET.clear();
      INDENTED_SET.clear();
      NEWLINE_SET.clear();

      // All XML tags are tree tags.
      templateBodyDeclarations.replace(anyTag, treeTag);
      simpleTemplateDeclarations.set(1, treeTag);

      template.setName("XMLTemplate(, xmlControl, *, templateDeclarations,)");
      template.set(spacing, xmlControlTag, templateAnnotations, templateBodyDeclarations, new Symbol(EOF));

      // We can use this parselet as-is except for the model class name.
      treeTag.setResultClassName("XMLElement");
   }

   public XMLLanguage() {
      super();
      addToSemanticValueClassPath("sc.lang.xml");
      languageName = "SCXml";
      defaultExtension = "scxml";
   }

   public static XMLLanguage getXMLLanguage() {
      return INSTANCE;
   }
}
