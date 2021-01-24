/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.lang.SemanticNode;
import sc.lang.java.Expression;
import sc.lang.java.JavaSemanticNode;

/** Used for HTMLLanguage's attributes of the form attName="= expr" where this contains the "= expr" part. */
public class AttrExpr extends JavaSemanticNode {
   public String op;
   public Expression expr;

   public String toString() {
      if (op == null || expr == null)
         return "<not initialized>";
      return op + " " + expr.toSafeLanguageString();
   }
}
