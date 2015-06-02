/*
 * Copyright (c) 2015. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos.mvn;

import sc.util.IMessageHandler;
import sc.lang.html.Element;
import sc.lang.xml.XMLFileFormat;
import sc.util.MessageHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class POMFile extends XMLFileFormat {
   Element projElement;

   public POMFile(String fileName, IMessageHandler handler) {
      super(fileName, handler);
   }

   public static POMFile readPOM(String fileName, IMessageHandler msg) {
      POMFile file = new POMFile(fileName, msg);
      if (file.parse())
         return file;
      else
         return null;
   }

   public boolean parse() {
      if (!super.parse())
         return false;

      projElement = getRootElement();

      if (projElement == null) {
         error("POM File should contain only a single <project> XML tag");
      }

      if (!projElement.tagName.equals("project")) {
         error("POM file contains tag: " + projElement.tagName + " expected <project>");
         return false;
      }

      // TODO - process inherited POMs and sub-modules - read the other POM file and populate hash-tables
      // with keys for the things that can be overridden.   Propagate variables from the nested file to here

      return true;
   }

   public static final String DEFAULT_SCOPE = "compile";

   public List<MvnDescriptor> getDependencies(String scope) {
      if (scope == null)
         scope = DEFAULT_SCOPE;
      Element[] deps = projElement.getChildTagsWithName("dependencies");
      if (deps == null || deps.length == 0)
         return Collections.emptyList();
      if (deps.length > 1)
         MessageHandler.error(msg, "Multiple tags with dependencies - should be only one");
      ArrayList<MvnDescriptor> res = new ArrayList<MvnDescriptor>();
      Element[] depTags = deps[0].getChildTagsWithName("dependency");
      if (depTags != null) {
         for (Element depTag:depTags) {
            String depScope = depTag.getSimpleChildValue("scope");
            if (depScope == null)
               depScope = DEFAULT_SCOPE;
            if (scope.equals(depScope)) {
               String groupId = getTagValue(depTag, "groupId");
               String artifactId = getTagValue(depTag, "artifactId");
               String version = getTagValue(depTag, "version");
               res.add(new MvnDescriptor(groupId, artifactId, version));
            }
         }
      }
      return res;
   }

   public String getTagValue(Element tag, String valName) {
      // TODO: substitute variables here
      return tag.getSimpleChildValue(valName);
   }
}