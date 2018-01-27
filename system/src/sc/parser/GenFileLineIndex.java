/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.lang.ISemanticNode;
import sc.lang.ISrcStatement;
import sc.lang.java.JavaModel;
import sc.layer.SrcEntry;
import sc.util.FileUtil;
import sc.util.IntStack;
import sc.util.StringUtil;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Used for storing the line mapping info for one file generated from a list of src files used to produce the generated file
 * It can be used in two different modes.  If you have already generated the file and have the gen-statements and the offsets
 * of those statements, you can provide the contents and an index is built to map the offsets to line numbers.  If you are
 * generating statements on the fly from a template and don't have the generated statement, you can just manually add the
 * mapping with the generated line number directly.  In this case, you must set numLines once you are finished and before you save
 * or use that - since the mappings may not include the total number of lines in the file and we need numLines in appendIndex()
 */
public class GenFileLineIndex implements Serializable, Cloneable {
   public String genFileName;

   // Used to build the index only when you are using offsets - not saved or available for the lookup operations
   public transient OffsetToLineIndex genFileLineIndex;

   // The list of src files this generated file was produced from - the index into this list is stored in the mapping array
   public ArrayList<SrcFileIndexEntry> srcFiles;
   public TreeMap<String,SrcFileIndexEntry> srcFilesByName;

   public String buildSrcDir;

   // [0] = id of src file, 1, 2 = gen file start, end  3, 4 src file start, end
   private final static int GENSZ = 5;

   public static boolean verbose = false;

   // The list of line mappings flattened out into an array for performance.  First index is the src file id (index into srcFiles)
   // then it's startGen/endGen startSrc/endSrc mappings from then on.  There's no specific indexing or order to this list but
   // in general it's from start to back (though some nested statements like anonymous inner classes cause problems with a strict ordering because the children are inside the parent)
   // TODO: use a 'ShortStack' or 'ByteStack' here when dealing with files under 256 or 16K lines to save space
   IntStack genLineMapping;
   public int numLines;

   transient int lastLine = -1;

   /**
    * When calling Statement.addToLineIndex with a parse-node that's being formatted and separately added to the file (as in JS code-gen)
    * this is used to store the statement we are currently formatting so we can compute the relative line number of each statement underneath
    * a compound statement, to add it to the known line number of the generated file
    */
   public transient String currentStatement;

   /**
    * Use this constructor when you generated the line number directly in Java code - i.e. you are generating the code line-by-line like for Js
    * like we do in the JSTypeTemplate conversion.
    */
   public GenFileLineIndex(String genFileName) {
      srcFiles = new ArrayList<SrcFileIndexEntry>();
      srcFilesByName = new TreeMap<String,SrcFileIndexEntry>();
      this.genFileName = genFileName;
   }

   /**
    * Use this constructor for the use case where generated files are created through 'transform' - i.e. we use offsets into
    * the generated file to determine the line number.
    */
   public GenFileLineIndex(String genFileName, String genFileContents, String buildSrcDir) {
      this(genFileName);
      genFileLineIndex = new OffsetToLineIndex(genFileContents);
      genLineMapping = new IntStack(genFileLineIndex.numLines);
      numLines = genFileLineIndex.numLines;
      this.buildSrcDir = buildSrcDir;
   }

   public void saveLineIndexFile(File lineIndexFile) {
      ObjectOutputStream os = null;
      try {
         os = new ObjectOutputStream(new FileOutputStream(lineIndexFile));
         os.writeObject(this);
      }
      catch (IOException exc) {
         System.out.println("*** can't save file line index: " + exc);
      }
      finally {
         FileUtil.safeClose(os);
      }
   }

   public static GenFileLineIndex readFileLineIndexFile(String srcName) {
      File lineIndexFile = getLineIndexFile(srcName);
      GenFileLineIndex idx = null;
      if (lineIndexFile.canRead()) {
         ObjectInputStream ois = null;
         FileInputStream fis = null;
         try {
            ois = new ObjectInputStream(fis = new FileInputStream(lineIndexFile));
            idx = (GenFileLineIndex) ois.readObject();
         }
         catch (InvalidClassException exc) {
            System.out.println("file line index - version changed: " + lineIndexFile);
            lineIndexFile.delete();
         }
         catch (IOException exc) {
            System.out.println("*** can't file line index: " + exc);
         }
         catch (ClassNotFoundException exc) {
            System.out.println("*** can't read file line index: " + exc);
         }
         finally {
            FileUtil.safeClose(ois);
            FileUtil.safeClose(fis);
         }
      }
      return idx;
   }

   public static class SrcFileIndexEntry implements Serializable {
      String absFileName;
      int id = 0; // integer offset into the srcFiles table and the value used in the genLineMapping array for this source file

      // Note: this is only used here when we are generating the table.  It's not saved or restored or needed while using the index
      public transient OffsetToLineIndex srcFileLineIndex;

      public String toString() {
         return "srcIndex ent: " + id + " for: " + absFileName;
      }
   }

   public void addMapping(SrcEntry srcFile, int genStart, int genEnd, int srcStart, int srcEnd) {
      if (genLineMapping == null)
         genLineMapping = new IntStack(256);

      // TODO: this happens for generated if (x) return; constructors where we have two statements on one line.  For now, just keep track of one of them
      // but since chrome can set breakpoints based on the column, we should keep track of the start column for both generated and source and support
      // column mappings in the source map for chrome.
      if (genStart <= lastLine)
         return;

      /* This happens for new Type() { ...} because right now the new expression starts and ends where it does so we are going to leave it because most likely the debugger needs to know the last line of the new and we search this list from the start for a mapping anyway.
      if (genStart < lastLine || genStart < 0)
         System.err.println("*** Invalid line mapping");
      */
      if (genEnd < genStart || srcEnd < srcStart)
         System.err.println("*** Invalid line start/end");
      SrcFileIndexEntry srcIndex = getSrcFileIndex(srcFile);
      genLineMapping.push(srcIndex.id);
      genLineMapping.push(genStart);
      genLineMapping.push(genEnd);
      genLineMapping.push(srcStart);
      genLineMapping.push(srcEnd);

      lastLine = genEnd;
   }

   public SrcFileIndexEntry getSrcFileIndex(SrcEntry srcFile) {
      String absName = srcFile.absFileName;
      SrcFileIndexEntry srcIndex = srcFilesByName.get(absName);
      if (srcIndex == null) {
         srcIndex = new SrcFileIndexEntry();
         srcIndex.absFileName = absName;
         srcIndex.srcFileLineIndex = new OffsetToLineIndex(srcFile.getFileAsString());
         srcIndex.id = srcFiles.size();
         srcFilesByName.put(absName, srcIndex);
         srcFiles.add(srcIndex);
      }
      return srcIndex;
   }

   /** Returns the list of line numbers in the generated source file for the source file specified at the given source file line number. */
   public List<Integer> getGenLinesForSrcLine(String absFileName, int lineNum) {
      SrcFileIndexEntry srcIndex = srcFilesByName.get(absFileName);
      if (srcIndex == null) {
         return null;
      }
      ArrayList<Integer> res = new ArrayList<Integer>();
      for (int i = 0; i < genLineMapping.size(); i += GENSZ) {
         // Is this generated mapping for the src file we are looking for?
         if (genLineMapping.get(i) == srcIndex.id) {
            if (lineNum >= genLineMapping.get(i+3) && lineNum <= genLineMapping.get(i+4)) {
               // For each line in the generated statement range we add a line in the src range
               // for genStartIndex to genEnd
               for (int j = genLineMapping.get(i+1); j <= genLineMapping.get(i+2); j++)
                  res.add(j);
            }
         }
      }
      if (verbose)
         System.out.println("*** genLinesForSrcLine - src file: " + absFileName + ":" + lineNum + " generated: " + genFileName + ": " + res);
      return res;
   }

   /** Returns the src file name and line number for the generated file's offset */
   public FileRangeRef getSrcFileForGenLine(int lineNum) {
      // TODO performance: could do binary search here
      for (int i = 0; i < genLineMapping.size(); i += GENSZ) {
         int genStart = genLineMapping.get(i + 1);
         int genEnd = genLineMapping.get(i + 2);
         if (genStart <= lineNum && genEnd >= lineNum) {
            int srcId = genLineMapping.get(i);
            FileRangeRef ref = new FileRangeRef();
            ref.absFileName = srcFiles.get(srcId).absFileName;
            ref.startLine = genLineMapping.get(i + 3);
            ref.endLine = genLineMapping.get(i + 4);
            if (verbose)
               System.out.println("*** getSrcFileForGenLine for gen: " + genFileName + ": " + lineNum + " returns src ref: " + ref.absFileName + ":" + ref.startLine);
            return ref;
         }
         if (lineNum < genEnd)
            break;
      }
      return null;
   }

   public void cleanUp() {
      genFileLineIndex = null;
   }

   public String dump(int min, int max) {
      StringBuilder sb = new StringBuilder();
      String lastFileName = null;
      sb.append(genFileName);
      sb.append("[");
      sb.append(numLines);
      sb.append("] showing ");
      sb.append(min);
      sb.append(" to ");
      sb.append(max);
      sb.append("\n");
      max = Math.min(max, numLines);
      for (int i = min; i < max; i++) {
         FileRangeRef ref = getSrcFileForGenLine(i);
         if (ref != null) {
            if (!StringUtil.equalStrings(lastFileName, ref.absFileName)) {
               if (i != min)
                  sb.append("]\n");
               sb.append("   Src file: " + ref.absFileName);
               lastFileName = ref.absFileName;
               sb.append(" [");
            }
            else
               sb.append(", ");
            sb.append(i);
            sb.append("<-");
            sb.append(ref.startLine);
            if (ref.startLine != ref.endLine) {
               sb.append(":");
               sb.append(ref.endLine);
            }
         }
      }
      sb.append("]\n");
      return sb.toString();
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("lineIndex for: " + genFileName + " from: " + srcFiles + " sz: " + (genLineMapping == null ? "null" : genLineMapping.size()));
      return sb.toString();
   }

   private int toVLQSigned(int val) {
      int bit = 0;
      if (val < 0) {
         bit = 1;
         val = -val;
      }
      return (val << 1) | bit;
   }

   private final static int VLQ_SHIFT = 5;
   private final static int VLQ_CONTINUE = 1 << VLQ_SHIFT;
   private final static int VLQ_MASK = VLQ_CONTINUE - 1;

   private static final char[] base64Chars = {
      'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
      'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
      'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm',
      'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z',
      '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '+', '/'
   };

   public String toBase64VLQ(int val) {
      String res = "";

      int vlqSigned = toVLQSigned(val);

      do {
         int digit = vlqSigned & VLQ_MASK;
         vlqSigned = vlqSigned >>> VLQ_SHIFT;

         if (vlqSigned != 0) {
            digit |= VLQ_CONTINUE;
         }
         res += base64Chars[digit];
      } while (vlqSigned > 0);
      return res;
   }

   public String getSourceMapping() {
      StringBuilder res = new StringBuilder();
      int lastGenLine = 1;
      int lastSrcIdx = 0;
      int lastSrcLine = 0;

      if (verbose) {
         System.out.println("Gen file: " + genFileName);
         System.out.println("   srcFiles: " + srcFiles.toString().replace(",", "\n      "));
      }

      if (genLineMapping == null)
         return "";

      for (int i = 0; i < genLineMapping.size(); i += GENSZ) {
         int srcIdx = genLineMapping.get(i);
         int startGen = genLineMapping.get(i+1);
         int endGen = genLineMapping.get(i+2);
         int startSrc = genLineMapping.get(i+3);
         int endSrc = genLineMapping.get(i+4);

         if (lastGenLine > startGen)
            System.out.println("*** Invalid mapping - gen lines in wrong order");

         // TODO: we should be adding the column to the table to support more than one statement on the same line
         if (startGen == lastGenLine)
            System.out.println("*** Invalid mapping - more than one mapping for same line");

         int curSrc = startSrc;
         for (int genLine = startGen; genLine <= endGen; genLine++) {
            while (lastGenLine < genLine) {
               res.append(";");
               lastGenLine++;
            }

            // source map line numbers are zero based
            int startSrcLineIndex = curSrc - 1;

            int srcIdxDelta = srcIdx - lastSrcIdx;
            int srcLineDelta = startSrcLineIndex - lastSrcLine;

            res.append(toBase64VLQ(0)); // Start column in gen source
            res.append(toBase64VLQ(srcIdxDelta));
            res.append(toBase64VLQ(srcLineDelta));
            res.append(toBase64VLQ(0)); // Start column in orig source
            lastSrcLine = startSrcLineIndex;
            lastSrcIdx = srcIdx;

            if (verbose)
               System.out.println(lastGenLine + "<-" + srcIdx + ":" + startSrc);

            // If there's more than one src line and more than one genLine, try to match them up, 1-1.
            if (curSrc < endSrc && genLine < endGen)
               curSrc++;
         }
      }
      return res.toString();
   }

   public String getSourceMappingJSON() {
      StringBuilder res = new StringBuilder();
      res.append("{");
      res.append("\"version\": 3,\n");
      res.append("\"file\":\"");
      res.append(FileUtil.getFileName(genFileName));
      res.append("\",\n");
      res.append("\"sources\": [");
      boolean first = true;
      for (SrcFileIndexEntry srcFile:srcFiles) {
         if (!first)
            res.append(", ");
         res.append("\"file://" + srcFile.absFileName + "\"");
         first = false;
      }
      res.append("],\n");
      res.append("\"mappings\":");
      res.append('"' + getSourceMapping() + '"');
      res.append("}");
      return res.toString();
   }

   public GenFileLineIndex clone() {
      try {
         GenFileLineIndex res = (GenFileLineIndex) super.clone();
         return res;
      }
      catch (CloneNotSupportedException exc) {}
      return null;
   }

   public void appendIndex(GenFileLineIndex toMerge) {
      int newNumLines = numLines == 0 ? toMerge.numLines : numLines + toMerge.numLines - 1;

      ArrayList<Integer> newSrcIds = new ArrayList<Integer>(toMerge.srcFiles.size());
      for (SrcFileIndexEntry mergeSrc : toMerge.srcFiles) {
         boolean found = false;
         for (int i = 0; i < srcFiles.size(); i++) {
            // This source file is already used
            if (mergeSrc.absFileName.equals(srcFiles.get(i).absFileName)) {
               newSrcIds.add(i);
               found = true;
               break;
            }
         }
         // New source file for this gen file
         if (!found) {
            int newId = srcFiles.size();
            newSrcIds.add(newId);
            SrcFileIndexEntry newSrc = new SrcFileIndexEntry();
            newSrc.absFileName = mergeSrc.absFileName;
            // Copy this over just in case we need to add additional entries in the combined index
            newSrc.srcFileLineIndex = mergeSrc.srcFileLineIndex;
            newSrc.id = newId;
            srcFiles.add(newSrc);
            srcFilesByName.put(newSrc.absFileName, newSrc);
         }
      }

      if (toMerge.genLineMapping != null) {
         int oldSize = genLineMapping == null ? 0 : genLineMapping.size();
         int startGenLine = numLines == 0 ? 0 : numLines - 1;
         if (genLineMapping == null)
            genLineMapping = toMerge.genLineMapping.clone();
         else
            genLineMapping.appendAll(toMerge.genLineMapping);
         int newSize = genLineMapping.size();
         for (int i = oldSize; i < newSize; i += GENSZ) {
            // Update the source index
            genLineMapping.set(i, newSrcIds.get(genLineMapping.get(i)));
            // Update the start and end generated line numbers
            genLineMapping.set(i + 1, genLineMapping.get(i + 1) + startGenLine);
            genLineMapping.set(i + 2, genLineMapping.get(i + 2) + startGenLine);
         }
      }

      numLines = newNumLines;
   }

   public static String getLineIndexFileName(String genSrcName) {
      return FileUtil.replaceExtension(genSrcName, "dbgIdx");
   }

   public static File getLineIndexFile(String genSrcName) {
      return new File(getLineIndexFileName(genSrcName));
   }

}
