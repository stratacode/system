/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.List;

/**
 * Supports access to the type declaration's statements for both new expression and regular type declarations
 */
public interface IClassBodyStatement {
   List<Statement> getBodyStatements();
}
