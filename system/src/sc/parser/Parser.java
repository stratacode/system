/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.binf.ParseInStream;
import sc.lang.ISemanticNode;
import sc.util.FileUtil;
import sc.util.StringUtil;

import java.io.IOException;
import java.io.Reader;
import java.util.*;

/**
 * This is the parser class which holds state for a given parse of a file or buffer.  Usually you access this
 * class via Language.  Parser maintains the input buffer and the location of the current parsed
 * position in the input buffer.  It also stores the current set of errors, parsing statistics, adn the current parselet.
 */
public class Parser implements IString {
   // Java won't include code behind these flags
   public final static boolean ENABLE_STATS = false;

   private final static boolean ENABLE_RESULT_TRACE = false;

   Language language;

   /** For languages like HTML which are not context free grammar, the parse nodes maintain additional information, like the current tag stack, used for matching the grammar */
   public SemanticContext semanticContext;

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

   /** Nodes in the parse tree like ; and ) can be marked as errors and then skipped in the actual parse.  */
   public List<ParseError> skippedErrors = new ArrayList<ParseError>();

   int inProgressCount = 0;

   // Stack of parselet states which are being processed on the parse stack
   //Stack<ParseletState> inProgress = new Stack<ParseletState>();

   public static int testedNodes = 0;
   public static int matchedNodes = 0;

   // When we hit EOF, if this is true we'll process the current semantic value for any matched slots
   // and put it in the error.partialValue property.  This is useful when you use the parser for incremental
   // input, such as for command line completion.
   public boolean enablePartialValues = false;

   /** For some small grammars, we may want to populate the parent instance rather than create a new instance from the grammar */
   public Object populateInst = null;

   int traceCt = 0; // # of nodes we are currently tracing for debug information
   int negatedCt = 0; // # of parselets in the stack with the negated flag - don't store errors while negated

   int totalParseCt = 0;
   int reparseCt = 0; // For reparse operations the # of nodes that are actually reparsed
   int reparseSkippedCt = 0; // For reparse operations the # of nodes that are the same

   // When this is true, we do not produce a semantic value - just perform the match part.
   public boolean matchOnly = false;

   HashMap<Integer,ParseletState> resultCache = null;

   public Parser(Language l, char[] inputArray) {
      language = l;
      inputBuffer = inputArray;
      bufSize = inputArray.length;
      eof = true;
   }

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
         parseError(currentParselet, null, null, "IO Error: {0}", currentIndex,currentIndex+bufSize, exc.toString());
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

   public final int length() {
      int ct = 0;
      while (!eof) {
         if (peekInputChar(ct++) == '\0')
            break;
      }
      return currentBufferPos + bufSize;
   }

   private void initParseState(Parselet parselet) {
      currentErrors.clear();
      currentErrorStartIndex = currentErrorEndIndex = -1;
      currentIndex = 0;
      currentStreamPos = 0; // The character index of the last char read from the input stream
      numAccepted = 0;
      // If inputReader == null, inputBuffer points to the complete source array
      if (inputReader != null)
         eof = false;

      semanticContext = language.newSemanticContext(parselet, null);
   }

   public final Object parseStart(Parselet parselet) {
      initParseState(parselet);

      Object result = parseNext(parselet);

      // Always return the error which occurred furthers into the stream.  Probably should return the whole
      // list of these errors if there is more than one.
      if ((result == null || result instanceof ParseError) && currentErrors != null) {
         if (enablePartialValues && result != null) {
            ParseError err = (ParseError) result;
            if (err.partialValue != null && err.endIndex != length()) {
               if (language.debug)
                  System.out.println("Partial value did not consume all of file - wrapping error node to parent element!");

            }
            // Sometimes the produced partial value is say 0-1273 and the currentErrors all are 1274-1274.  We always choose
            // an error that makes it further but in this case, the error with the information is the one returned in the partial value so
            // we are adding it back in.
            if (!currentErrors.contains(err))
               currentErrors.add(err);
         }
         if (language.debug) {
            for (int i = 0; i < currentErrors.size(); i++)
               System.out.println("Errors: " + i + ": " + currentErrors.get(i));
         }
         result = wrapErrors();
      }

      if (ENABLE_STATS) {
         System.out.println("*** cache stats:");
         System.out.println(getCacheStats());
      }
      return result;
   }

   public final Object reparseStart(Parselet parselet, Object oldParseNode, DiffContext dctx) {
      initParseState(parselet);

      Object result = reparseNext(parselet, oldParseNode, dctx, false, null);

      // Always return the error which occurred furthers into the stream.  Probably should return the whole
      // list of these errors if there is more than one.
      if ((result == null || result instanceof ParseError) && currentErrors != null) {
         if (language.debug) {
            for (int i = 0; i < currentErrors.size(); i++)
               System.out.println("Errors: " + i + ": " + currentErrors.get(i));
         }
         result = wrapErrors();
      }
      return result;
   }

   public final Object restoreStart(Parselet parselet, ISemanticNode oldModel, ParseInStream pIn) {
      initParseState(parselet);

      Object result = restoreNext(parselet, oldModel, new RestoreCtx(pIn), false);

      // Always return the error which occurred furthers into the stream.  Probably should return the whole
      // list of these errors if there is more than one.
      if ((result == null || result instanceof ParseError) && currentErrors != null) {
         if (enablePartialValues && result != null) {
            ParseError err = (ParseError) result;
            if (err.partialValue != null && err.endIndex != length()) {
               if (language.debug)
                  System.out.println("Partial value did not consume all of file - wrapping error node to parent element!");

            }
            // Sometimes the produced partial value is say 0-1273 and the currentErrors all are 1274-1274.  We always choose
            // an error that makes it further but in this case, the error with the information is the one returned in the partial value so
            // we are adding it back in.
            if (!currentErrors.contains(err))
               currentErrors.add(err);
         }
         if (language.debug) {
            for (int i = 0; i < currentErrors.size(); i++)
               System.out.println("Errors: " + i + ": " + currentErrors.get(i));
         }
         result = wrapErrors();
      }

      if (ENABLE_STATS) {
         System.out.println("*** cache stats:");
         System.out.println(getCacheStats());
      }
      return result;
   }

   public final ParseError wrapErrors() {
      if (currentErrors.size() == 1)
         return currentErrors.get(0);
      else {
         return new ParseError(ParseError.MULTI_ERROR_CODE, currentErrors.toArray(), currentErrorStartIndex, currentErrorEndIndex);
      }
   }

   private final String getErrorStrings(List<ParseError> currentErrors) {
      StringBuffer sb = new StringBuffer();
      for (int i = 0; i < currentErrors.size(); i++) {
         ParseError pe = currentErrors.get(i);
         // Always pull at least one error in
         if (pe.parselet != null && !pe.parselet.getReportError() && (sb.length() >= 0 || i != currentErrors.size()-1))
             continue;
         sb.append(FileUtil.LINE_SEPARATOR + "   ");
         sb.append(pe.errorString());
      }
      return sb.toString();
   }

   private static final Object NO_MATCHED_RESULT = new Object();

   static class ParsedState {
      Parselet parselet;
      Object value;
      ParsedState next;
   }

   private ParseletState findMatchingState(ParseletState state, Parselet parselet) {
      if (state.parselet == parselet)
         return state;
      else if (state.next != null)
         return findMatchingState(state.next, parselet);
      return null;
   }

   /**
    * This method is called by recognizers which are nested.
    * it will do all of the work necessary before/after calling the recognizer.recognize method
    * (i.e. rolling back changes if there are errors, dealing with the parse error
    * returned - add it to the parser).
    */
   public final Object parseNext(Parselet parselet) {
      Parselet saveParselet = null;
      int saveLastStartIndex = -1;
      Object value;
      boolean doCache = false;

      if (parselet.cacheResults || ENABLE_STATS) {
         doCache = true;
         if (resultCache != null) {
            ParseletState state = resultCache.get(currentIndex);
            if (state != null) {
               ParseletState res = findMatchingState(state, parselet);
               if (res != null) {
                  if (parselet.cacheResults) {
                     if (parselet.accept(semanticContext, res.value, currentIndex, res.endIx) == null) {
                        currentIndex = res.endIx;
                        parselet.updateCachedResult(res.value);
                        return res.value;
                     }
                  }
                  res.cacheHits++;
               }
            }
         }
      }

      if (!enablePartialValues && parselet.partialValuesOnly)
         return PARSELET_DISABLED;

      /*
      if ((language.debug || parselet.trace) && !language.debugSuccessOnly)
         System.out.println(indent(inProgressCount) + "Next: " + getLookahead(8) + " testing rule: " + parselet.toString());
      */

      boolean disableDebug = false;
      if (parselet.negated)
         negatedCt++;
      if (parselet.trace && ENABLE_RESULT_TRACE) {
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

         if (doCache) {
            if (resultCache == null)
               resultCache = new HashMap<Integer,ParseletState>();
            ParseletState newState = new ParseletState(parselet, value, currentIndex);
            ParseletState currentState;
            if (ENABLE_STATS) {
               currentState = resultCache.get(lastStartIndex);
               if (currentState != null)
                  currentState = findMatchingState(currentState, parselet);
            }
            else
               currentState = null;
            if (currentState == null) {
               currentState = resultCache.put(lastStartIndex, newState);
               if (currentState != null) {
                  newState.next = currentState;
               }
            }
         }

         totalParseCt++;

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

         if (ENABLE_RESULT_TRACE && disableDebug)
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

   public final Object reparseNext(Parselet parselet, Object oldParseNode, DiffContext dctx, boolean forceReparse, Parselet exitParselet) {
      Parselet saveParselet = null;
      int saveLastStartIndex = -1;
      Object value;
      boolean doCache = false;

      /*
      if ((language.debug || parselet.trace) && !language.debugSuccessOnly)
         System.out.println(indent(inProgressCount) + "Next: " + getLookahead(8) + " testing rule: " + parselet.toString());
      */

      if (parselet.cacheResults || ENABLE_STATS) {
         doCache = true;
         if (resultCache != null) {
            ParseletState state = resultCache.get(currentIndex);
            if (state != null) {
               ParseletState res = findMatchingState(state, parselet);
               if (res != null) {
                  if (parselet.cacheResults) {
                     if (parselet.accept(semanticContext, res.value, currentIndex, res.endIx) == null) {
                        currentIndex = res.endIx;
                        parselet.updateCachedResult(res.value);
                        return res.value;
                     }
                  }
                  res.cacheHits++;
               }
            }
         }
      }

      boolean disableDebug = false;
      if (parselet.negated)
         negatedCt++;
      try {
         saveLastStartIndex = lastStartIndex;
         lastStartIndex = currentIndex;
         inProgressCount++;
         saveParselet = currentParselet;
         currentParselet = parselet;

         value = parselet.reparse(this, oldParseNode, dctx, forceReparse, exitParselet);

         if (doCache) {
            if (resultCache == null)
               resultCache = new HashMap<Integer,ParseletState>();
            ParseletState newState = new ParseletState(parselet, value, currentIndex);
            ParseletState currentState;
            if (ENABLE_STATS) {
               currentState = resultCache.get(lastStartIndex);
               if (currentState != null)
                  currentState = findMatchingState(currentState, parselet);
            }
            else
               currentState = null;
            if (currentState == null) {
               currentState = resultCache.put(lastStartIndex, newState);
               if (currentState != null) {
                  newState.next = currentState;
               }
            }
         }

         totalParseCt++;

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

         if (ENABLE_RESULT_TRACE && disableDebug)
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

   /**
    * This method is called by recognizers which are nested.
    * it will do all of the work necessary before/after calling the recognizer.recognize method
    * (i.e. rolling back changes if there are errors, dealing with the parse error
    * returned - add it to the parser).
    */
   public final Object restoreNext(Parselet parselet, ISemanticNode oldModel, RestoreCtx rctx, boolean inherited) {
      Parselet saveParselet = null;
      int saveLastStartIndex = -1;
      Object value;

      if (!enablePartialValues && parselet.partialValuesOnly)
         return PARSELET_DISABLED;

      /*
      if ((language.debug || parselet.trace) && !language.debugSuccessOnly)
         System.out.println(indent(inProgressCount) + "Next: " + getLookahead(8) + " testing rule: " + parselet.toString());
      */

      boolean disableDebug = false;
      if (parselet.negated)
         negatedCt++;
      if (parselet.trace && ENABLE_RESULT_TRACE) {
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

         value = parselet.restore(this, oldModel, rctx, inherited);

         totalParseCt++;

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

         if (ENABLE_RESULT_TRACE && disableDebug)
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

   /** Called when we need to unwind the parser to an earlier or later state -
    * TODO:performance - try changing the callers of this in the inner loop here to just set the field.  */
   public final void changeCurrentIndex(int ix) {
      currentIndex = ix;
   }

   /**
    * Reset is used when we encounter an error and need to revert back to a previous index. Use restoreCurrentIndex when
    * you are peeking into the stream, or need to move the semantic context backwards to reparse for errors
    */
   public final Object resetCurrentIndex(int ix) {
      Object res = null;
      if (semanticContext != null)
         res = semanticContext.resetToIndex(ix);
      currentIndex = ix;
      return res;
   }

   /**
    * Like reset, but will save semantic context info.  For example, if we peek ahead an HTML close tag, we remove it from
    * the semantic context.  When we restore the current index, we put the tag back again so we can match it again in the close tag.
    */
   public final void restoreCurrentIndex(int ix, Object res) {
      if (semanticContext != null)
         semanticContext.restoreToIndex(ix, res);
      currentIndex = ix;
   }

   public final int getCurrentIndex() {
      return currentIndex;
   }

   /** Returns the starting position for the active expression */
   public final int getLastStartIndex() {
      return lastStartIndex;
   }

   public final ParseError parseError(String errorCode) {
      return parseError(currentParselet, null, null, errorCode, currentIndex, currentIndex, (Object[])null);
   }

   public static boolean isBetterError(int currentStart, int currentEnd, int newStart, int newEnd, boolean replaceIfEqual) {
      if (currentStart == -1)
         return true;

      int newLen = newEnd - newStart;
      int currentLen = currentEnd - currentStart;

      // There's a case where we might have a parse-error that represents a small fragment in the middle of the document.
      // Rather than using that, we'd rather use the error chunk that comes before it, as long as is parses more of the
      // document.
      if (newLen < currentLen && currentLen > 2 && currentStart == 0 && newEnd > currentEnd && newStart >= currentEnd)
         return false;

      if (currentLen < newLen && newLen > 2 && newStart == 0 && currentEnd > newEnd && currentStart >= newEnd)
         return true;

      if (replaceIfEqual)
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

      // TODO: verify that this is not a performance problem (since it marks all recursive parse-nodes as error nodes)
      if (partialValue instanceof IParseNode)
         ((IParseNode) partialValue).setErrorNode(true);

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
               if (childParselet != null && childParselet.producesParselet(lastError.parselet))
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

   /**
    * When it's convenient to parse a document being lenient on errors, skipped errors are enabled.  You can parse
    * a document even when it's missing a ; etc.  Those errors are recorded and we move on.
    */
   public void addSkippedError(ParseError skippedError) {
      if (skippedErrors == null)
         skippedErrors = new ArrayList<ParseError>();
      skippedErrors.add(skippedError);
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
         node.setSemanticValue(ParseUtil.nodeToSemanticValue(value), true);
      return node;
   }

   static final ParseError PARSE_ERROR_OVERRIDDEN = new StaticParseError("Error less specific than previous error");
   static final ParseError PARSE_NEGATED_ERROR = new StaticParseError("Match failed while negated");
   static final ParseError PARSELET_DISABLED = new StaticParseError("Parselet not enabled in this mode");

   public void getChars(int srcBegin, int srcEnd, char dst[], int dstBegin) {
      int dstOffset = dstBegin - srcBegin;
      for (int i = srcBegin; i < srcEnd; i++)
         dst[i-dstOffset] = charAt(i);
   }

   public String toString() {
      return language.toString() + " parsing: " + getLookahead(32);
   }

   /**
    * Warning: this method is tuned for performance so has a messy interface.  Using ArrString because we want to
    * use char arrays directly
    *
    * Look ahead into the currently parsed characters and match them against expectedValue.
    * If !negated:
    *    return 0 for a match and 1 for a non-match, 2 for EOF
    * If you pass in negated the match is opposite:
    *    return 1 for a match and 0 for a non-match, 0 for EOF
    */
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

   private static class ParseletState {
      ParseletState(Parselet p, Object v, int endIx) {
         parselet = p;
         value = v;
         this.endIx = endIx;
      }

      Object value;
      Parselet parselet;
      int endIx;

      // ifdef ENABLE_STATS
      int cacheHits;

      // Used for the states we put into the resultCache.  We store a linked list
      // of the states... this is a way to implement what hopefully is a small set of
      // parselet states for each position in the file.
      ParseletState next;

      public String toString() {
         return parselet.toString() + " ends at: " + endIx + (next == null ? "" : next.toString());
      }
   }

   // TODO: ifdef ENABLE_STATS

   public static String getStatInfo(Parselet startParselet) {
      StringBuilder sb = new StringBuilder();
      internalGetStatInfo(startParselet, 0, startParselet.name, new HashMap<Parselet, String>(), sb);
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

   public String getCacheStats() {
      ArrayList<String> res = new ArrayList<String>();
      for (Map.Entry<Integer,ParseletState> ent:resultCache.entrySet()) {
         Integer pos = ent.getKey();
         ParseletState val = ent.getValue();

         if (val.cacheHits > 1) {
            res.add(val.cacheHits + " : " + pos + ": " + val.parselet);
         }
      }
      Collections.sort(res);
      StringBuilder out = new StringBuilder();
      for (String str:res) {
         out.append(str);
         out.append("\n");
      }
      return out.toString();
   }
}
