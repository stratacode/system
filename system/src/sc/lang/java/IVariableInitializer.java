/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.bind.IChangeable;
import sc.obj.Constant;

public interface IVariableInitializer extends IVariable, IChangeable {
   Expression getInitializerExpr();

   @Constant
   String getInitializerExprStr();

   void updateInitializer(String op, Expression expr);

   @Constant
   String getOperatorStr();

   /** This is called on the property assignment after all instances of the property have been updated.  updateInitializer detects a binding has been removed, the initDynInstance method is called to update all of the instances, this is called to clear that state.   */
   void updateComplete();

}

