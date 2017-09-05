/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

/**
 * Implemented by types like method, catch, which wrap a BlockStatement
 */
public interface IBlockStatementWrapper {
   BlockStatement getWrappedBlockStatement();
}
