/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.util.FileUtil;
import sc.util.StringUtil;

import java.io.IOException;
import java.io.Reader;
import java.util.*;

/**
 * This is the basic parser class. For the most part external users won't see this - they
 * use the Language class.  This class maintains the input buffer and the location of the current parsed
 * position in the input buffer.  It also stores the current set of errors.
 */
public class Parser implements IString {
   public final static boolean ENABLE_STATS = false;

   Language language;

   /** For languages like HTML which are not context free grammar, the parse nodes maintain additional information, like the current tag stack, used for matching the grammar */
   SemanticContext semanticContext;

   int currentStreamPos = 0; // The character index of the last char read from the input stream
   Reader inputReader; // Used to fetch characters to parse

   int lastStartIndex = 0;

   Parselet currentParselet;

   public static final int DEFAULT_BUFFER_SIZE = 1024; // chunk size to grow inputBuffer

   int currentBufferPos; 
   // Stores unaccepted characters in the input stream.  Grows as large as necessary in 
   // BUFFER CHUNK sizes.  
   char [] inputBuffer;
   int bufSize; // number of chars in inputBuffer which are populated.

   int numAccepted;   // Number of accepted characters in the input stream.  
   int currentIndex; // The parser's current position in the stream

   public boolean eof = false; // has the input reader returned EOF yet - use atEOF to tell if we're at EOF

   // The start and end positions in which the most specific error occurred
   public int currentErrorEndIndex = -1;
   public int currentErrorStartIndex = -1;

   // this will store any errors which are generated.  Errors are cleared out when
   // the index is advanced past the point in which the error occurred.
   public List<ParseError> currentErrors = new ArrayList<ParseError>();

   int inProgressCount = 0;

   // Stack of parselet states which are being processed on the parse stack
   Stack<ParseletState> inProgress = new Stack<ParseletState>();

   public static int testedNodes = 0;
   public static int matchedNodes = 0;

   // When we hit EOF, if this is true we'll process the current semantic value for any matched slots
   // and put it in the error.partialValue property.  This is useful when you use the parser for incremental
   // input, such as for command line completion.
   public boolean enablePartialValues = false;

   /** For some small grammers, we may want to populate the parent instance rather than create a new instance from the grammar */
   public Object populateInst = null;

   int traceCt = 0; // # of nodes we are currently tracing for debug information
   int negatedCt = 0; // # of parselets in the stack with the negated flag - don't store errors while negated

   // When this is true, we do not produce a semantic value - just perform the match part.
   public boolean matchOnly = false;

   public Parser(Language l, Reader reader, int bufSize) {
      inputBuffer = new char[bufSize];
      language = l;
      inputReader = reader;
   }

   public Parser(Language l, Reader reader) {
      this(l, reader, DEFAULT_BUFFER_SIZE);
   }

   private void fillBuffer() {
      if (numAccepted > currentBufferPos)
      {
         int numToShift = numAccepted - currentBufferPos;
         if (bufSize > numToShift)
            System.arraycopy(inputBuffer, numToShift, inputBuffer, 0, bufSize - numToShift);
         currentBufferPos += numToShift;
         bufSize -= numToShift;
      }
      if (inputBuffer.length == bufSize || eof)
         return;
      try
      {
         int numRead = inputReader.read(inputBuffer, bufSize, inputBuffer.length - bufSize);
         if (numRead == -1)
            eof = true;
         else 
            bufSize += numRead;
      }
      catch (IOException exc)
      {
         parseError(currentParselet, "IO Error: {0}", currentIndex,currentIndex+bufSize, exc.toString());
         eof = true;
      }
   }

   private void growBuffer() {
      char [] newBuf = new char[inputBuffer.length + DEFAULT_BUFFER_SIZE];
      System.arraycopy(inputBuffer, 0, newBuf, 0, bufSize);
      inputBuffer = newBuf;
   }

   public char charAt(int pos) {
      return getInputChar(pos);
   }

   public char peekInputChar(int pos) {
      return getInputChar(currentIndex + pos);
   }

   public char getInputChar(int pos) {
      int relIx = pos - currentBufferPos;
      if (relIx < 0)
         throw new IllegalArgumentException("Attempt to access character before the current buffer position");

      if (relIx < bufSize)
         return inputBuffer[relIx];
      else
      {
         if (!eof) {
            while (relIx >= inputBuffer.length)
               growBuffer();
            fillBuffer();
         }

         if (relIx < bufSize)
            return inputBuffer[relIx];

         eof = true;
         return '\0';
      }
   }

   public String getInputString(int index, int len) {
      if (len < 0)
         System.out.println("*ugh");

      char[] buf = new char[len];
      for (int i = 0; i < len; i++) {
         char inputChar = getInputChar(index + i);
         if (inputChar == '\0') {
            if (i == 0)
               return null;
            char [] buf2 = new char[i];
            System.arraycopy(buf, 0, buf2, 0, i);
            return new String(buf2);
         }
         buf[i] = inputChar;
      }
      return new String(buf);
   }

   public IString substring(int index, int endIndex) {
      return PString.toIString(getInputString(index, endIndex - index));
   }

   public IString substring(int index) {
      return substring(index, length());
   }

   public boolean startsWith(CharSequence other) {
      if (other == null)
         return false;

      if (other.length() > length())
         return false;

      for (int i = 0; i < other.length(); i++)
         if (charAt(i) != other.charAt(i))
            return false;
      return true;
   }

   public int length() {
      while (!eof)
         growBuffer();

      return currentBufferPos + bufSize;
   }

   public Object parseStart(Parselet parselet) {
      currentErrors.clear();
      currentErrorStartIndex = currentErrorEndIndex = -1;
      currentIndex = 0;
      currentStreamPos = 0; // The character index of the last char read from the input stream
      numAccepted = 0;
      eof = false;

      semanticContext = language.newSemanticContext(parselet, null);

      Object result = parseNext(parselet);

      // Always return the error which occurred furthers into the stream.  Probably should return the whole
      // list of these errors if there is more than one.
      if ((result == null || result instanceof ParseError) && currentErrors != null)
      {
         if (language.debug)
         {
            for (int i = 0; i < currentErrors.size(); i++)
               System.out.println("Errors: " + i + ": " + currentErrors.get(i));
         }
         return wrapErrors();
      }
      return result;
   }

   public ParseError wrapErrors() {
      if (currentErrors.size() == 1)
         return currentErrors.get(0);
      else {
         if (currentErrors.size() < 3)
            return new ParseError("Multiple errors: {0}", ParseUtil.wrapArg(getErrorStrings(currentErrors)), currentErrorStartIndex, currentErrorEndIndex);
         else
            return new ParseError(null, null, currentErrorStartIndex, currentErrorEndIndex);
      }
   }

   private String getErrorStrings(List<ParseError> currentErrors)
   {
      StringBuffer sb = new StringBuffer();
      for (int i = 0; i < currentErrors.size(); i++)
      {
         ParseError pe = currentErrors.get(i);
         // Always pull at least one error in
         if (pe.parselet != null && !pe.parselet.getReportError() && (sb.length() >= 0 || i != currentErrors.size()-1))
             continue;
         sb.append(FileUtil.LINE_SEPARATOR + "   ");
         sb.append(pe.errorString());
      }
      return sb.toString();
   }

   /**
    * This method is called by recognizers which are nested.
    * it will do all of the work necessary before/after calling the recognizer.recognize method
    * (i.e. rolling back changes if there are errors, dealing with the parse error
    * returned - add it to the parser).
    */
   public Object parseNext(Parselet parselet) {
      Parselet saveParselet = null;
      int saveLastStartIndex = -1;
      Object value;

      /*
      if ((language.debug || parselet.trace) && !language.debugSuccessOnly)
         System.out.println(indent(inProgressCount) + "Next: " + getLookahead(8) + " testing rule: " + parselet.toString());
      */

      boolean disableDebug = false;
      if (parselet.negated)
         negatedCt++;
      if (parselet.trace) {
         if (traceCt == 0 && !language.debug) {
            disableDebug = true;
            language.debug = true;
         }
         traceCt++;
      }
      try {
         saveLastStartIndex = lastStartIndex;
         lastStartIndex = currentIndex;
         inProgressCount++;
         saveParselet = currentParselet;
         currentParselet = parselet;

         value = parselet.parse(this);

         if (ENABLE_STATS) {
            parselet.attemptCount++;
            testedNodes++;

            if (!(value instanceof ParseError) && value != null) {
               parselet.successCount++;
               matchedNodes++;
            }
         }
      }
      finally {
         inProgressCount--;
         currentParselet = saveParselet;
         lastStartIndex = saveLastStartIndex;

         if (disableDebug)
            language.debug = false;
         if (parselet.trace)
            traceCt--;
         if (parselet.negated)
            negatedCt--;
      }

      /*
      if (language.debug || parselet.trace) {
         if (value instanceof ParseError) {
            if (!language.debugSuccessOnly)
               System.out.println(indent(inProgressCount) + "Error" +
                       (parselet.getName() != null ? "<" + parselet.getName() + ">: " : ": ") +
                       value + " next: " + getLookahead(8));
         }
         else
            System.out.println(indent(inProgressCount) + "Result" +
                    (parselet.getName() != null ? "<" + parselet.getName() + ">:" : ":") +
                    ParseUtil.escapeObject(value) + " next: " + getLookahead(8));
      }
      */
      return value;
   }

   private String getLookahead(int num) {
      String lookahead = ParseUtil.escapeString(substring(currentIndex, currentIndex + num));
      if (lookahead == null)
         lookahead = "<EOF>";
      else if (lookahead.length() == num)
         lookahead += "...";

      return lookahead;
   }

   private static String indent(int ct) {
      StringBuffer sb = new StringBuffer();
      for (int i = 0; i < ct; i++)
         sb.append("   ");
      return sb.toString();
   }

   /** Called when we need to unwind the parser to an earlier or later state */
   public void changeCurrentIndex(int ix) {
      currentIndex = ix;
   }

   public int getCurrentIndex() {
      return currentIndex;
   }

   /** Returns the starting position for the active expression */
   public int getLastStartIndex() {
      return lastStartIndex;
   }

   public ParseError parseError(String errorCode)
   {
      return parseError(currentParselet, errorCode, (Object[])null);
   }

   public ParseError parseError(Parselet parselet, String errorCode, Object... args)
   {
      return parseError(parselet, errorCode, currentIndex, currentIndex, args);
   }

   public ParseError parseError(Parselet parselet, Parselet childParselet, String errorCode, Object... args)
   {
      return parseError(parselet, childParselet, errorCode, currentIndex, currentIndex, args);
   }

   public ParseError parseError(Parselet parselet, String errorCode, int start, int end, Object... args)
   {
      return parseError(parselet, null, null, errorCode, start, end, args);
   }

   public static boolean isBetterError(int currentStart, int currentEnd, int newStart, int newEnd, boolean replace) {
      if (currentStart == -1)
         return true;

      if (replace)
         return newEnd > currentEnd || (newEnd == currentEnd && newStart <= currentStart);
      else
         return newEnd > currentEnd || (newEnd == currentEnd && newStart < currentStart);
   }

   public ParseError parseError(Parselet parselet, Object partialValue, Parselet childParselet, String errorCode, int start, int end, Object... args) {
      // Don't bother to create an error object if we are in the midst of a negated expression.  Also,
      // don't add it to the global list of errors.
      if (negatedCt > 0)
         return PARSE_NEGATED_ERROR;
      //if (parselet.reportError && (currentErrorEndIndex == -1 || (end > currentErrorEndIndex || (end == currentErrorEndIndex && start <= currentErrorStartIndex)))) {

      // Keep track of only the errors which made the most progress during the parse.  If the end is greater, that is
      // a better error than the one we have no matter what.  But if they end at the same spot, this error is only better
      // if it consumed more text than the previous one.
      if (parselet.reportError && isBetterError(currentErrorStartIndex, currentErrorEndIndex, start, end, true)) {
         if (end > currentErrorEndIndex || start < currentErrorStartIndex) {
            currentErrors.clear();
            currentErrorEndIndex = end;
            currentErrorStartIndex = start;
         }
         ParseError e = new ParseError(parselet, errorCode, args, start, end);
         e.partialValue = partialValue;
         if (parselet.reportError || currentErrors.size() == 0) {
            int lastIx = currentErrors.size();
            if (lastIx != 0) {
               ParseError lastError = currentErrors.get(lastIx-1);
               if (lastError.parselet == childParselet)
                  currentErrors.set(lastIx-1, e);
               else
                  addError(e);
            }
            else
               addError(e);
         }
         return e;
      }
      // These all require specific errors
      else if (language.debug || parselet.trace || enablePartialValues) {
         ParseError e = new ParseError(parselet, errorCode, args, start, end);
         e.partialValue = partialValue;
         return e;
      }
      return PARSE_ERROR_OVERRIDDEN;
   }

   private void addError(ParseError newError) {
      currentErrors.add(newError);
   }

   public Object parseResult(Parselet parselet, Object value, boolean skipSemanticValue) {
      if (value == null)
         return null;

      // Don't wrap if we are skipping this node and avoid double-wrapping for recursive definitions.
      if ((parselet.getSkip() && !parselet.needsChildren()) || ((value instanceof IParseNode) && (((IParseNode)value).getParselet()) == parselet))
         return value;

      ParseNode node = new ParseNode();
      node.setParselet(parselet);
      node.value = value;
      node.startIndex = lastStartIndex;
      if (!skipSemanticValue)
         node.setSemanticValue(ParseUtil.nodeToSemanticValue(value));
      return node;
   }

   static final ParseletState STATE_PENDING = new ParseletState(-1, null, new ParseError("Expression's value is pending resolution of other rules", null, -1, -1));
   static final ParseError PARSE_ERROR_OVERRIDDEN = new ParseError("Error less specific than previous error", null, -1, -1);
   static final ParseError PARSE_NEGATED_ERROR = new ParseError("Match failed while negated", null, -1, -1);

   public void getChars(int srcBegin, int srcEnd, char dst[], int dstBegin) {
      int dstOffset = dstBegin - srcBegin;
      for (int i = srcBegin; i < srcEnd; i++)
         dst[i-dstOffset] = charAt(i);
   }

   public String toString() {
      return language.toString() + " parsing: " + getLookahead(8);
   }

   public int peekInputStr(ArrString expectedValue, boolean negated) {
      int relIx = currentIndex - currentBufferPos;
      if (relIx < 0)
         throw new IllegalArgumentException("Attempt to access character before the current buffer position");

      char[] expectedBuf = expectedValue.buf;
      int expectedLen = expectedBuf.length;
      if (relIx+expectedLen <= bufSize) {
         for (int i = 0; i < expectedLen; i++) {
            if (inputBuffer[relIx++] != expectedBuf[i])
               return negated ? 0 : 1;
         }
         return !negated ? 0 : 1;
      }
      else {
         if (!eof) {
            while (relIx + expectedLen >= inputBuffer.length)
               growBuffer();
            fillBuffer();
         }

         if (relIx + expectedLen <= bufSize)
            return peekInputStr(expectedValue, negated);

         eof = true;
         return negated ? 0 : 2;
      }
   }

   /** 
    * Created when we encounter a left-recursion rule - i.e. we need to evaluate 
    * an expression as the first step in evaluating itself.  We handle this by recording
    * the value of everything we can compute on the first pass, then keep re-evaluating it
    * each time replacing the old value of the expression by its new value in the next pass.
    * eventually we stop making progress and we know we are done. 
    */
   private static class LeftRecursionState {
     LeftRecursionState(ParseletState ps) {
        rootParselet = ps.parselet;
     }
     Object seedValue;
     boolean seedValueSet = false;
     // The first parselet where we notice a left recursion
     Parselet rootParselet;
     // The list of all parselet states in the path from the head node to the point of recursion.
     // When trying to choose the value for a left-recursed node, we need to regenerate the
     // values of all of these nodes. 
     Set<Parselet> involved = new HashSet<Parselet>(); // TODO TreeSet with Identity comparator
     // When we are resolving the values of left-recursed nodes, we want to evaluate each involved
     // node at most once.   
     Set<Parselet> toEval;
   }

   private static class ParseletState
   {
      ParseletState(int ix, Parselet p, Object v)
      {
         index = ix;
         parselet = p;
         leftRecursion = null;
         value = v;
      }

      ParseletState(int ix, Parselet p)
      {
         index = ix;
         parselet = p;
         leftRecursion = null;
         value = this; // Using this as a sentinel value so we recognize recursive calls
      }

      int index;
      int endIndex;
      Object value;
      Parselet parselet;
      LeftRecursionState leftRecursion;

      // Used for the states we put into the resultCache.  We store a linked list
      // of the states... this is a way to implement what hopefully is a small set of
      // parselet states for each position in the file.
      ParseletState next;
   }

   // TODO: ifdef ENABLE_STATS

   public static String getStatInfo(Parselet startParselet) {
      StringBuilder sb = new StringBuilder();
      internalGetStatInfo(startParselet, 0, startParselet.name, new HashMap<Parselet,String>(), sb);
      return sb.toString();
   }

   private static void internalGetStatInfo(Parselet parselet, int level, String pathName, Map<Parselet,String> visited, StringBuilder sb) {
      String inProgressName = visited.get(parselet);
      if (inProgressName != null) {
         /*
         sb.append(indent(level));
         sb.append(inProgressName);
         sb.append(FileUtil.LINE_SEPARATOR);
         */
      }
      else {
         sb.append(indent(level));

         if (parselet.isNamed())
            inProgressName = parselet.name;
         else
            inProgressName = pathName;

         sb.append(inProgressName);
         visited.put(parselet, inProgressName);

         if (parselet.attemptCount != 0) {
            sb.append(" attempts: ");
            sb.append(parselet.attemptCount);
            sb.append(" success: ");
            sb.append(parselet.successCount);

            sb.append(" matched: ");
            sb.append(StringUtil.formatFloat(100.0 * parselet.successCount / parselet.attemptCount));
            sb.append("%");
         }

         if (parselet.generatedBytes != 0) {
            sb.append(" generatedBytes: ");
            sb.append(parselet.generatedBytes);
            sb.append(" failedProgressBytes: ");
            sb.append(parselet.failedProgressBytes);

            sb.append(" generation success: ");
            sb.append(StringUtil.formatFloat(100.0 * parselet.generatedBytes / (parselet.generatedBytes + parselet.failedProgressBytes)));
            sb.append("%");
         }

         sb.append(FileUtil.LINE_SEPARATOR);

         if (parselet instanceof NestedParselet) {
            NestedParselet parent = (NestedParselet) parselet;
            for (int i = 0; i < parent.parselets.size(); i++) {
               internalGetStatInfo(parent.parselets.get(i), level+1, String.valueOf(inProgressName + "." + i), visited, sb);
            }
         }
      }
   }

   public CharSequence subSequence(int start, int end) {
      return substring(start, end);
   }

   public boolean atEOF() {
      return peekInputChar(0) == '\0';
   }
}
