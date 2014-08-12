package sc.lang.html;

import sc.lang.SemanticNode;
import sc.lang.java.Expression;
import sc.lang.java.JavaSemanticNode;

/** Used for HTMLLanguage's attributes of the form attName="= expr" where this contains the "= expr" part. */
public class AttrExpr extends JavaSemanticNode {
   public String op;
   public Expression expr;
}
