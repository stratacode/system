/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.List;

/**
 * Groups blockStatement, try, catch, finally, switch, and template statement.
 *
 * It seems as though this should be an abstract class but making that change caused some ambiguity with
 * AST classes in the grammar so we are using an interface to target "anything that holds the list of statements"
 */
public interface IBlockStatement {
   List<Statement> getBlockStatements();

   IBlockStatement getEnclosingBlockStatement();

   /** Returns the first token to look for when starting the block in a parsed text - for static, try, etc. should not include the open brace */
   String getStartBlockToken();

   /** Returns the whole start string for toString or formatting purposes but will include the open brace */
   String getStartBlockString();

   String getEndBlockString();
}
