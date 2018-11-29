/*
 * Copyright (c) 2018. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

/** An optional context argument that specifies state passed to the outputTag, outputBody, outputStartTag methods as the second argument
 * after the 'sb'.  If it's null, the default output is used. */
public class OutputCtx {
   public boolean validateCache;
   public String toString() {
      return "ctx:validateCache=" + validateCache;
   }
}
