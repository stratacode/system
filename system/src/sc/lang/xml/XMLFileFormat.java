/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.xml;

import sc.util.IMessageHandler;
import sc.util.MessageType;
import sc.lang.XMLLanguage;
import sc.lang.html.Element;
import sc.lang.template.Template;
import sc.parser.ParseError;
import sc.parser.ParseUtil;

import java.io.File;

// Base class or utility class for reading an arbitrary XML format
public class XMLFileFormat {
   public IMessageHandler msg;
   public String fileName;

   public Template fileTemplate;

   public XMLFileFormat(String fileName, IMessageHandler messages) {
      this.fileName = fileName;
      msg = messages;
   }

   public boolean parse() {
      XMLLanguage lang = XMLLanguage.getXMLLanguage();
      lang.initialize();
      Object parseRes = lang.parse(fileName, false);
      if (parseRes instanceof ParseError) {
         error("Failed to parse POM file: " + ((ParseError) parseRes).errorStringWithLineNumbers(new File(fileName)));
         return false;
      }
      else {
         fileTemplate = (Template) ParseUtil.nodeToSemanticValue(parseRes);
      }
      return true;
   }

   public Element getRootElement() {
      return fileTemplate.getSingleFileElement(null);
   }

   public void error(String... args) {
      StringBuilder buf = new StringBuilder();
      for (String arg:args)
         buf.append(arg);
      if (msg != null)
         msg.reportMessage(buf, "file://" + fileName, -1, -1, MessageType.Error);
      else
         System.err.println(fileName + ": " + buf);
   }
}
