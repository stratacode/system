/*
 * Copyright (c) 2018. Jeffrey Vroom. All Rights Reserved.
 */

package sc.binf;

import sc.parser.*;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Used during the 'restore' process where we attach a ParseNode tree to a deserialized SemanticNode tree in the
 * case where the file has not changed.  The ParseInStream comes from a previously saved ParseOutStream which
 * stores the extra info needed to efficiently restore the ParseNode structure that's not contained in the SemanticNode tree
 */
public class ParseInStream extends BinfInStream {
   public ParseInStream(DataInputStream in) {
      super(in);
   }

   public Parselet getNextParselet(Parser parser, RestoreCtx rctx) {
      int pid = readUInt();
      if (pid == 0)
         return null;
      if (pid < BinfConstants.NumReservedIds) {
         System.err.println("*** Invalid parselet id");
         return null;
      }
      Parselet res = lang.getParseletById(pid);
      if (res == null)
         System.err.println("*** Invalid saved parselet id: " + pid);
      return res;
   }

   /** Corresponds to a saveChild call in ParseOutStream */
   public Object readChild(Parser parser, Parselet parselet, RestoreCtx rctx) {
      int pnType = readUInt();
      NestedParselet nestedParselet = null;
      switch (pnType) {
         case ParseOutStream.NullPN:
            return null;
         case ParseOutStream.ParentPNType:
            nestedParselet = (NestedParselet) getNextParselet(parser, rctx);
            // Fall through...
         case ParseOutStream.ParentPN:
            int sz = readUInt();
            if (nestedParselet == null)
               nestedParselet = (NestedParselet) parselet;
            int startIndex = parser.getCurrentIndex();
            if (nestedParselet == null)
               System.out.println("*** Error no parselet for parentPN");
            ParentParseNode ppn = nestedParselet.newParseNode(startIndex);
            for (int i = 0; i < sz; i++) {
               Parselet childParselet = nestedParselet.getChildParseletForIndex(i);
               if (childParselet == null) {
                  childParselet = getNextParselet(parser, rctx);
               }
               Object childPN = readChild(parser, childParselet, rctx);
               ppn.addGeneratedNode(childPN);
            }
            if (parser.getCurrentIndex() != startIndex + ppn.length())
               System.err.println("*** readChild - current index not updated properly during restore of parent");
            return ppn;
         case ParseOutStream.SinglePNType:
            nestedParselet = (NestedParselet) getNextParselet(parser, rctx);
            // Fall through...
         case ParseOutStream.SinglePN:
            if (nestedParselet == null) {
               if (!(parselet instanceof NestedParselet))
                  System.out.println("*** Not a nested parselet in restore!");
               nestedParselet = (NestedParselet) parselet;
            }
            // Can't rely on parselet.newParseNode because of a weird case - <statement> an indexedChoice which returns ParentParseNode most of the time but ParseNode for a dangling ";" which has no semantic value presently
            ParseNode spnNode = new ParseNode();
            spnNode.setParselet(nestedParselet);
            spnNode.setStartIndex(parser.getCurrentIndex());
            spnNode.value = readChild(parser, nestedParselet, rctx);
            return spnNode;
         case ParseOutStream.StringToken:
            // TODO: is this the best way to create a StringToken?  How will this work with the 'buffer' in Parser... will it ever be incremental?  seems like we should have the file as a byte array and point directly do that here to avoid pulling in extra stuff
            startIndex = readUInt();
            if (startIndex != parser.getCurrentIndex())
               System.err.println("*** String token restored to wrong location!");
            int len = readUInt();
            StringToken st = new StringToken(parser, startIndex, len);
            parser.changeCurrentIndex(startIndex + len);
            return st;
         case ParseOutStream.String:
            try {
               String res = in.readUTF();
               parser.changeCurrentIndex(parser.getCurrentIndex() + res.length());
               return res;
            }
            catch (IOException exc) {
               throw new UncheckedIOException(exc);
            }
         default:
            throw new IllegalArgumentException("*** Unrecognized value in readChild");
      }
   }

   public void close() {
      try {
         in.close();
      }
      catch (IOException exc) {
         System.err.println("** Unable to close ParseInStream");
      }
   }
}
