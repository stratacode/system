/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.js;

import sc.lang.SemanticNodeList;

import sc.lang.java.JavaModel;
import sc.lang.java.Statement;

public class JSModel extends JavaModel {
   public SemanticNodeList<Statement> sourceElements;
}
