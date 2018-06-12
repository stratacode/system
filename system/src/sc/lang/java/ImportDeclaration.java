/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ISemanticNode;
import sc.parser.IParseNode;
import sc.type.CTypeUtil;

import java.util.Set;

public class ImportDeclaration extends AbstractErrorNode {
   public final static String WILDCARD = ".*";

   public boolean staticImport;
   public String identifier;

   public boolean hasWildcard() {
      return identifier.endsWith(WILDCARD);
   }

   public String getIdentifierPrefix() {
      if (hasWildcard())
         return identifier.substring(0,identifier.length()-WILDCARD.length());
      return identifier;
   }

   public static ImportDeclaration create(String ident) {
      ImportDeclaration id = new ImportDeclaration();
      id.identifier = ident;
      return id;
   }
   public static ImportDeclaration createStatic(String ident) {
      ImportDeclaration id = new ImportDeclaration();
      id.identifier = ident;
      id.staticImport = true;
      return id;
   }

   public String toString() {
      return toSafeLanguageString();
   }

   public String toSafeLanguageString() {
      StringBuilder sb = new StringBuilder();
      if (staticImport)
         sb.append("static ");
      sb.append("import ");
      sb.append(identifier);
      return sb.toString();
   }


   public String addNodeCompletions(JavaModel origModel, JavaSemanticNode origNode, String matchPrefix, int offset, String dummyIdentifier, Set<String> candidates, boolean nextNameInPath) {
      String packagePrefix;
      boolean isQualifiedType = false;

      if (identifier == null)
         return matchPrefix;

      if (matchPrefix.contains(".")) {
         packagePrefix = CTypeUtil.getPackageName(matchPrefix);
         matchPrefix = CTypeUtil.getClassName(matchPrefix);
         isQualifiedType = true;
      }
      else {
         packagePrefix = origModel.getPackagePrefix();
      }
      ModelUtil.suggestTypes(origModel, packagePrefix, matchPrefix, candidates, true);
      if (origModel != null && !isQualifiedType) {
         Object currentType = origNode == null ? origModel.getModelTypeDeclaration() : origNode.getEnclosingType();
         if (currentType != null)
            ModelUtil.suggestMembers(origModel, currentType, identifier, candidates, true, true, true, true);
      }
      return matchPrefix;
   }
}

