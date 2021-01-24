/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.binf;

import sc.lang.ISemanticNode;
import sc.lang.SemanticNodeList;
import sc.lang.java.PrimitiveType;
import sc.parser.*;
import sc.type.*;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

public abstract class BinfInStream {
   public Language lang;
   DataInputStream in;

   public BinfInStream(DataInputStream in) {
      this.in = in;
   }

   public void initStream(Language lang) {
      try {
         String ext = in.readUTF();
         if (lang == null)
            this.lang = Language.getLanguageByExtension(ext);
         else
            this.lang = lang;
         if (this.lang == null)
            throw new IllegalArgumentException("No language registered for saved model with extension: " + ext);
         // TODO: should we validate the parseletId mappings by storing a hash of some kind - so we can detect when they change and produce a reasonable error?
      }
      catch (IOException exc) {
         throw new UncheckedIOException(exc);
      }
   }

   public int readUInt() {
      try {
         int v0 = in.readUnsignedByte();
         if (v0 < 0x80)
            return v0;

         v0 = v0 & ~0x80;

         int v1 = in.readUnsignedByte();
         if (v1 < 0x80)
            return v0 | (v1 << 7);

         v1 = v1 & ~0x80;

         int v2 = in.readUnsignedByte();
         if (v2 < 0x80)
            return v0 | (v1 << 7) | (v2 << 14);

         v2 = v2 & ~0x80;

         int v3 = in.readUnsignedByte();
         if (v3 < 0x80)
            return v0 | (v1 << 7) | (v2 << 14) | (v3 << 21);

         v3 = v3 & ~0x80;

         // Adding one extra byte so we can write all 31 bits of the original int
         int v4 = in.readUnsignedByte();
         return v0 | (v1 << 7) | (v2 << 14) | (v3 << 21) | (v4 << 28);
      }
      catch (IOException exc) {
         throw new UncheckedIOException(exc);
      }
   }

}
