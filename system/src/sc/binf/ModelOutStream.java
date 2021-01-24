/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.binf;

import sc.lang.ISemanticNode;
import sc.parser.IParseNode;
import sc.parser.Language;
import sc.parser.PString;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

public class ModelOutStream extends BinfOutStream {
   public ISemanticNode rootNode;
   public int currentListId = -1;

   public ModelOutStream(DataOutputStream out) {
      super(out);
   }

   public void serialize(ISemanticNode rootNode, String origFileExt, Language lang) {
      this.rootNode = rootNode;
      IParseNode pn = rootNode.getParseNode();
      if (pn != null) {
         lang = pn.getParselet().getLanguage();
      }
      initOutStream(origFileExt, lang);
      writeValue(rootNode);
   }

   public void writeValue(Object v) {
      if (v instanceof ISemanticNode) {
         ISemanticNode sn = (ISemanticNode) v;
         sn.serialize(this);
      }
      else if (PString.isString(v))
         writeString(v.toString());
      else if (v instanceof Boolean) {
         writeUInt((Boolean) v ? BinfConstants.BoolTrue : BinfConstants.BoolFalse);
      }
      else if (v == null)
         writeNull();
      else if (v instanceof Integer) {
         writeInt((Integer) v);
      }
      else if (v instanceof Long) {
         writeLong((Long) v);
      }
      else if (v instanceof Float) {
         writeFloat((Float) v);
      }
      else if (v instanceof Double) {
         writeDouble((Double) v);
      }
      else if (v instanceof Character) {
         writeChar((Character) v);
      }
      else {
         System.err.println("*** Unrecognized value type: " + v.getClass() + " for BinfOutStream - should implement ISemanticNode or be handled as a new primitive type");
      }
   }

}
