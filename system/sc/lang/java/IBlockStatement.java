/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.List;

/**
 * It seems as though this should be an abstract class but making that change caused some ambiguity with
 * AST classes in the grammar so we are using an interface to target "anything that holds the list of statements"
 */
public interface IBlockStatement {
   List<Statement> getBlockStatements();

   IBlockStatement getEnclosingBlockStatement();
}
