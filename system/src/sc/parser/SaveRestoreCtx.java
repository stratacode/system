/*
 * Copyright (c) 2018. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

/**
 * Used by both RestoreCtx and SaveParseCtx - includes info we use to navigate the semanticValue as we
 * walk down the tree to determine what info is missing from the semantic value that we need from the file itself
 */
public abstract class SaveRestoreCtx extends BaseRebuildCtx {
   public int arrIndex;
   public boolean arrElement;
   public boolean listProp;
}
