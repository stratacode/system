/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

/**
 * Implemented by language constructs which wrap a single statement - e.g. forStatement, synchronized, catchStatement
 */
public interface IStatementWrapper {
   Statement getWrappedStatement();

   String getFunctionEndString();
}
