/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

public class XMLLanguage extends HTMLLanguage {
   public final static XMLLanguage INSTANCE = new XMLLanguage();

   {
      UNESCAPED_SET.clear();
      INDENTED_SET.clear();
      NEWLINE_SET.clear();
   }

   public XMLLanguage() {
      setStartParselet(template);
      //addToSemanticValueClassPath("sc.lang.html");
      languageName = "SCXml";
      defaultExtension = "scxml";
   }

   public static XMLLanguage getXMLLanguage() {
      return INSTANCE;
   }
}
