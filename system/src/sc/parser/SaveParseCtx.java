/*
 * Copyright (c) 2018. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.binf.ParseOutStream;

public class SaveParseCtx extends SaveRestoreCtx {
   // TODO: if we maintain the currentIndex here, we could use relative offsets during the save/restore so that writeUInt could write fewer bytes
   //int currentIndex;

   public ParseOutStream pOut;
}
