/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

public class ImportDeclaration extends JavaSemanticNode {
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
      return staticImport ? "static " : "" + "import " + identifier;
   }
}
