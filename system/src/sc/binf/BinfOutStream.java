package sc.binf;

import sc.parser.Language;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

public abstract class BinfOutStream {
   public DataOutputStream out;
   public Language lang;

   public BinfOutStream(DataOutputStream out) {
      this.out = out;
   }

   public void initOutStream(String origFileExt) {
      try {
         if (lang != null) {
            if (origFileExt == null)
               origFileExt = lang.defaultExtension;
            else {
               Language extLang = Language.getLanguageByExtension(origFileExt);
               if (extLang != lang)
                  System.out.println("*** Warning: mismatching languages in model/parse stream!");
            }
         }
         if (origFileExt == null)
            throw new IllegalArgumentException("*** Error - no language specified for model/parse stream");
         else if (lang == null) {
            lang = Language.getLanguageByExtension(origFileExt);
            if (lang == null)
               throw new IllegalArgumentException("No language found for extension: " + origFileExt + " serializing model/parse stream");
         }

         out.writeUTF(origFileExt);
      }
      catch (IOException exc) {
         throw new UncheckedIOException(exc);
      }
   }

   /**
    *
    * To handle parseletId, and size - writes 1-5 bytes depending upon the value using the MSB of each byte as a flag
    * to indicate whether there's another byte
    * TODO: need to handle negative numbers or at least -1 here?  This should catch all of the common cases - size, parseletId etc. and we have read/writeInt for those others
    */
   public void writeUInt(int v) {
      if (v < 0)
         throw new IllegalArgumentException("Negative value passed to writeUInt!");
      try {
         if (v < 0x80) {
            out.writeByte(v);
         }
         else {
            int v1 = v >> 7;
            int next = (v & 0x7f) | 0x80;
            out.writeByte(next);

            if (v1 < 0x80) {
               out.writeByte(v1);
            }
            else {
               int v2 = v1 >> 7;
               next = (v1 & 0x7f) | 0x80;
               out.writeByte(next);

               if (v2 < 0x80) {
                  out.writeByte(v2);
               }
               else {
                  int v3 = v2 >> 7;
                  next = (v2 & 0x7f) | 0x80;
                  out.writeByte(next);

                  if (v3 < 0x80) {
                     out.writeByte(v3);
                  }
                  else {
                     int v4 = v3 >> 7;
                     next = (v3 & 0x7f) | 0x80;
                     out.writeByte(next);

                     if (v4 < 0x80) {
                        out.writeByte(v4);
                     }
                     else {
                        throw new IllegalArgumentException("writeUInt- value too large: " + v); // Should not be possible since we wrote 5*7 bits!
                     }
                  }
               }
            }
         }
      }
      catch (IOException exc) {
         throw new UncheckedIOException(exc);
      }
   }

   public void writeInt(int v) {
      try {
         out.writeByte(BinfConstants.IntId);
         out.writeInt(v);
      }
      catch (IOException exc) {
         throw new UncheckedIOException(exc);
      }
   }

   public void writeLong(long v) {
      try {
         out.writeByte(BinfConstants.LongId);
         out.writeLong(v);
      }
      catch (IOException exc) {
         throw new UncheckedIOException(exc);
      }
   }

   public void writeFloat(float v) {
      try {
         out.writeByte(BinfConstants.FloatId);
         out.writeFloat(v);
      }
      catch (IOException exc) {
         throw new UncheckedIOException(exc);
      }
   }

   public void writeDouble(double v) {
      try {
         out.writeByte(BinfConstants.DoubleId);
         out.writeDouble(v);
      }
      catch (IOException exc) {
         throw new UncheckedIOException(exc);
      }
   }

   public void writeChar(char v) {
      try {
         out.writeByte(BinfConstants.CharId);
         out.writeChar(v);
      }
      catch (IOException exc) {
         throw new UncheckedIOException(exc);
      }
   }

   public void writeNull() {
      try {
         out.writeByte(BinfConstants.NullId);
      }
      catch (IOException exc) {
         throw new UncheckedIOException(exc);
      }
   }

   public void writeString(String str) {
      try {
         out.writeByte(BinfConstants.StringId);
         out.writeUTF(str);
      }
      catch (IOException exc) {
         throw new UncheckedIOException(exc);
      }
   }
}
