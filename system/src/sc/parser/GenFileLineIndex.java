/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.layer.SrcEntry;
import sc.util.FileUtil;
import sc.util.IntStack;
import sc.util.StringUtil;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Used for storing the line mapping info for one file generated from a list of src files used to produce the generated file
 */
public class GenFileLineIndex implements Serializable {
   public String genFileName;

   // Used to build the index only - not saved or available for the lookup operations
   public transient OffsetToLineIndex genFileLineIndex;

   // The list of src files this generated file was produced from - the index into this list is stored in the mapping array
   public ArrayList<SrcFileIndexEntry> srcFiles;
   public TreeMap<String,SrcFileIndexEntry> srcFilesByName;

   public String buildSrcDir;

   // 0 - id of src file, 1/2 - gen file start/end  3/4 src file start/end
   private final static int GENSZ = 5;

   public static boolean verbose = true;

   // The list of line mappings flattened out into an array for performance.  First index is the src file id (index into srcFiles)
   // then it's startGen/endGen startSrc/endSrc mappings from then on.  There's no specific indexing or order to this list but
   // in general it's from start to back (though some nested statements like anonymous inner classes cause problems with a strict ordering because the children are inside the parent)
   // TODO: use a 'ShortStack' or 'ByteStack' here - less space for most files
   IntStack genLineMapping;
   int numLines;

   transient int lastLine = -1;

   public GenFileLineIndex(String genFileName, String genFileContents, String buildSrcDir) {
      genFileLineIndex = new OffsetToLineIndex(genFileContents);
      srcFiles = new ArrayList<SrcFileIndexEntry>();
      srcFilesByName = new TreeMap<String,SrcFileIndexEntry>();
      genLineMapping = new IntStack(genFileLineIndex.numLines);
      numLines = genFileLineIndex.numLines;
      this.buildSrcDir = buildSrcDir;
      this.genFileName = genFileName;
   }

   public static class SrcFileIndexEntry implements Serializable {
      String absFileName;
      int id = 0;
      public transient OffsetToLineIndex srcFileLineIndex;

      public String toString() {
         return "srcIndex ent: " + id + " for: " + absFileName;
      }
   }

   public void addMapping(SrcEntry srcFile, int genStart, int genEnd, int srcStart, int srcEnd) {
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
         srcIndex.srcFileLineIndex = new OffsetToLineIndex(FileUtil.getFileAsString(absName));
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
}
