/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.layer.Layer;
import sc.parser.Sequence;
import sc.parser.Symbol;

public class XMLLanguage extends HTMLLanguage {
   public final static XMLLanguage INSTANCE = new XMLLanguage();

   SymbolSpace xmlControlStart = new SymbolSpace("<?xml");
   SymbolSpace xmlControlClose = new SymbolSpace("?>");

   private final static String CDATA_START = "<![CDATA[", CDATA_END = "]]>";
   Sequence cdataString = new Sequence("(,'',)", new Symbol(CDATA_START), new Symbol(NOT | REPEAT, CDATA_END), new Symbol(CDATA_END));

   public boolean validTagChar(char c) {
      return Character.isLetterOrDigit(c) || c == '-' || c == ':' || c == '_' || c == '.';
   }

   public boolean validStartTagChar(char c) {
      return super.validStartTagChar(c) || c == '_';
   }

   Sequence xmlControlTag = new Sequence("XMLControlTag(,attributeList,)", OPTIONAL, xmlControlStart, tagAttributes, xmlControlClose);

   {
      UNESCAPED_SET.clear();
      INDENTED_SET.clear();
      NEWLINE_SET.clear();

      // In some XML files at least, the newline is allowed in the body
      escapedStringBody.removeExpectedValue("\n");
      // Also backslash
      escapedStringBody.removeExpectedValue("\\");

      // For some reason some XML files do things like &lt;strong>Site&lt;/strong>
      templateString.removeExpectedValue(">");

      // All XML tags are tree tags.
      templateBodyDeclarations.replace(anyTag, treeTag);
      templateBodyDeclarations.put(CDATA_START, cdataString);
      templateBodyDeclarations.setName("([],[],[],[],[],[],[])");

      simpleTemplateDeclarations.set(1, treeTag);

      template.setName("XMLTemplate(, xmlControl, *, templateDeclarations,)");
      template.set(spacing, xmlControlTag, templateAnnotations, templateBodyDeclarations, new Symbol(EOF));

      // We can use this parselet as-is except for the model class name.
      treeTag.setResultClassName("XMLElement");
   }

   public XMLLanguage() {
      this(null);
   }

   public XMLLanguage(Layer layer) {
      super(layer);
      addToSemanticValueClassPath("sc.lang.xml");
      languageName = "SCXml";
      defaultExtension = "scxml";
   }

   public static XMLLanguage getXMLLanguage() {
      return INSTANCE;
   }
}
