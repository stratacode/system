/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

public class StatementProcessor {
   public boolean disableRefresh = false;
   public void processStatement(AbstractInterpreter interpreter, Object statement) {
      interpreter.processStatement(statement, false);
   }

}
