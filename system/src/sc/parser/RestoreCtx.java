/*
 * Copyright (c) 2018. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.binf.ParseInStream;

public class RestoreCtx extends SaveRestoreCtx {
   // Optional: an input stream to cache info to speed up the restore operation
   public ParseInStream pIn;

   public RestoreCtx() {
   }

   public RestoreCtx(ParseInStream pIn) {
      this.pIn = pIn;
   }

   public String toString() {
      return "restore arrIndex: " + arrIndex + (arrElement ? " - arrElement " : "") + (listProp ? " - processing list property " : "") + super.toString();
   }
}
