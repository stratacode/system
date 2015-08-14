/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.lang.SemanticNode;

public class ControlTag extends SemanticNode {
   public String docTypeName; // Usually the case insensitive string "doctype"
   public String docTypeValue; // Usually the value html

   public String toString() {
      return toLanguageString();
   }
}