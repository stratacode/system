/*
 * Copyright (c) 2018. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

public class RestoreCtx extends BaseRebuildContext {
   int arrIndex;
   boolean arrElement;
   boolean listProp;

   public String toString() {
      return "restore arrIndex: " + arrIndex + (arrElement ? " - arrElement " : "") + (listProp ? " - processing list property " : "") + super.toString();
   }
}
