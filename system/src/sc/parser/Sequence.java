/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.binf.ParseInStream;
import sc.binf.ParseOutStream;
import sc.lang.ISemanticNode;
import sc.lang.SemanticNodeList;
import sc.lang.java.JavaSemanticNode;
import sc.type.TypeUtil;

import java.util.ArrayList;
import java.util.List;

public class Sequence extends NestedParselet  {
   /** For a Sequence which has all optional children, should the value of this sequence be an empty object? (acceptNoContentn=true) or
    * return null as the value for the sequence, the default. */
   boolean acceptNoContent = false;

   /** If set, after parsing this slot return a partial-value result when partialValues is enabled */
   public int skipOnErrorSlot = -1;
   /** If set with skipOnErrorParselet, stop skip on error parsing at this slot */
   public int skipOnErrorEndSlot = -1;

   /** The minimum number of slots that should be filled before considering this parselet produces content.  Used for partial values parsing and to set the 'anyContent' flag */
   // TODO: this probably should be combined with skipOnErrorSlot, or at least we should use that the partial values and minContentSlot for
   public int minContentSlot = 0;

   public Sequence() { super(); }

   public Sequence(String id, int options) {
      super(id, options);
   }

   public Sequence(int options) {
      super(options);
   }

   public Sequence(String id, Parselet... toAdd) {
      super(id, toAdd);
   }

   public Sequence(Parselet... toAdd) {
      super(toAdd);
   }

   public Sequence(String id, int options, Parselet... toAdd) {
      super(id, options, toAdd);
   }

   public Sequence(int options, Parselet... toAdd) {
      super(options, toAdd);
   }

   public String getSeparatorSymbol() {
      return " ";
   }

   private ParentParseNode cloneParseNode(ParentParseNode value) {
      Object semValue = value.getSemanticValue();
      ParentParseNode errVal;
      if (semValue != null && semValue instanceof ISemanticNode) {
         ISemanticNode newNode = ((ISemanticNode) semValue).deepCopy(ISemanticNode.CopyAll, null);
         errVal = (ParentParseNode) newNode.getParseNode();
      }
      else
         errVal = value.deepCopy();
      return errVal;
   }

   public Object parse(Parser parser) {
      if (trace)
         System.out.println("*** parse sequence: " + this + " at " + parser.currentIndex);

      if (repeat)
         return parseRepeatingSequence(parser, false, null);

      ParentParseNode value = null;
      int startIndex = parser.currentIndex;

      boolean anyContent = false, errorNode = false;

      ErrorParseNode pendingError = null;
      int pendingErrorIx = -1;

      int numParselets = parselets.size();
      for (int i = 0; i < numParselets; i++) {
         Parselet childParselet = parselets.get(i);

         Object nestedValue = parser.parseNext(childParselet);
         if (nestedValue instanceof ParseError) {
            if (negated) {
               // Reset back to the beginning of the sequence
               parser.changeCurrentIndex(startIndex);

               return null;
            }

            ParseError err = (ParseError) nestedValue;
            boolean isBetterError = false;
            // If we are optional and have at least some content that we've matched and looking for partial values, this optional error may be helpful
            // in stitching things together.
            if (optional && (!parser.enablePartialValues || parser.currentIndex != parser.currentErrorStartIndex)) {
               if (parser.enablePartialValues) {
                  int errEndIx = Math.max(parser.currentIndex, err.endIndex);
                  if (!Parser.isBetterError(parser.currentErrorStartIndex, parser.currentErrorEndIndex, startIndex, errEndIx, true)) {
                     parser.changeCurrentIndex(startIndex);
                     return null;
                  }
                  else {
                     isBetterError = true;
                  }
               }
               else {
                  parser.changeCurrentIndex(startIndex);
                  return null;
               }
            }

            if (parser.enablePartialValues) {
               Object pv = err.partialValue;

               // If we failed to complete this sequence, and we are in "error handling" mode, try to re-parse the previous sequence by extending it.
               // Some sequences collapse all values into a single child (e.g. blockComment).  For those, we can't support this optimization.
               if (i > 0 && value != null && value.children != null && value.children.size() >= i && pendingErrorIx == -1) {
                  int prevIx = i - 1;
                  Parselet prevParselet = parselets.get(prevIx);
                  Object oldValue = value.children.get(prevIx);
                  int saveIndex = parser.currentIndex;
                  Object ctxState = null;
                  if (oldValue instanceof IParseNode) {
                     ctxState = parser.resetCurrentIndex(((IParseNode) oldValue).getStartIndex());
                  }
                  Object newPrevValue = prevParselet.parseExtendedErrors(parser, childParselet);
                  // If we have a successful extended error parse that's longer than the previous one, we should try parsing again
                  if (newPrevValue != null && !(newPrevValue instanceof ParseError) && (oldValue == null || ((CharSequence) newPrevValue).length() > ((CharSequence) oldValue).length())) {
                     value.set(newPrevValue, childParselet, prevIx, false, parser);

                     // Go back and retry the current child parselet now that we've parsed the previous one again successfully... we know it should match because we just peeked it in the previous parselet.
                     i = i - 1;
                     continue;
                  }
                  else
                     parser.restoreCurrentIndex(saveIndex, ctxState);
               }

               if (i < parselets.size() - 1) {
                  Parselet exitParselet = parselets.get(i+1);
                  // TODO: for the simpleTag case where we are completing tagAttributes, we have / and > as following parselets where
                  // the immediate one afterwards is optional.  To handle this we can take the list of parselets following in the sequence
                  // and pass them all then peek them as a list.
                  if (!exitParselet.optional) {
                     Object extendedValue = childParselet.parseExtendedErrors(parser, exitParselet);
                     if (extendedValue != null) {
                        nestedValue = extendedValue;
                        err = nestedValue instanceof ParseError ? (ParseError) nestedValue : null;
                     }
                  }
               }

               // Question: should we always do the skipOnError processing - not just on enablePartialValues?   It could in general give more complete syntax errors
               // For now, I think the answer is no.  If we need better errors, we should parse files with syntax errors again with this mode.
               // Otherwise, it's possible we'll introduce strange ambiguities into the successfully parsed files.
               if (err != null) {
                  // Skip on error can either be set on the child or as a slot index on the parent
                  if ((childParselet.skipOnError && skipOnErrorSlot == -1) || (skipOnErrorSlot != -1 && i >= skipOnErrorSlot)) {
                     if (skipOnErrorParselet != null && (skipOnErrorEndSlot == -1 || i < skipOnErrorEndSlot)) {
                        int errorStart = parser.currentIndex;

                        Object errorRes = parser.parseNext(skipOnErrorParselet);

                        if (!(errorRes instanceof ParseError) && errorRes != null && ((CharSequence) errorRes).length() > 0) {
                           // We were able to consume at least some characters so now we decide which sequence to retry.
                           // We definitely retry this slot but if there are null optional slots in front of us, we can try to
                           // reparse them as well.
                           int resumeIx = i;
                           Object resumeSlotVal = value == null || value.children == null || value.children.size() <= i ? null : value.children.get(resumeIx);
                           while (resumeIx >= skipOnErrorSlot - 1) {
                              int nextIx = resumeIx - 1;

                              Parselet backChild = parselets.get(nextIx);
                              // Walking backwards
                              if (!backChild.optional || backChild.getLookahead())
                                 break;

                              if (value.children != null && nextIx < value.children.size()) {
                                 Object nextSlotVal = value.children.get(nextIx);
                                 // Can't retry this slot as we already parsed it - an error can be parsed but a pre-error cannot because it's an error followed by a matched result
                                 if (nextSlotVal != null && (!(nextSlotVal instanceof ErrorParseNode) || nextSlotVal instanceof PreErrorParseNode))
                                    break;
                                 resumeSlotVal = nextSlotVal;
                              }
                              else
                                 resumeSlotVal = null;
                              resumeIx = nextIx;
                           }

                           if (resumeSlotVal == null) {
                              pendingError = new ErrorParseNode(new ParseError(skipOnErrorParselet, "Expected {0}", new Object[]{this}, errorStart, parser.currentIndex), errorRes.toString());
                              pendingErrorIx = resumeIx;
                              value.addOrSet(pendingError, parselets.get(resumeIx), -1, resumeIx, true, parser);
                              // Since we filled in a parseError for resumeIx, need to go to the next slot to actually resume
                              resumeIx++;
                           }
                           else {
                              if (resumeSlotVal instanceof PreErrorParseNode)
                                 System.err.println("*** Bad code - trying to resume pre-errors");
                              ErrorParseNode resumeNode = (ErrorParseNode) resumeSlotVal;
                              resumeNode.errorText += errorRes.toString();
                              pendingErrorIx = resumeIx;
                           }
                           errorNode = true;

                           // Restart the loop again at slot resumeIx
                           i = resumeIx - 1;
                           continue;
                        }
                        else {
                           parser.addSkippedError(err);
                           // Record the error but move on
                           if (err.partialValue != null) {
                              nestedValue = err.partialValue;
                              parser.changeCurrentIndex(err.endIndex);
                           }
                           else
                              nestedValue = new ErrorParseNode(err, "");
                           errorNode = true;
                           err = null;
                        }
                     }
                     // No parselet - so just skip parsing this sequence and resume parsing from here
                     else {
                        parser.addSkippedError(err);
                        // Record the error but move on
                        if (err.partialValue != null) {
                           nestedValue = err.partialValue;
                           parser.changeCurrentIndex(err.endIndex);
                        }
                        else
                           nestedValue = new ErrorParseNode(err, "");
                        errorNode = true;
                        err = null;
                     }
                  }
                  else {
                     if (err.optionalContinuation) {
                        ParentParseNode errVal;
                        if (pv != null) {
                           if (value == null)
                              errVal = (ParentParseNode) newParseNode(startIndex);
                           else {
                              // Here we need to clone the semantic value so we keep track of the state that
                              // represents this error path separate from the default path which is going to match
                              // and replace the error slot here with a null
                              Object semValue = value.getSemanticValue();
                              if (semValue != null && semValue instanceof ISemanticNode) {
                                 ISemanticNode newNode = ((ISemanticNode) semValue).deepCopy(ISemanticNode.CopyAll, null);
                                 errVal = (ParentParseNode) newNode.getParseNode();
                              }
                              else
                                 errVal = value.deepCopy();
                           }
                           errVal.add(pv, childParselet, -1, i, false, parser);
                        }
                        else
                           errVal = value;
                        err = err.propagatePartialValue(errVal);
                        //err.continuationValue = true;
                        err.optionalContinuation = false;
                        nestedValue = null; // Switch this to optional
                        err = null; // cancel the error
                     }
                     // Always call this to try and extend the current error... also see if we can generate a new error
                     else if (!childParselet.getLookahead() && i >= minContentSlot && !disableExtendedErrors()) {

                        // First complete the value with any partial value from this error and nulls for everything
                        // else.
                        if (value == null)
                           value = (ParentParseNode) newParseNode(startIndex);
                        value.add(pv, childParselet, -1, i, false, parser);
                        // Add these null slots so that we do all of the node processing
                        for (int k = i+1; k < numParselets; k++)
                           value.add(null, parselets.get(k), -1, k, false, parser);

                        // Now see if this computed value extends any of our most specific errors.  If so, this error
                        // can be used to itself extend other errors based on the EOF parsing.
                        if ((extendsPartialValue(parser, childParselet, value, anyContent, startIndex, err) && anyContent) || err.eof || pv != null || (anyContent && isBetterError)) {
                           if (!err.eof || !value.isEmpty()) {
                              if (optional && value.isEmpty())
                                 return null;
                              // Keep the origin error description so the right info is in the error presented to the user
                              ParseError newError = parsePartialError(parser, value, err, childParselet, err.errorCode, err.errorArgs);
                              return newError;
                           }
                        }
                     }
                  }
               }
            }

            // Unless the error was cancelled or skip-on-error was true, i.e. so we logged the error and are moving on.
            if (err != null) {
               if (optional) {
                  parser.changeCurrentIndex(startIndex);
                  return null;
               }

               if (reportError)
                  return parseError(parser, childParselet, "Expected: {0}", this);
               else
                  parser.changeCurrentIndex(startIndex);
               return nestedValue;
            }
         }

         if (value == null)
            value = newParseNode(startIndex);

         if (!childParselet.getLookahead()) {
            if (nestedValue instanceof IParseNode) {
               IParseNode nestedParseNode = (IParseNode) nestedValue;
               // If the last parselet we parse which has content is not an error we do not propagate the error for the
               // containing node.  We want to ignore internal errors but catch when this sequence ends in an error - i.e.
               // is some kind of fragment that must be reparsed, even if the beginning content has not changed.
               if (!nestedParseNode.isEmpty())
                  errorNode = nestedParseNode.isErrorNode();
            }
            if (pendingErrorIx != -1) {
               if (nestedValue != null) {
                  if (pendingErrorIx != i) {
                     value.addOrSet(nestedValue, childParselet, -1, i, false, parser);
                  }
                  else {
                     if (nestedValue instanceof IParseNode) {
                        if (nestedValue instanceof ErrorParseNode)
                           pendingError.errorText += nestedValue.toString();
                        else if (!(nestedValue instanceof ParseError)) {
                           // Need to somehow insert the errorText as an ErrorParseNode into the ParentParseNode or ParseNode?  Or add a "post-value" onto error node?
                           PreErrorParseNode pepn = new PreErrorParseNode(pendingError.error, pendingError.errorText, (IParseNode) nestedValue);
                           nestedValue = pepn;
                           value.addOrSet(nestedValue, childParselet, -1, i, false, parser);
                        }
                     }
                     else
                        pendingError.errorText += nestedValue.toString();
                  }
               }
               else
                  value.addOrSet(null, childParselet, -1, i, false, parser);
            }
            else
               value.add(nestedValue, childParselet, -1, i, false, parser);
         }
         else if (slotMapping != null) // Need to preserve the specific index in the results if we have a mapping
            value.add(null, childParselet, -1, i, true, parser);

         if (nestedValue != null || childParselet.isNullValid())
            anyContent = minContentSlot <= i;
      }

      if (errorNode && value != null)
         value.setErrorNode(true);

      if (lookahead) {
         // Reset back to the beginning of the sequence
         parser.changeCurrentIndex(startIndex);
      }

      if (anyContent || acceptNoContent) {
         String customError;
         if ((customError = accept(parser.semanticContext, value, startIndex, parser.currentIndex)) != null) {
            if (negated)
                return value;
            return parseError(parser, customError, value, this);
         }

         if (negated)
            return parseError(parser, "Negated rule: value {0} matched rule: {1}", value, this);
         return value;
      }
      else
         return null;
   }

   private static final boolean alwaysExtendErrors = true;

   // Note: this is a modified copy of the parse() method, duplicating a lot of code but it needs to do more.  We are keeping
   // them separate because we want the parse method itself to be faster and simpler.  Reparse is only used when editing
   // a model interactively.
   public Object reparse(Parser parser, Object oldParseNode, DiffContext dctx, boolean forceReparse, Parselet exitParselet) {
      if (trace)
         System.out.println("*** reparse sequence: " + this + " at " + parser.currentIndex);

      Object origOldParseNode = oldParseNode;

      if (!anyReparseChanges(parser, oldParseNode, dctx, forceReparse)) {
         advancePointer(parser, oldParseNode, dctx);
         parser.reparseSkippedCt++;
         return oldParseNode;
      }

      if (trace)
         System.out.println("*** reparse sequence: " + this + " at " + parser.currentIndex);

      parser.reparseCt++;

      if (repeat) {
         Object res = reparseRepeatingSequence(parser, exitParselet != null, exitParselet, oldParseNode, dctx, forceReparse);
         checkForSameAgainRegion(parser, origOldParseNode, dctx, res == null || res instanceof ParseError, forceReparse);
         return res;
      }

      // If the changes in the text begin before this node, just reparse it from scratch
      if (dctx.changedRegion && oldParseNode != null) {
         forceReparse = true;
      }

      // If the primitive node has changed or we have an ErrorParseNode, just reparse it since we don't maintain enough info to split the value
      // apart and incrementally update it
      if (!(oldParseNode instanceof IParseNode) && oldParseNode != null) {
         forceReparse = true;
      }

      if (oldParseNode instanceof IParseNode && !producesParselet(((IParseNode) oldParseNode).getParselet())) {
         forceReparse = true;
      }

      int startIndex = parser.currentIndex;
      ParentParseNode value = null;
      Object[] savedOrigChildren = null;

      boolean anyContent = false, errorNode = false;

      int numParselets = parselets.size(), newChildCount = 0, pendingErrorIx = -1, svCount = 0;
      ErrorParseNode pendingError = null;

      for (int i = 0; i < numParselets; i++) {
         Parselet childParselet = parselets.get(i);
         boolean nextChildReparse = false;

         Object oldChildParseNode = getReparseChildNode(oldParseNode, newChildCount, forceReparse);

         if (oldChildParseNode == SKIP_CHILD) {
            // Last time we took the reparse but this time we might not
            if (!childParselet.anyReparseChanges(parser, null, dctx, false)) {
               svCount += value.addForReparse(null, childParselet, svCount, newChildCount++, i, false, parser, null, dctx, false, false);
               continue;
            }
            else {
               oldChildParseNode = null;
               forceReparse = true;
            }
         }

         // If there's unparsed junk before this node don't use it when trying to reparse the slot
         if (oldChildParseNode instanceof PreErrorParseNode) {
            PreErrorParseNode oldChildPreError = (PreErrorParseNode) oldChildParseNode;
            oldChildParseNode = oldChildPreError.value;
         }

         nextChildReparse = !oldChildMatches(oldChildParseNode, childParselet, dctx);

         Object nestedValue = parser.reparseNext(childParselet, oldChildParseNode, dctx, forceReparse || nextChildReparse, getExitParselet(i));
         if (nestedValue instanceof ParseError) {
            if (negated) {
               // Reset back to the beginning of the sequence
               dctx.changeCurrentIndex(parser, startIndex);

               checkForSameAgainRegion(parser, origOldParseNode, dctx, true, forceReparse);
               return null;
            }

            ParseError err = (ParseError) nestedValue;
            boolean isBetterError = false;
            // If we are optional and have at least some content that we've matched and looking for partial values, this optional error may be helpful
            // in stitching things together.
            if (optional && (!parser.enablePartialValues || parser.currentIndex != parser.currentErrorStartIndex)) {
               if (parser.enablePartialValues) {
                  int errEndIx = Math.max(parser.currentIndex, err.endIndex);
                  if (!Parser.isBetterError(parser.currentErrorStartIndex, parser.currentErrorEndIndex, startIndex, errEndIx, true)) {
                     dctx.changeCurrentIndex(parser, startIndex);
                     checkForSameAgainRegion(parser, origOldParseNode, dctx, true, forceReparse);
                     return null;
                  }
                  else {
                     isBetterError = true;
                  }
               }
               else {
                  dctx.changeCurrentIndex(parser, startIndex);
                  checkForSameAgainRegion(parser, origOldParseNode, dctx, true, forceReparse);
                  return null;
               }
            }

            if (parser.enablePartialValues) {
               Object pv = err.partialValue;

               // If we failed to complete the current child in the sequence, and we are in "error handling" mode, try to re-parse the previous child by extending it.
               // Some sequences collapse all values into a single child (e.g. blockComment).  For those, we can't support this optimization.
               if (i > 0 && value != null && value.children != null && value.children.size() >= i) {
                  int prevIx = i - 1;
                  Parselet prevParselet = parselets.get(prevIx);
                  Object oldValue = value.children.get(prevIx);
                  Object origOldChild = value == origOldParseNode ? oldValue : null;
                  if (savedOrigChildren != null && prevIx < savedOrigChildren.length) {
                     origOldChild = savedOrigChildren[prevIx];
                  }
                  boolean prevChildReparse = !oldChildMatches(origOldChild, prevParselet, dctx);
                  int saveIndex = parser.currentIndex;
                  Object ctxState = null;
                  if (oldValue instanceof IParseNode) {
                     ctxState = dctx.resetCurrentIndex(parser, ((IParseNode) oldValue).getStartIndex());
                  }
                  // TODO: remove this call since we do reparsing of extended errors during the main reparse now.  partial values still does it in two passes but this will always just return null now.
                  // One problem we had is that we don't reset the currentErrors when we reset the start position.  So getting an accurate 'partial value' from extendPartialValues won't work during the
                  // reparse.  Probably this same algorithm should be used for the normal parse method with enablePartialValues.
                  Object newPrevValue = prevParselet.reparseExtendedErrors(parser, childParselet, origOldChild, dctx, forceReparse || prevChildReparse);
                  if (newPrevValue != null && !(newPrevValue instanceof ParseError)) {
                     Object oldPrevValue = value.children == null || prevIx >= value.children.size() ? null : value.children.get(prevIx);
                     boolean longerVal = true;
                     if (oldPrevValue != null && !(oldPrevValue instanceof ParseError))
                        longerVal = ((CharSequence) newPrevValue).length() > ((CharSequence) oldPrevValue).length();

                     value.set(newPrevValue, childParselet, prevIx, false, parser);

                     // Go back and retry the current child parselet now that we've parsed the previous one again successfully... we know it should match because we just peeked it in the previous parselet.
                     if (longerVal) {
                        i = i - 1;
                        continue;
                     }
                  }
                  else
                     dctx.restoreCurrentIndex(parser, saveIndex, ctxState);
               }

               if (i < parselets.size() - 1) {
                  Parselet nextExitParselet = parselets.get(i+1);
                  // TODO: for the simpleTag case where we are completing tagAttributes, we have / and > as following parselets where
                  // the immediate one afterwards is optional.  To handle this we can take the list of parselets following in the sequence
                  // and pass them all then peek them as a list.
                  if (!nextExitParselet.optional) {
                     Object extendedValue = childParselet.reparseExtendedErrors(parser, nextExitParselet, oldChildParseNode, dctx, forceReparse || nextChildReparse);
                     if (extendedValue != null) {
                        nestedValue = extendedValue;
                        err = nestedValue instanceof ParseError ? (ParseError) nestedValue : null;
                     }
                  }
               }

               // Question: should we always do the skipOnError processing - not just on enablePartialValues?   It could in general give more complete syntax errors
               // For now, I think the answer is no.  If we need better errors, we should parse files with syntax errors again with this mode.
               // Otherwise, it's possible we'll introduce strange ambiguities into the successfully parsed files.
               if (err != null) {
                  // Skip on error can either be set on the child or as a slot index on the parent
                  if ((childParselet.skipOnError && skipOnErrorSlot == -1) || (skipOnErrorSlot != -1 && i >= skipOnErrorSlot)) {
                     if (skipOnErrorParselet != null && (skipOnErrorEndSlot == -1 || i < skipOnErrorEndSlot)) {
                        int errorStart = parser.currentIndex;

                        if (trace)
                           trace = trace;

                        Object errorChildParseNode = oldChildParseNode;
                        boolean errorReparse = forceReparse || nextChildReparse;
                        if (errorChildParseNode instanceof IParseNode) {
                           if (!skipOnErrorParselet.producesParselet(((IParseNode) errorChildParseNode).getParselet()))
                              errorReparse = true;
                        }

                        Object errorRes = parser.reparseNext(skipOnErrorParselet, errorChildParseNode, dctx, errorReparse, getExitParselet(i));

                        if (!(errorRes instanceof ParseError) && errorRes != null && ((CharSequence) errorRes).length() > 0) {
                           // We were able to consume at least some characters so now we decide which sequence to retry.
                           // We definitely retry this slot but if there are null optional slots in front of us, we can try to
                           // reparse them as well.
                           int resumeIx = i;
                           Object resumeSlotVal = value == null || value.children == null || value.children.size() <= i ? null : value.children.get(resumeIx);
                           while (resumeIx >= skipOnErrorSlot - 1) {
                              int nextIx = resumeIx - 1;

                              Parselet backChild = parselets.get(nextIx);
                              // Walking backwards
                              if (!backChild.optional || backChild.getLookahead())
                                 break;

                              if (value.children != null && nextIx < value.children.size()) {
                                 Object nextSlotVal = value.children.get(nextIx);
                                 // Can't retry this slot as we already parsed it - an error can be parsed but a pre-error cannot because it's an error followed by a matched result
                                 if (nextSlotVal != null && (!(nextSlotVal instanceof ErrorParseNode) || nextSlotVal instanceof PreErrorParseNode))
                                    break;
                                 resumeSlotVal = nextSlotVal;
                              }
                              else
                                 resumeSlotVal = null;
                              resumeIx = nextIx;
                           }

                           if (resumeSlotVal == null) {
                              pendingError = new ErrorParseNode(new ParseError(skipOnErrorParselet, "Expected {0}", new Object[]{childParselet}, errorStart, parser.currentIndex), errorRes.toString());
                              pendingErrorIx = resumeIx;
                              value.addForReparse(pendingError, parselets.get(resumeIx), -1, resumeIx, resumeIx, true, parser, errorChildParseNode, dctx, false, false);
                              resumeIx++;
                           }
                           else {
                              if (resumeSlotVal instanceof PreErrorParseNode)
                                 System.err.println("*** Error - should not be reparsing something that finished successfully in the previous parse");
                              if (resumeSlotVal instanceof ErrorParseNode) {
                                 ErrorParseNode resumeNode = (ErrorParseNode) resumeSlotVal;
                                 if (pendingErrorIx == -1) {
                                    resumeNode.errorText = errorRes.toString();
                                 }
                                 else
                                    resumeNode.errorText += errorRes.toString();
                                 // Since we are reusing an old node here we need to advance it's start index
                                 resumeNode.setStartIndex(errorStart);
                                 pendingError = resumeNode;
                              }
                              else if (resumeSlotVal instanceof IParseNode) {
                                 PreErrorParseNode newEPN = new PreErrorParseNode(new ParseError(skipOnErrorParselet, "Expected {0}", new Object[]{childParselet},errorStart, parser.currentIndex), errorRes.toString(),
                                         (IParseNode) resumeSlotVal);
                                 pendingError = newEPN;
                                 value.addForReparse(newEPN, parselets.get(resumeIx), -1, resumeIx, resumeIx, true, parser, errorChildParseNode, dctx, false, false);
                              }
                              pendingErrorIx = resumeIx;
                           }
                           errorNode = true;

                           // Restart the loop again at slot resumeIx
                           i = resumeIx - 1;
                           newChildCount = resumeIx;
                           continue;
                        }
                        // We were not able to consume more node in the error parselet but have completed enough for
                        // a partial value.  Just use 'null' or the partial value for this node and carry on
                        else {
                           parser.addSkippedError(err);
                           // Record the error but move on
                           if (err.partialValue != null) {
                              nestedValue = err.partialValue;
                              dctx.changeCurrentIndex(parser, err.endIndex);
                           }
                           else
                              nestedValue = new ErrorParseNode(err, "");
                           errorNode = true;
                           err = null;
                        }
                     }
                     else {
                        parser.addSkippedError(err);
                        // Record the error but move on
                        if (err.partialValue != null) {
                           nestedValue = err.partialValue;
                           dctx.changeCurrentIndex(parser, err.endIndex);
                        }
                        else
                           nestedValue = new ErrorParseNode(err, "");
                        errorNode = true;
                        err = null;
                     }
                  }
                  else {
                     int origChildCount = newChildCount;

                     // TODO - what we really want to do here is peak the next parselet and if it matches, we'll just
                     // ignore this error and set nestedValue to null.
                     //err.optionalContinuation = false;
                     if (err.optionalContinuation) {
                        /*
                        ParentParseNode errVal;
                        if (pv != null) {
                           if (value == null) {
                              errVal = resetOldParseNode(forceReparse || nextChildReparse ? null : oldParseNode, startIndex, true, false);
                           }
                           else {
                              // Here we need to clone the semantic value so we keep track of the state that
                              // represents this error path separate from the default path which is going to match
                              // and replace the error slot here with a null
                              Object semValue = value.getSemanticValue();
                              if (semValue != null && semValue instanceof ISemanticNode) {
                                 ISemanticNode semValNode = ((ISemanticNode) semValue);
                                 if (semValNode.getParseNode().getParselet() == this) {
                                    ISemanticNode newNode = semValNode.deepCopy(ISemanticNode.CopyAll, null);
                                    errVal = (ParentParseNode) newNode.getParseNode();
                                 }
                                 else {
                                    errVal = value.deepCopy();
                                 }
                              }
                              else
                                 errVal = value.deepCopy();
                           }
                           svCount += errVal.addForReparse(pv, childParselet, svCount, newChildCount++, i, false, parser, oldChildParseNode, dctx, false, false);
                           // Add these null slots so that we do all of the node processing, like setting the properties on the last child
                           //for (int k = i+1; k < numParselets; k++)
                           //   svCount += errVal.addForReparse(null, parselets.get(k), svCount, newChildCount++, k, false, parser, oldChildParseNode, dctx, true, false);
                        }
                        else
                           errVal = value;

                        err = err.propagatePartialValue(errVal);
                        //err.continuationValue = true;
                        err.optionalContinuation = false;
                        */
                        if (err.partialValue == null) {
                           nestedValue = null; // Switch this to optional
                        }
                        else {
                           nestedValue = err.partialValue;
                           dctx.changeCurrentIndex(parser, err.endIndex);
                        }
                        errorNode = true; err = null; // cancel the error

                        // Here we also need to clear out any parse-nodes we did not reparse from the old time.
                        if (value == origOldParseNode && value != null) {
                           // TODO: should we be doing this for other places where we return value?  It seems like general,
                           value.cullUnparsedNodes(parser, svCount, origChildCount, dctx);
                           newChildCount = origChildCount;
                        }
                     }
                     // Always call this to try and extend the current error... also see if we can generate a new error
                     else if (!childParselet.getLookahead() && i >= minContentSlot) {
                        // First complete the value with any partial value from this error and nulls for everything
                        // else.  Note: we have clone = true for this call to resetOldParseNode because there's a chance we end up clearing
                        // out the old value and not returning this as a result from this parselet.  The parent parselet will then try to use the
                        // cleared out "oldParseNode" as though it was not cleared out.

                        if (value == null)
                           value = resetOldParseNode(nextChildReparse || forceReparse ? null : oldParseNode, startIndex, true, true);
                        svCount += value.addForReparse(pv, childParselet, svCount, newChildCount++, i, false, parser, oldChildParseNode, dctx, false, false);
                        // Add these null slots so that we do all of the node processing
                        for (int k = i+1; k < numParselets; k++)
                           svCount += value.addForReparse(null, parselets.get(k), svCount, newChildCount++, k, false, parser, oldChildParseNode, dctx, true, false);

                        // Now see if this computed value extends any of our most specific errors.  If so, this error
                        // can be used to itself extend other errors based on the EOF parsing.
                        // TODO: warning - this relies on currentErrors which during reparse might have advanced beyond the point we are parsing.  This used happened when we used reparseExtendedErrors but that's been disabled now
                        if ((extendsPartialValue(parser, childParselet, value, anyContent, startIndex, err) && anyContent) || err.eof || pv != null || isBetterError) {
                           if (!err.eof || !value.isEmpty()) {
                              if (optional && value.isEmpty())
                                 return null;
                              // Keep the origin error description so the right info is in the error presented to the user
                              ParseError newError = parsePartialError(parser, value, err, childParselet, err.errorCode, err.errorArgs);
                              checkForSameAgainRegion(parser, origOldParseNode, dctx, true, forceReparse);
                              return newError;
                           }
                        }
                     }
                  }
               }
            }

            // Unless the error was cancelled or skip-on-error was true, i.e. so we logged the error and are moving on.
            if (err != null) {
               if (optional) {
                  dctx.changeCurrentIndex(parser, startIndex);
                  checkForSameAgainRegion(parser, origOldParseNode, dctx, true, forceReparse);
                  return null;
               }

               if (reportError)
                  return reparseError(parser, childParselet, dctx, "Expected: {0}", this);
               else
                 dctx.changeCurrentIndex(parser, startIndex);

               checkForSameAgainRegion(parser, origOldParseNode, dctx, nestedValue == null || nestedValue instanceof ParseError, forceReparse);
               return nestedValue;
            }
         }

         if (value == null) {
            value = resetOldParseNode(nextChildReparse || forceReparse ? null : oldParseNode, startIndex, true, false);
         }

         if (!childParselet.getLookahead()) {
            // When we get an error parsing this sequence, if we've changed the children array we need to make a backup copy so we can pull out the original old parse-nodes
            if (savedOrigChildren == null && value == origOldParseNode && value.children != null && newChildCount < value.children.size() && value.children.get(newChildCount) != nestedValue) {
               savedOrigChildren = value.children.toArray();
            }
            if (nestedValue instanceof IParseNode) {
               IParseNode nestedParseNode = (IParseNode) nestedValue;
               // If the last parselet we parse which has content is not an error we do not propagate the error for the
               // containing node.  We want to ignore internal errors but catch when this sequence ends in an error - i.e.
               // is some kind of fragment that must be reparsed, even if the beginning content has not changed.
               if (!nestedParseNode.isEmpty())
                  errorNode |= nestedParseNode.isErrorNode();
            }
            if (pendingErrorIx != -1) {
               if (nestedValue != null) {
                  if (pendingErrorIx != i) {
                     value.addForReparse(nestedValue, childParselet, -1, newChildCount, newChildCount, false, parser, oldChildParseNode, dctx, false, false);
                  }
                  else {
                     if (nestedValue instanceof IParseNode) {
                        if (nestedValue instanceof ErrorParseNode)
                           pendingError.errorText += nestedValue.toString();
                        else if (!(nestedValue instanceof ParseError)) {
                           // Need to somehow insert the errorText as an ErrorParseNode into the ParentParseNode or ParseNode?  Or add a "post-value" onto error node?
                           PreErrorParseNode pepn = new PreErrorParseNode(pendingError.error, pendingError.errorText, (IParseNode) nestedValue);
                           nestedValue = pepn;
                           value.addForReparse(nestedValue, childParselet, -1, newChildCount, newChildCount, false, parser, oldChildParseNode, dctx, false, false);
                        }
                     }
                     else
                        pendingError.errorText += nestedValue.toString();
                  }
               }
               else
                  value.addForReparse(null, childParselet, -1, newChildCount, newChildCount, false, parser, oldChildParseNode, dctx, false, false);

               newChildCount++;
            }
            else
               svCount += value.addForReparse(nestedValue, childParselet, svCount, newChildCount++, i, false, parser, oldChildParseNode, dctx, false, false);
         }
         else if (slotMapping != null) // Need to preserve the specific index in the results if we have a mapping
            svCount += value.addForReparse(null, childParselet, svCount, newChildCount++, i, true, parser, oldChildParseNode, dctx, false, false);

         if (nestedValue != null || childParselet.isNullValid())
            anyContent = minContentSlot <= i;
      }

      if (errorNode && value != null)
         value.setErrorNode(true);

      if (lookahead) {
         // Reset back to the beginning of the sequence
         dctx.changeCurrentIndex(parser, startIndex);
      }

      if (!forceReparse && oldParseNode instanceof ParentParseNode && newChildCount != ((ParentParseNode) oldParseNode).children.size() && !(this instanceof ChainedResultSequence))
         System.err.println("*** Mismatching sizes in sequence parse");

      checkForSameAgainRegion(parser, origOldParseNode, dctx, !anyContent, forceReparse);

      if (anyContent || acceptNoContent) {
         String customError;
         if ((customError = accept(parser.semanticContext, value, startIndex, parser.currentIndex)) != null) {
            if (negated)
               return value;
            return reparseError(parser, dctx, customError, value, this);
         }

         if (negated)
            return reparseError(parser, dctx, "Negated rule: value {0} matched rule: {1}", value, this);
         return value;
      }
      else
         return null;
   }

   /** For restoring the parseNode tree onto oldNode, for this sequence figure out which child node was produced by the the given slot index */
   protected Object getSlotSemanticValue(int slotIx, ISemanticNode oldNode, SaveRestoreCtx rctx) {
      if (parameterMapping == null || parameterMapping[slotIx] == ParameterMapping.SKIP) {
         return null;
      }
      rctx.arrElement = rctx.listProp = false;
      switch (parameterMapping[slotIx]) {
         case STRING:
            return null;
         case PROPAGATE:
            return oldNode;
         case INHERIT:
            // TODO:???
            return oldNode;

         case ARRAY:
            Parselet childParselet = parselets.get(slotIx);
            if (propagatesArray() && childParselet.getSemanticValueIsArray()) {
               if (((List) oldNode).size() == 0)
                  return SKIP_CHILD;
               return oldNode;
            }
            boolean childIsArray = childParselet.getSemanticValueIsArray();
            boolean valIsArray = getSemanticValueIsArray();
            if (oldNode instanceof SemanticNodeList) {
               SemanticNodeList oldList = (SemanticNodeList) oldNode;
               if (!valIsArray && !List.class.isAssignableFrom(childParselet.getSemanticValueClass())) {
                  if (rctx.arrIndex >= oldList.size()) {
                     return SKIP_CHILD;
                  }
                  rctx.arrElement = true;
                  return oldList.get(rctx.arrIndex);
               }
               else {
                  // We've restored all elements in the list - nothing to do for this slot.
                  if (rctx.arrIndex >= oldList.size())
                     return null;
                  //return rctx.arrIndex == 0 ? oldList : oldList.subList(rctx.arrIndex, oldList.size());
                  return oldList;  // The arrIndex points to the current index in the oldList
               }
            }
            else {
               if (valIsArray)
                  System.err.println("*** Error another invalid case for ARRAY");

            }
            System.out.println("*** Unhandled case");
            break;

         case NAMED_SLOT:
            if (resultClass != null && !resultClass.isInstance(oldNode))
               return null;
            Object slotValue = rctx.getPropertyValue(oldNode, slotMapping[slotIx]);
            rctx.listProp = slotValue instanceof List;
            return slotValue;
      }
      System.out.println("*** Unhandled case2");
      return null;
   }

   private Object getArraySemanticValue(ISemanticNode oldNode, int slotIx, SaveRestoreCtx rctx) {
      if (oldNode == null)
         return null;

      rctx.arrElement = false;
      if (parameterMapping == null || parameterMapping[slotIx] == ParameterMapping.SKIP) {
         return null;
      }
      switch (parameterMapping[slotIx]) {
         case STRING:
         case PROPAGATE:
         case INHERIT:
            return oldNode;

         case ARRAY:
            if (oldNode instanceof List) {
               List<Object> oldList = (List) oldNode;

               Parselet childParselet = parselets.get(slotIx);
               boolean childIsArray = childParselet.getSemanticValueIsArray();
               if (!childIsArray) {
                  if (rctx.arrIndex < oldList.size()) {
                     rctx.arrElement = true;
                     return oldList.get(rctx.arrIndex);
                  }
                  else
                     return null;
               }
               else {
                  // When we've hit the end of the array, we won't have a parse-node and so don't go into the 'restoreNext' case.  We need to just go into pIn.readChild
                  int sz = oldList.size();
                  if (sz == rctx.arrIndex)
                     return null;
                  return oldList;
               }
            }
            else
               System.out.println("*** Unhandled array case");
            break;

         case NAMED_SLOT:
            // In this case, we have a repeat node that is building up an element of the array by setting properties slot-by-slot
            if (oldNode instanceof List) {
               List<Object> oldList = (List) oldNode;
               if (rctx.arrIndex < oldList.size()) {
                  // Only go to the next array element when we've hit the last slot - e.g. <classTypeChainedTypes>ClassType
                  if (slotIx == parselets.size()-1)
                     rctx.arrElement = true;
                  Object listElem = oldList.get(rctx.arrIndex);
                  if (listElem != null) {
                     return rctx.getPropertyValue(listElem, slotMapping[slotIx]);
                  }
                  return null;
               }
               else {
                  //System.err.println("*** Error - unhandled case - return a code to stop parsing here?");
                  return null;
               }
            }
            break;
      }
      return null;
   }

   public Object restore(Parser parser, ISemanticNode oldNode, RestoreCtx rctx, boolean inherited) {
      if (trace)
         System.out.println("*** restore sequence: " + this + " at " + parser.currentIndex);

      if (repeat)
         return restoreRepeatingSequence(parser, oldNode, rctx, false, null);

      ParentParseNode value = null;
      int startIndex = parser.currentIndex;

      boolean anyContent = false, errorNode = false;
      //int saveArrIndex = rctx.arrIndex;
      //rctx.arrIndex = 0;

      ErrorParseNode pendingError = null;
      int pendingErrorIx = -1;
      boolean arrElement;
      ParseInStream pIn = rctx.pIn;

      // TODO: need to handle more than 31 parselets?
      int numParselets = parselets.size();
      int excludeChildMask = pIn == null ? 0 : pIn.readUInt();
      int bit = 1;
      for (int i = 0; i < numParselets; i++) {
         boolean skipRestore = false;
         Parselet childParselet = parselets.get(i);

         // When we have the parse node input stream, we do not have to parse non-parsed objects because we'll restore stuff
         // properly.  But when there's no input stream, we still need to validate lookahead parselets.
         if (pIn != null) {
            if (childParselet.getDiscard() || childParselet.getLookahead()) {
               /*
               if (slotMapping != null) {
                  bit = bit << 1;
               }
               continue;
               */
               if (slotMapping == null)
                  continue;
            }
         }

         if ((excludeChildMask & bit) != 0) {
            skipRestore = true;
         }
         bit = bit << 1;

         boolean childInherited = false;
         boolean listProp = false;
         Object nestedValue = null;
         Object oldChildObj;
         if (oldNode != null) {
            oldChildObj = getSlotSemanticValue(i, oldNode, rctx);
            if (oldChildObj == SKIP_CHILD) {
               skipRestore = true;
               oldChildObj = null;
            }

            // TODO: performance - if this returns null for a spacing element we have to reparse to pick up that spacing - could save spacing ranges so we can quickly account for that on the restore?

            // If we are passing the parent object through to the child, we will have already matched the parseletId in the semantic node when the slot uses "*" (produced from the top parselet using properties set from the child)
            // and not "." (produced from the child)
            childInherited = oldChildObj == oldNode && parameterMapping != null && parameterMapping[i] == ParameterMapping.INHERIT;
            listProp = rctx.listProp;
            rctx.listProp = false;
         }
         else
            oldChildObj = null; // Need to reparse - no hint about what's next from the oldModel

         // Set in getSlotSemanticValue - need to grab this value before we call reparse on a child because it will reset it
         arrElement = rctx.arrElement;
         rctx.arrElement = false;

         ISemanticNode oldChildNode;
         if (oldChildObj instanceof ISemanticNode) {
            oldChildNode = (ISemanticNode) oldChildObj;

            /* Normally the parent parselet should not have sent the restore if the child is not going to match so this test is redundant
            int childPid = oldChildNode.getParseletId();
            if (childPid != -1 && !childParselet.producesParseletId(childPid)) {
            }
            */
         }
         else {
            // Need to reparse this node
            // TODO: performance - most of the time it's identifier<sp> so we could at least skip the identifier part even in the semantic model only by Changing the type of oldNode to Object and passing down the string value.
            // Then either with or without the ParseInStream, we could reduce the work here.  It's probably a modest optimization though so keeping things simple for now.
            // Another modest optimization might be to save a variant of StringToken in the oldModel that includes start+len of both the string value and the entire node so we pull out the entire space, comment or whatever.  That might reduce the need for the ParseInStream altogether
            if (pIn != null && !skipRestore) {
               // Reads the parselet-id, offsets, etc. to recreate this parse-node since it's not defined in the semantic model
               nestedValue = pIn.readChild(parser, childParselet, rctx);
               // Because we do not call ParentParseNode.add (which calls setSemanticValue, need to restore the old value here
               if (nestedValue instanceof IParseNode && oldChildObj != null)
                  ((IParseNode) nestedValue).setSemanticValue(oldChildObj, false, true);
               skipRestore = true;
            }
            oldChildNode = null;
         }

         if (!skipRestore)
            nestedValue = parser.restoreNext(childParselet, oldChildNode, rctx, childInherited);

         if (nestedValue instanceof ParseError) {
            if (negated) {
               // Reset back to the beginning of the sequence
               parser.changeCurrentIndex(startIndex);

               return null;
            }

            ParseError err = (ParseError) nestedValue;
            boolean isBetterError = false;
            // If we are optional and have at least some content that we've matched and looking for partial values, this optional error may be helpful
            // in stitching things together.
            if (optional && (!parser.enablePartialValues || parser.currentIndex != parser.currentErrorStartIndex)) {
               if (parser.enablePartialValues) {
                  int errEndIx = Math.max(parser.currentIndex, err.endIndex);
                  if (!Parser.isBetterError(parser.currentErrorStartIndex, parser.currentErrorEndIndex, startIndex, errEndIx, true)) {
                     parser.changeCurrentIndex(startIndex);
                     return null;
                  }
                  else {
                     isBetterError = true;
                  }
               }
               else {
                  parser.changeCurrentIndex(startIndex);
                  return null;
               }
            }

            if (parser.enablePartialValues) {
               Object pv = err.partialValue;

               // If we failed to complete this sequence, and we are in "error handling" mode, try to re-parse the previous sequence by extending it.
               // Some sequences collapse all values into a single child (e.g. blockComment).  For those, we can't support this optimization.
               if (i > 0 && value != null && value.children != null && value.children.size() >= i && pendingErrorIx == -1) {
                  int prevIx = i - 1;
                  Parselet prevParselet = parselets.get(prevIx);
                  Object oldValue = value.children.get(prevIx);
                  int saveIndex = parser.currentIndex;
                  Object ctxState = null;
                  if (oldValue instanceof IParseNode) {
                     ctxState = parser.resetCurrentIndex(((IParseNode) oldValue).getStartIndex());
                  }
                  Object newPrevValue = prevParselet.parseExtendedErrors(parser, childParselet);
                  // If we have a successful extended error parse that's longer than the previous one, we should try parsing again
                  if (newPrevValue != null && !(newPrevValue instanceof ParseError) && (oldValue == null || ((CharSequence) newPrevValue).length() > ((CharSequence) oldValue).length())) {
                     value.set(newPrevValue, childParselet, prevIx, false, parser);

                     // Go back and retry the current child parselet now that we've parsed the previous one again successfully... we know it should match because we just peeked it in the previous parselet.
                     i = i - 1;
                     continue;
                  }
                  else
                     parser.restoreCurrentIndex(saveIndex, ctxState);
               }

               if (i < parselets.size() - 1) {
                  Parselet exitParselet = parselets.get(i+1);
                  // TODO: for the simpleTag case where we are completing tagAttributes, we have / and > as following parselets where
                  // the immediate one afterwards is optional.  To handle this we can take the list of parselets following in the sequence
                  // and pass them all then peek them as a list.
                  if (!exitParselet.optional) {
                     Object extendedValue = childParselet.parseExtendedErrors(parser, exitParselet);
                     if (extendedValue != null) {
                        nestedValue = extendedValue;
                        err = nestedValue instanceof ParseError ? (ParseError) nestedValue : null;
                     }
                  }
               }

               // Question: should we always do the skipOnError processing - not just on enablePartialValues?   It could in general give more complete syntax errors
               // For now, I think the answer is no.  If we need better errors, we should parse files with syntax errors again with this mode.
               // Otherwise, it's possible we'll introduce strange ambiguities into the successfully parsed files.
               if (err != null) {
                  // Skip on error can either be set on the child or as a slot index on the parent
                  if ((childParselet.skipOnError && skipOnErrorSlot == -1) || (skipOnErrorSlot != -1 && i >= skipOnErrorSlot)) {
                     if (skipOnErrorParselet != null && (skipOnErrorEndSlot == -1 || i < skipOnErrorEndSlot)) {
                        int errorStart = parser.currentIndex;

                        Object errorRes = parser.parseNext(skipOnErrorParselet);

                        if (!(errorRes instanceof ParseError) && errorRes != null && ((CharSequence) errorRes).length() > 0) {
                           // We were able to consume at least some characters so now we decide which sequence to retry.
                           // We definitely retry this slot but if there are null optional slots in front of us, we can try to
                           // reparse them as well.
                           int resumeIx = i;
                           Object resumeSlotVal = value == null || value.children == null || value.children.size() <= i ? null : value.children.get(resumeIx);
                           while (resumeIx >= skipOnErrorSlot - 1) {
                              int nextIx = resumeIx - 1;

                              Parselet backChild = parselets.get(nextIx);
                              // Walking backwards
                              if (!backChild.optional || backChild.getLookahead())
                                 break;

                              if (value.children != null && nextIx < value.children.size()) {
                                 Object nextSlotVal = value.children.get(nextIx);
                                 // Can't retry this slot as we already parsed it - an error can be parsed but a pre-error cannot because it's an error followed by a matched result
                                 if (nextSlotVal != null && (!(nextSlotVal instanceof ErrorParseNode) || nextSlotVal instanceof PreErrorParseNode))
                                    break;
                                 resumeSlotVal = nextSlotVal;
                              }
                              else
                                 resumeSlotVal = null;
                              resumeIx = nextIx;
                           }

                           if (resumeSlotVal == null) {
                              pendingError = new ErrorParseNode(new ParseError(skipOnErrorParselet, "Expected {0}", new Object[]{this}, errorStart, parser.currentIndex), errorRes.toString());
                              pendingErrorIx = resumeIx;
                              value.addOrSet(pendingError, parselets.get(resumeIx), -1, resumeIx, true, parser);
                              resumeIx++;
                           }
                           else {
                              if (resumeSlotVal instanceof PreErrorParseNode)
                                 System.err.println("*** Bad code - trying to resume pre-errors");
                              ErrorParseNode resumeNode = (ErrorParseNode) resumeSlotVal;
                              resumeNode.errorText += errorRes.toString();
                              pendingErrorIx = resumeIx;
                           }
                           errorNode = true;

                           // Restart the loop again at slot resumeIx
                           i = resumeIx - 1;
                           continue;
                        }
                        else {
                           parser.addSkippedError(err);
                           // Record the error but move on
                           if (err.partialValue != null) {
                              nestedValue = err.partialValue;
                              parser.changeCurrentIndex(err.endIndex);
                           }
                           else
                              nestedValue = new ErrorParseNode(err, "");
                           errorNode = true;
                           err = null;
                        }
                     }
                     // No parselet - so just skip parsing this sequence and resume parsing from here
                     else {
                        parser.addSkippedError(err);
                        // Record the error but move on
                        if (err.partialValue != null) {
                           nestedValue = err.partialValue;
                           parser.changeCurrentIndex(err.endIndex);
                        }
                        else
                           nestedValue = new ErrorParseNode(err, "");
                        errorNode = true;
                        err = null;
                     }
                  }
                  else {
                     if (err.optionalContinuation) {
                        ParentParseNode errVal;
                        if (pv != null) {
                           if (value == null)
                              errVal = (ParentParseNode) newParseNode(startIndex);
                           else {
                              // Here we need to clone the semantic value so we keep track of the state that
                              // represents this error path separate from the default path which is going to match
                              // and replace the error slot here with a null
                              Object semValue = value.getSemanticValue();
                              if (semValue != null && semValue instanceof ISemanticNode) {
                                 ISemanticNode newNode = ((ISemanticNode) semValue).deepCopy(ISemanticNode.CopyAll, null);
                                 errVal = (ParentParseNode) newNode.getParseNode();
                              }
                              else
                                 errVal = value.deepCopy();
                           }
                           errVal.add(pv, childParselet, -1, i, false, parser);
                        }
                        else
                           errVal = value;
                        err = err.propagatePartialValue(errVal);
                        //err.continuationValue = true;
                        err.optionalContinuation = false;
                        nestedValue = null; // Switch this to optional
                        err = null; // cancel the error
                     }
                     // Always call this to try and extend the current error... also see if we can generate a new error
                     else if (!childParselet.getLookahead() && i >= minContentSlot && !disableExtendedErrors()) {

                        // First complete the value with any partial value from this error and nulls for everything
                        // else.
                        if (value == null)
                           value = (ParentParseNode) newParseNode(startIndex);
                        value.add(pv, childParselet, -1, i, false, parser);
                        // Add these null slots so that we do all of the node processing
                        for (int k = i+1; k < numParselets; k++)
                           value.add(null, parselets.get(k), -1, k, false, parser);

                        // Now see if this computed value extends any of our most specific errors.  If so, this error
                        // can be used to itself extend other errors based on the EOF parsing.
                        if ((extendsPartialValue(parser, childParselet, value, anyContent, startIndex, err) && anyContent) || err.eof || pv != null || isBetterError) {
                           if (!err.eof || !value.isEmpty()) {
                              if (optional && value.isEmpty())
                                 return null;
                              // Keep the origin error description so the right info is in the error presented to the user
                              ParseError newError = parsePartialError(parser, value, err, childParselet, err.errorCode, err.errorArgs);
                              return newError;
                           }
                        }
                     }
                  }
               }
            }

            // Unless the error was cancelled or skip-on-error was true, i.e. so we logged the error and are moving on.
            if (err != null) {
               if (optional) {
                  parser.changeCurrentIndex(startIndex);
                  return null;
               }

               if (reportError)
                  return parseError(parser, childParselet, "Expected: {0}", this);
               else
                  parser.changeCurrentIndex(startIndex);
               return nestedValue;
            }
         }

         if (value == null)
            value = newParseNode(startIndex);

         // Increment the current array index if we just successfully parsed an index in the current array value
         // when pIn is null, we may not parse a child - nestedValue == null and so that should not count as an array index
         if (arrElement && !skipRestore && (pIn != null || nestedValue != null))
            rctx.arrIndex++;

         // After we've completed the parse for a list property, need to clear out the list index so it's not inherited
         if (listProp)
            rctx.arrIndex = 0;

         if (!childParselet.getLookahead()) {
            if (nestedValue instanceof IParseNode) {
               IParseNode nestedParseNode = (IParseNode) nestedValue;
               // If the last parselet we parse which has content is not an error we do not propagate the error for the
               // containing node.  We want to ignore internal errors but catch when this sequence ends in an error - i.e.
               // is some kind of fragment that must be reparsed, even if the beginning content has not changed.
               if (!nestedParseNode.isEmpty())
                  errorNode = nestedParseNode.isErrorNode();
            }
            if (pendingErrorIx != -1) {
               if (nestedValue != null) {
                  if (pendingErrorIx != i) {
                     value.addOrSet(nestedValue, childParselet, -1, i, false, parser);
                  }
                  else {
                     if (nestedValue instanceof IParseNode) {
                        if (nestedValue instanceof ErrorParseNode)
                           pendingError.errorText += nestedValue.toString();
                        else if (!(nestedValue instanceof ParseError)) {
                           // Need to somehow insert the errorText as an ErrorParseNode into the ParentParseNode or ParseNode?  Or add a "post-value" onto error node?
                           PreErrorParseNode pepn = new PreErrorParseNode(pendingError.error, pendingError.errorText, (IParseNode) nestedValue);
                           nestedValue = pepn;
                           value.addOrSet(nestedValue, childParselet, -1, i, false, parser);
                        }
                     }
                     else
                        pendingError.errorText += nestedValue.toString();
                  }
               }
               else
                  value.addOrSet(null, childParselet, -1, i, false, parser);
            }
            else
               value.add(nestedValue, childParselet, -1, i, false, parser);
         }
         else if (slotMapping != null) // Need to preserve the specific index in the results if we have a mapping
            value.add(null, childParselet, -1, i, true, parser);

         if (nestedValue != null || childParselet.isNullValid())
            anyContent = minContentSlot <= i;
      }

      if (errorNode && value != null)
         value.setErrorNode(true);

      if (lookahead) {
         // Reset back to the beginning of the sequence
         parser.changeCurrentIndex(startIndex);
      }

      //rctx.arrIndex = saveArrIndex;

      if (anyContent || acceptNoContent) {
         String customError;
         if ((customError = accept(parser.semanticContext, value, startIndex, parser.currentIndex)) != null) {
            if (negated)
               return value;
            return parseError(parser, customError, value, this);
         }

         if (negated)
            return parseError(parser, "Negated rule: value {0} matched rule: {1}", value, this);
         if (oldNode != null) // TODO: add !inherited here?  But then we need to only set inherited for when the mapping is "*" and not "." (now we set it for anytime childNode = oldNode)
            restoreOldNode(value, oldNode);
         return value;
      }
      else {
         return null;
      }
   }

   public void saveParse(IParseNode pn, ISemanticNode oldNode, SaveParseCtx sctx) {
      if (repeat) {
         saveParseRepeatingSequence(pn, oldNode, sctx);
         return;
      }
      ParseOutStream pOut = sctx.pOut;

      if (pn == null)
         System.out.println("*** Null parse node for saveParse");

      Object sv = pn.getSemanticValue();
      ISemanticNode sn = null;
      if (sv instanceof ISemanticNode)
         sn = (ISemanticNode) sv;
      else if (sv == null)
         sn = oldNode;

      if (pn instanceof ErrorParseNode)
         throw new IllegalArgumentException("Failed to save parse tree due to parse error");
      ParentParseNode ppn = (ParentParseNode) pn;

      boolean arrElement;

      int numParselets = parselets.size();

      List pnChildren = ppn.children;

      // TODO: fix this by writing multiple mask UInts if someone needs a grammar like this
      if (numParselets >= 32)
         throw new IllegalArgumentException("Unable to save sequence with more than 31 parselets");

      int mask = getExcludeMask(pnChildren);
      pOut.writeUInt(mask);

      int bit = 1;
      int ct = 0;
      for (int i = 0; i < numParselets; i++) {
         Parselet childParselet = parselets.get(i);

         // These are not added to the output parse node so need to skip the in ct but keep them in i
         if (childParselet.getDiscard() || childParselet.getLookahead()) {
            if (slotMapping != null) {
               bit = bit << 1;
               ct++;
            }
            continue;
         }

         int nextBit = bit << 1;
         if ((mask & bit) != 0) {
            bit = nextBit;
            ct++;
            continue;
         }
         bit = nextBit;

         Object childPN = pnChildren.get(ct);
         ct++;

         boolean skipRestore = false;
         boolean listProp = false;
         Object oldChildObj;
         if (sn != null) {
            oldChildObj = getSlotSemanticValue(i, sn, sctx);
            if (oldChildObj == SKIP_CHILD) {
               skipRestore = true;
               oldChildObj = null;
            }

            // If we are passing the parent object through to the child, we will have already matched the parseletId in the semantic node when the slot uses "*" (produced from the top parselet using properties set from the child)
            // and not "." (produced from the child)
            listProp = sctx.listProp;
            sctx.listProp = false;
         }
         else
            oldChildObj = null; // Need to reparse - no hint about what's next from the oldModel

         // Set in getSlotSemanticValue - need to grab this value before we call reparse on a child because it will reset it
         arrElement = sctx.arrElement;
         sctx.arrElement = false;

         IParseNode childPNNode = childPN instanceof IParseNode ? (IParseNode) childPN : null;

         // There's a tricky distinction here.  If there's a string oldChildObj, it could be a case like the typeIdentifier in NewExpression which converts from 'PrimitiveType' to String.  If we use the child semantic value,
         // the restore will not be able to match it and uses pIn.readChild instead of childParselet.restore.  But if oldChildObj == null, it's a case where we are meant to propagate the semantic value.
         if (oldChildObj instanceof ISemanticNode || (oldChildObj == null && (childPNNode != null && childPNNode.getSemanticValue() instanceof ISemanticNode))) {
            ISemanticNode oldChildNode = oldChildObj instanceof ISemanticNode ? (ISemanticNode) oldChildObj : null;
            if (childPNNode != null) {
               Object pnChildSV = childPNNode.getSemanticValue();
               if (pnChildSV instanceof ISemanticNode)
                  oldChildNode = (ISemanticNode) pnChildSV;
            }
            childParselet.saveParse(childPNNode, oldChildNode, sctx);

            /* Normally the parent parselet should not have sent the restore if the child is not going to match so this test is redundant
            int childPid = oldChildNode.getParseletId();
            if (childPid != -1 && !childParselet.producesParseletId(childPid)) {
            }
            */
         }
         else {
            Parselet pnParselet = childParselet;
            boolean needsParseletType = false;
            if (childPNNode != null) {
               pnParselet = childPNNode.getParselet();
               needsParseletType = true;
            }

            // Need to reparse this node
            // TODO: performance - most of the time it's identifier<sp> so we could at least skip the identifier part even in the semantic model only by Changing the type of oldNode to Object and passing down the string value.
            // Then either with or without the ParseInStream, we could reduce the work here.  It's probably a modest optimization though so keeping things simple for now.
            // Another modest optimization might be to save a variant of StringToken in the oldModel that includes start+len of both the string value and the entire node so we pull out the entire space, comment or whatever.  That might reduce the need for the ParseInStream altogether
            if (!skipRestore) {
               pOut.saveChild(pnParselet, childPN, sctx, needsParseletType);
            }
         }

         // Increment the current array index if we just successfully processed an index in the current array value
         if (arrElement) {
            //sctx.arrIndex++;
         }

         // After we've completed the save for a list property, need to clear out the list index so it's restored back to the value expected the parent
         if (listProp)
            sctx.arrIndex = 0;
      }
   }

   private int getExcludeMask(List<Object> pnChildren) {
      int ct = 0;
      int mask = 0;
      int numParselets = parselets.size();
      for (int i = 0; i < numParselets; i++) {
         Parselet p = parselets.get(i);
         if (slotMapping == null && (p.getDiscard() || p.getLookahead())) {
            continue;
         }
         if (pnChildren == null || pnChildren.size() <= ct || pnChildren.get(ct) == null)
            mask |= 1 << ct;
         ct++;
      }
      return mask;
   }

   private Parselet getExitParselet(int ix) {
      if (!alwaysExtendErrors || ix == parselets.size() - 1)
         return null;
      Parselet child = parselets.get(ix);
      if (child instanceof NestedParselet && ((NestedParselet) child).skipOnErrorParselet != null)
         return parselets.get(ix+1);
      return null;
   }

   private boolean oldChildMatches(Object oldChildParseNode, Parselet childParselet, DiffContext dctx) {
      boolean matches = true;
      // Since lookahead nodes do not store their result in the parseNode tree at all we have to reparse them always.
      if (childParselet.lookahead)
         return false;
      if (oldChildParseNode instanceof IParseNode) {
         IParseNode oldChildPN = (IParseNode) oldChildParseNode;
         if (!childParselet.producesParselet(oldChildPN.getParselet())) {
            //oldChildParseNode = null;
            matches = false;
         }
         // We might be in the same again region for the new text but still in the changed region for the old text so do not include those children
         else if (dctx.sameAgain && oldChildPN.getOrigStartIndex() < dctx.endChangeOldOffset) {
            //oldChildParseNode = null;
            matches = false;
         }
         else if (oldChildParseNode instanceof ErrorParseNode) {
            matches = false;
         }
      }
      // If we did not match a required parselet we need to reparse
      else if (oldChildParseNode == null && !childParselet.optional)
         return false;
      return matches;
   }

   protected static final String SKIP_CHILD = "SkipChildParseletSentinel";

   protected Object getReparseChildNode(Object oldParent, int ix, boolean forceReparse) {
      if (oldParent == null)
         return null;
      if (oldParent instanceof ParseNode) {
         return null;
      }
      if (oldParent instanceof ErrorParseNode) {
         return null;
      }
      if (oldParent instanceof IString || oldParent instanceof String)
         return null;
      ParentParseNode oldpn = (ParentParseNode) oldParent;
      if (ix < oldpn.children.size())
         return oldpn.children.get(ix);
      return null;
   }

   private boolean extendsPartialValue(Parser parser, Parselet childParselet, ParentParseNode value, boolean anyContent, int startIndex, ParseError childError) {
      if (value == null)
         return false;

      //if (parser.currentIndex == parser.currentErrorStartIndex) {
         Object nsv = ParseUtil.nodeToSemanticValue(value);
         if (nsv instanceof JavaSemanticNode) {
            JavaSemanticNode node = (JavaSemanticNode) nsv;
            for (int i = 0; i < parser.currentErrors.size(); i++) {
               ParseError err = parser.currentErrors.get(i);
               Object esv = ParseUtil.nodeToSemanticValue(err.partialValue);
               if (esv != null) {
                  if (node == esv || (anyContent && node.applyPartialValue(esv))) {
                     if (!value.isEmpty()) {
                        // Reuse the same error with the new value (unless we are caching in which case we create a new
                        // error).  This node will also call parseEOF error
                        // but that error will be ignored caused it does not end as far back as this one.
                        // One potential benefit of doing it this way is that if more nodes end up with better
                        // final matches we'll hang onto them.  If we use the new error it would replace any other
                        // existing errors.
                        err = err.propagatePartialValue(value);
                        err.startIndex = parser.currentIndex;

                        return true;
                     }
                  }
               }
               else if (childParselet == err.parselet) {
                  err = err.propagatePartialValue(value);
                  err.startIndex = parser.currentIndex;
                  return true;
               }
            }
         }
         // The current value is a list like the return from class body and the error in this case should be the field, member etc. which is inside of it.
         else if (nsv instanceof SemanticNodeList) {
            SemanticNodeList snl = (SemanticNodeList) nsv;
            int snlSize = snl.size();
            int startIx = snl.getParseNode().getStartIndex();
            ISemanticNode lastListSemValue = null;
            for (int i = 0; i < snlSize; i++) {
               Object elem = snl.get(i);
               if (elem instanceof ISemanticNode) {
                  ISemanticNode elemNode = (ISemanticNode) elem;
                  int newIx = ((ISemanticNode) elem).getParseNode().getStartIndex();

                  if (newIx > startIx)
                     startIx = newIx;

                  if (i == snlSize - 1) {
                     lastListSemValue = elemNode;
                  }
               }
            }

            /** Ideally we can find a node in the list which can be extended with the value from this list */
            boolean res = false;
            if (anyContent && lastListSemValue instanceof JavaSemanticNode) {
               for (int i = 0; i < parser.currentErrors.size(); i++) {
                  JavaSemanticNode lastListSemNode = (JavaSemanticNode) lastListSemValue;
                  ParseError err = parser.currentErrors.get(i);
                  if (err.partialValue instanceof IParseNode) {
                     IParseNode errParseNode = (IParseNode) err.partialValue;
                     Object errSemValue = errParseNode.getSemanticValue();

                     if (lastListSemNode.applyPartialValue(errSemValue)) {
                        res = true;
                        err.partialValue = value;
                     }
                  }
               }
            }
            if (res)
               return true;

            /**
             * As another attempt to extend the lists we look for an element that can be added to the list.
             * TODO: this case should be cleaned up or potentially removed in favor of the above approach?
             */
         /*
            for (int i = 0; i < parser.currentErrors.size(); i++) {
               ParseError err = parser.currentErrors.get(i);
               Object esv = ParseUtil.nodeToSemanticValue(err.partialValue);
               if (esv != null) {
                  if (esv instanceof ISemanticNode) {
                     ISemanticNode errSemNode = (ISemanticNode) esv;
                     IParseNode errParseNode = errSemNode.getParseNode();
                     if (errSemNode.getParentNode() == null && (errParseNode != null && errParseNode.getStartIndex() > startIx)) {
                        if (!snl.contains(errSemNode) && errSemNode != snl) {
                           if (!(errSemNode instanceof List)) {
                              if (anyContent)
                                 snl.add(errSemNode);
                           }
                        }
                     }
                  }
                  /*
                  else if (PString.isString(esv)) {
                     if (lastListVal instanceof JavaSemanticNode) {
                        JavaSemanticNode lastNode = ((JavaSemanticNode) lastListVal);
                        if (lastNode.applyPartialValue(esv))
                           return true;
                     }
                  }
                  */
                  /*
                  //err.partialValue = snl;
                  // TODO: Seems like we should not always be returning true here but instead should have
                  // a mechanism to be sure this error is extended - like check if the parselet types match and
                  // if the lists match or overla - like check if the parselet types match or the lists
                  // are the same or we can tell this is an element of the new list.
                  //if (anyContent)
                  //   res = true;
                  /*
                  */
               //}
           // }
            return res;
         }
         else if (parameterType == ParameterType.INHERIT) {
            if (parser.currentIndex <= parser.currentErrorStartIndex && anyContent) {
               return true;
            }
         }

      // If the combination of this new error and the child error ends up being equal to or better than the current match, we extend
      if (anyContent && Parser.isBetterError(parser.currentErrorStartIndex, parser.currentErrorEndIndex, startIndex, childError.endIndex, true)) {
         if (optional)
            return false;
         return true;
      }

      //}
      return false;
   }

   private Object parseRepeatingSequence(Parser parser, boolean extendedErrors, Parselet exitParselet) {
      ParentParseNode value = null;
      int startIndex = parser.currentIndex;
      int lastMatchIndex;
      boolean matched;
      boolean matchedAny = false;
      int i;
      ParseError lastError = null;
      Parselet childParselet = null;
      int numParselets;
      ArrayList<Object> matchedValues;
      ArrayList<Object> errorValues = null;

      if (trace)
         trace = trace;

      boolean anyContent;
      boolean extendedErrorMatches = false;

      do {
         lastMatchIndex = parser.currentIndex;

         matchedValues = null;

         matched = true;
         anyContent = false;
         numParselets = parselets.size();
         for (i = 0; i < numParselets; i++) {
            childParselet = parselets.get(i);
            Object nestedValue = parser.parseNext(childParselet);
            if (nestedValue instanceof ParseError) {
               matched = false;
               errorValues = matchedValues;
               matchedValues = null;
               lastError = (ParseError) nestedValue;
               break;
            }

            if (matchedValues == null)
               matchedValues = new ArrayList<Object>(); // TODO: performance set the init-size here?

            if (nestedValue != null && i >= minContentSlot)
               anyContent = true;

            matchedValues.add(nestedValue);
         }
         if (i == numParselets) {
            // We need at least one non-null slot in a sequence for it to match
            if (anyContent) {
               if (matchedValues != null) {
                  if (value == null)
                     value = (ParentParseNode) newParseNode(lastMatchIndex);
                  int numMatchedValues = matchedValues.size();
                  for (i = 0; i < numMatchedValues; i++) {
                     Object nv = matchedValues.get(i);
                     //if (nv != null) // need an option to preserve nulls?
                     value.add(nv, parselets.get(i), -1, i, false, parser);
                  }
               }
               matched = true;
               matchedAny = true;
            }
            else
               matched = false;
         }
         else if (!matched) {
            if (extendedErrors) {
               // Restore the current index back to the lastMatchIndex - back track over any partial matches in the sequence for this entry
               parser.changeCurrentIndex(lastMatchIndex);
               lastError = null;

               // Consume the next skip token if we have not hit the exit, then retry
               if (!exitParselet.peek(parser)) {
                  int errorStart = parser.currentIndex;
                  // This will consume whatever it is that we can't parse until we get to the next statement.  We have to be careful with the
                  // design of the skipOnErrorParselet so that it leaves us in the state for the next match on this choice.  It should not breakup
                  // an identifier for example.
                  Object errorRes = parser.parseNext(skipOnErrorParselet);
                  if (errorRes instanceof ParseError) {
                     // We never found the exitParselet - i.e. > so the parent sequence will fail just like it did before.
                     // When this method returns null, we go with the result we produced in the first call to parseRepeatingSequence method.
                     return null;
                  }

                  if (value == null)
                     value = (ParentParseNode) newParseNode(lastMatchIndex);
                  value.add(new ErrorParseNode(new ParseError(skipOnErrorParselet, "Expected {0}", new Object[]{this}, errorStart, parser.currentIndex), errorRes.toString()), skipOnErrorParselet, -1, -1, true, parser);
                  extendedErrorMatches = true;
                  matched = true;
               }
               else {   // Found the exit parselet is next in the stream so we successfully consumed some error nodes so reset the lastMatchIndex to include them
                  lastMatchIndex = parser.currentIndex;
                  matchedAny = extendedErrorMatches;
               }
            }
         }
      } while (matched);

      if (!matchedAny) {
         if (parser.enablePartialValues && !lookahead && lastError != null) {
            Object pv = lastError.partialValue;
            if ((pv != null || !optional) && !childParselet.getLookahead()) {
               if (errorValues == null)
                  errorValues = new ArrayList<Object>();
               errorValues.add(pv);
               // Add these null slots so that we do all of the node processing
               value = newRepeatSequenceResult(errorValues, value, lastMatchIndex, parser);
               return parsePartialError(parser, value, lastError, childParselet, "Partial array match: {0} ", this);
            }
               /* A bad case for the optional continuation is Foo.this - which needs to only match "Foo" as the identifier expression
                  and cannot match 'foo.' with the optional continuation, or else it consumes the '.' which won't match the '.this' selector
                  later on.  It seems like a bad idea to by default match an error case which is optional.  Instead, those specific error cases
                  should be ignored with skipOnErrorSlot or some other way.
            if (pv == null && optional && anyContent) {
               System.out.println("***");
               value = newRepeatSequenceResult(errorValues, value, lastMatchIndex, parser);
               ParseError err = parsePartialError(parser, value, lastError, childParselet, "Optional continuation: {0} ", this);
               err.optionalContinuation = true;
               return err;
            }
               */
         }

         if (optional) {
            parser.changeCurrentIndex(startIndex);
            return null;
         }
         return parseError(parser, "Expecting one or more of {0}", this);
      }
      else {
         if (parser.enablePartialValues && !lookahead && lastError != null) {
            Object pv = lastError.partialValue;
            // IF we partially matched the next sequence, we need to indicate the partial value
            // for this error if it's not already set.
            if (pv == null) {
               if (errorValues != null) {
                  ParentParseNode errVal = cloneParseNode(((ParentParseNode) value)) ;
                  lastError = lastError.propagatePartialValue(newRepeatSequenceResult(errorValues, errVal, lastMatchIndex, parser));
               }
               else
                   lastError = lastError.propagatePartialValue(value);
               // Instead of producing a value for this fragment and messing up the parse node for what might be a valid match
               // we mark this error as a "continuation".  This flag can then be used in the suggestCompletions
               // method to take into account the fact that there's an extra '.' at the end (or whatever)
               lastError.continuationValue = true;
            }
            /*
            if (pv != null && !childParselet.getLookahead()) {
               value = (ParentParseNode) newParseNode(lastMatchIndex);
               if (errorValues == null)
                  errorValues = new ArrayList<Object>();
               errorValues.add(pv);
               // Add these null slots so that we do all of the node processing
               for (int k = 0; k < numParselets; k++) {
                  Object nv;
                  if (errorValues.size() > k)
                     nv = errorValues.get(k);
                  else
                     nv = null;
                  value.add(nv, parselets.get(k), k, false, parser);
               }
               return parsePartialError(parser, value, lastError, childParselet, "Partial array match: {0} ", this);
            }
            */
         }
         parser.changeCurrentIndex(lastMatchIndex);
      }

      String customError;
      if ((customError = accept(parser.semanticContext, value, startIndex, parser.currentIndex)) != null)
         return parseError(parser, customError, value, this);

      if (lookahead)
         parser.changeCurrentIndex(startIndex);
      return value;
   }

   private Object reparseRepeatingSequence(Parser parser, boolean extendedErrors, Parselet exitParselet, Object oldParseNode, DiffContext dctx, boolean forceReparse) {
      ParentParseNode value = null;
      int startIndex = parser.currentIndex;
      int lastMatchIndex;
      boolean matched;
      boolean matchedAny = false;
      int i;
      ParseError lastError = null;
      Parselet childParselet = null;
      int numParselets;
      int numMatchedValues = 0;
      ArrayList<Object> matchedValues;
      ArrayList<Object> errorValues = null;
      Object oldChildParseNode = null;

      if (trace)
         trace = true;

      ParentParseNode oldParent = oldParseNode instanceof ParentParseNode ? (ParentParseNode) oldParseNode : null;
      // This parent is not for our parselet
      if (oldParent != null && !producesParselet(oldParent.parselet)) {
         oldParent = null;
         forceReparse = true;
      }

      boolean anyContent;
      boolean extendedErrorMatches = false;

      int newChildCount = 0;
      int svCount = 0;

      do {
         lastMatchIndex = parser.currentIndex;

         matchedValues = null;
         numMatchedValues = 0;

         matched = true;
         anyContent = false;
         numParselets = parselets.size();
         for (i = 0; i < numParselets; i++) {
            childParselet = parselets.get(i);

            int childIx = newChildCount + numMatchedValues;

            if (oldParent == null || oldParent.children == null) {
               oldChildParseNode = null;
               forceReparse = true;
            }
            else if (oldParent.children.size() > childIx) {
               oldChildParseNode = oldParent.children.get(childIx);

               if (dctx.sameAgain && oldChildParseNode instanceof IParseNode) {
                  IParseNode oldChildPN = (IParseNode) oldChildParseNode;
                  int oldOrigStartIx = oldChildPN.getOrigStartIndex();
                  if (oldOrigStartIx < dctx.endChangeOldOffset) {
                     oldChildParseNode = null;
                     forceReparse = true;
                  }
                  // If we have an old node and we are back in the unchanged text, it's possible it starts after the current index - in which case we need to force a reparse
                  // because otherwise when parsing it, if there's a false partial match with the old value we'll just accept it because we are not in the changed region.
                  else {
                     int oldChildLen = oldChildPN.length();
                     int oldStartNew = oldOrigStartIx + dctx.getNewOffsetForOldPos(oldOrigStartIx + oldChildLen);
                     if (oldStartNew > parser.currentIndex) {
                        oldChildParseNode = null;
                        forceReparse = true;
                     }
                  }
               }
            }
            else {
               oldChildParseNode = null;
               forceReparse = true;
            }

            Object nestedValue = parser.reparseNext(childParselet, oldChildParseNode, dctx, forceReparse, getExitParselet(i));
            if (nestedValue instanceof ParseError) {
               matched = false;
               errorValues = matchedValues;
               matchedValues = null;
               lastError = (ParseError) nestedValue;
               break;
            }

            if (matchedValues == null)
               matchedValues = new ArrayList<Object>(); // TODO: performance set the init-size here?

            if (nestedValue != null && i >= minContentSlot)
               anyContent = true;

            matchedValues.add(nestedValue);
            numMatchedValues++;
         }
         if (i == numParselets) {
            // We need at least one non-null slot in a sequence for it to match
            if (anyContent) {
               if (matchedValues != null) {
                  if (value == null)
                     value = resetOldParseNode(forceReparse ? null : oldParent, lastMatchIndex, true, false);
                  for (i = 0; i < numMatchedValues; i++) {
                     Object nv = matchedValues.get(i);
                     oldChildParseNode = oldParent == null || i >= oldParent.children.size() ? null : oldParent.children.get(i);
                     //if (nv != null) // need an option to preserve nulls?
                     // Only remove the extra nodes on the last value - that uses parser.currentIndex which has advanced up to after the last matched value
                     boolean removeExtraNodes = i == numMatchedValues - 1;
                     svCount += value.addForReparse(nv, parselets.get(i), svCount, newChildCount++, i, false, parser, oldChildParseNode, dctx, removeExtraNodes, true);
                  }
               }
               matched = true;
               matchedAny = true;
            }
            else
               matched = false;
         }
         else if (!matched) {
            if (extendedErrors) {
               // Restore the current index back to the lastMatchIndex - back track over any partial matches in the sequence for this entry
               dctx.changeCurrentIndex(parser, lastMatchIndex);
               lastError = null;

               // Consume the next skip token if we have not hit the exit, then retry
               if (!exitParselet.peek(parser)) {
                  int errorStart = parser.currentIndex;
                  // TODO: is oldChildParseNode here right?
                  oldChildParseNode = oldParent == null ? null : oldParent.children.get(oldParent.children.size()-1);
                  // This will consume whatever it is that we can't parse until we get to the next statement.  We have to be careful with the
                  // design of the skipOnErrorParselet so that it leaves us in the state for the next match on this choice.  It should not breakup
                  // an identifier for example.
                  Object errorRes = parser.reparseNext(skipOnErrorParselet, oldChildParseNode, dctx, forceReparse, null);
                  if (!(errorRes instanceof ParseError) && errorRes != null && !(errorRes instanceof ErrorParseNode)) {
                     if (value == null) {
                        value = resetOldParseNode(forceReparse ? null : oldParent, lastMatchIndex, false, false);
                     }
                     svCount += value.addForReparse(new ErrorParseNode(new ParseError(skipOnErrorParselet, "Expected {0}", new Object[]{this}, errorStart, parser.currentIndex), errorRes.toString()), skipOnErrorParselet, svCount, newChildCount, -1, true, parser, oldChildParseNode, dctx, true, true);
                     newChildCount++;
                     extendedErrorMatches = true;
                     matched = true;
                  }
                  // else - we did not find the exit parselet.  We'll just match as normal - the parent will fail but we still need to return our valid match so it is not cached incorrectly
               }
               else {   // Found the exit parselet is next in the stream so we successfully consumed some error nodes so reset the lastMatchIndex to include them
                  lastMatchIndex = parser.currentIndex;
                  matchedAny = matchedAny || extendedErrorMatches;
               }
            }
         }
      } while (matched);

      if (!matchedAny) {
         if (parser.enablePartialValues && !lookahead && lastError != null) {
            Object pv = lastError.partialValue;
            if ((pv != null || !optional) && !childParselet.getLookahead()) {
               if (errorValues == null)
                  errorValues = new ArrayList<Object>();
               errorValues.add(pv);
               // Temporarily advance the index so parser.currentIndex is where we ended parsing this error.  We use parser.currentIndex here to
               // determine whether to insert or replace the parse node.  We'll reset current index in parsePartialError anyway.
               dctx.changeCurrentIndex(parser, lastError.endIndex);
               // Add these null slots so that we do all of the node processing
               value = newRepeatSequenceResultReparse(errorValues, value, svCount, newChildCount, lastMatchIndex, parser, oldParent, dctx);
               newChildCount += errorValues.size();

               // If we are reparsing once we've added the error values, we should remove any thing left over that was not reparsed.
               if (oldParent == value) {
                  removeChildrenForReparse(parser, value, svCount, newChildCount);
               }

               return reparsePartialError(parser, dctx, value, lastError, childParselet, "Partial array match: {0} ", this);
            }
               /*
                * This case caused problems with Foo.this - where matching Foo. did not work.
            if (pv == null && optional && anyContent) {
               System.out.println("***");
               value = newRepeatSequenceResultReparse(errorValues, value, svCount, newChildCount, lastMatchIndex, parser, oldParent, dctx);
               newChildCount += errorValues.size();

               if (oldParent == value) {
                  removeChildrenForReparse(parser, value, svCount, newChildCount);
               }

               ParseError err = reparsePartialError(parser, dctx, value, lastError, childParselet, "Optional continuation: {0} ", this);
               //err.optionalContinuation = true;
               return err;
            }
               */

         }

         if (optional) {
            dctx.changeCurrentIndex(parser, startIndex);
            return null;
         }
         return reparseError(parser, dctx, "Expecting one or more of {0}", this);
      }
      else {
         if (parser.enablePartialValues && !lookahead && lastError != null) {
            Object pv = lastError.partialValue;
            // IF we partially matched the next sequence, we need to indicate the partial value
            // for this error if it's not already set.
            if (pv == null) {
               if (errorValues != null) {
                  ParentParseNode errVal = cloneParseNode(((ParentParseNode) value)) ;
                  lastError = lastError.propagatePartialValue(newRepeatSequenceResultReparse(errorValues, errVal, svCount, newChildCount, lastMatchIndex, parser, oldParent, dctx));
               }
               else
                  lastError = lastError.propagatePartialValue(value);
               // Instead of producing a value for this fragment and messing up the parse node for what might be a valid match
               // we mark this error as a "continuation".  This flag can then be used in the suggestCompletions
               // method to take into account the fact that there's an extra '.' at the end (or whatever)
               lastError.continuationValue = true;
            }
            /*
            if (pv != null && !childParselet.getLookahead()) {
               value = (ParentParseNode) newParseNode(lastMatchIndex);
               if (errorValues == null)
                  errorValues = new ArrayList<Object>();
               errorValues.add(pv);
               // Add these null slots so that we do all of the node processing
               for (int k = 0; k < numParselets; k++) {
                  Object nv;
                  if (errorValues.size() > k)
                     nv = errorValues.get(k);
                  else
                     nv = null;
                  value.add(nv, parselets.get(k), k, false, parser);
               }
               return parsePartialError(parser, value, lastError, childParselet, "Partial array match: {0} ", this);
            }
            */
         }
         dctx.changeCurrentIndex(parser, lastMatchIndex);
      }

      if (oldParent == value) {
         removeChildrenForReparse(parser, value, svCount, newChildCount);
         Object sv = value.getSemanticValue();
         // A tricky case - the semantic value in a repeat node can be built from more than one parse node - e.g.
         // switchBlockStatementGroups contains switchLabels.  During reparse, we can remove a statement which means
         // the switchLabels is reconfigured - it might go from 1 value to 3 labels if you remove the statement separating
         // them for example.  Here we cull out any switchLabels remaining after the ones we matched
         // in the old semantic value.
         if (repeat && sv instanceof List) {
            List svList = (List) sv;
            for (int svIx = svCount; svList.size() > svIx; ) {
               Object nextSv = svList.get(svIx);
               if (nextSv instanceof ISemanticNode) {
                  ISemanticNode nextSN = (ISemanticNode) nextSv;
                  IParseNode nextPN = (IParseNode) nextSN.getParseNode();
                  // Need to see if this semantic value came from a child of this parselet.  If so, and we did not
                  // reparse it this time, it's an "extra" semantic value that needs to be culled.
                  if (getParseletSlotIx(nextPN.getParselet()) != -1)
                     svList.remove(svIx);
                  else
                     break;
               }
               else
                  break;
            }
         }
      }

      String customError;
      if ((customError = accept(parser.semanticContext, value, startIndex, parser.currentIndex)) != null)
         return reparseError(parser, dctx, customError, value, this);

      if (lookahead)
         dctx.changeCurrentIndex(parser, startIndex);
      return value;
   }

   private Object restoreRepeatingSequence(Parser parser, ISemanticNode oldModel, RestoreCtx rctx, boolean extendedErrors, Parselet exitParselet) {
      ParentParseNode value = null;
      int startIndex = parser.currentIndex;
      int lastMatchIndex;
      boolean matched;
      boolean matchedAny = false;
      int i;
      ParseError lastError = null;
      Parselet childParselet = null;
      int numParselets;
      ArrayList<Object> matchedValues;
      ArrayList<Object> errorValues = null;

      if (trace)
         trace = trace;

      boolean anyContent;
      boolean extendedErrorMatches = false;
      int listSize = oldModel instanceof List ? ((List) oldModel).size() : -1;

      ParseInStream pIn = rctx.pIn;

      int repeatCount = pIn == null ? -1 : pIn.readUInt();

      // Skip the parse loop entirely if we matched nothing and break out of the loop when we have parsed the expected number of parse-nodes
      matched = repeatCount != 0; // true for either "not known" or we have entries to parse

      //int saveArrIndex = rctx.arrIndex;
      //rctx.arrIndex = 0;
      lastMatchIndex = parser.currentIndex;

      while (matched) {
         lastMatchIndex = parser.currentIndex; // TODO: move this one to the end of the loop?  It's already set there but in an 'if' but need to be careful because matched might not be at the end of the loop.

         matchedValues = null;

         anyContent = false;
         boolean arrElement;
         numParselets = parselets.size();
         for (i = 0; i < numParselets; i++) {
            childParselet = parselets.get(i);
            Object oldChildObj = getArraySemanticValue(oldModel, i, rctx);
            ISemanticNode oldChildNode;
            boolean skipRestore = false;

            // Set in getArraySemanticValue if oldChildObj is an element of the list oldModel - TODO: this should be a return value from getArraySemanticCtx to avoid the save/restore here
            arrElement = rctx.arrElement;
            rctx.arrElement = false;

            Object nestedValue = null;

            if (oldChildObj instanceof ISemanticNode) {
               oldChildNode = (ISemanticNode) oldChildObj;
            }
            else {
               if (pIn != null) {
                  nestedValue = pIn.readChild(parser, childParselet, rctx);
                  skipRestore = true;
               }
               oldChildNode = null;
            }

            int saveArrIndex = 0;
            if (arrElement) {
               saveArrIndex = rctx.arrIndex;
               rctx.arrIndex = 0;
            }

            if (!skipRestore)
               nestedValue = parser.restoreNext(childParselet, oldChildNode, rctx, false);

            if (arrElement) {
               rctx.arrIndex = saveArrIndex;
            }

            if (nestedValue instanceof ParseError) {
               matched = false;
               errorValues = matchedValues;
               matchedValues = null;
               lastError = (ParseError) nestedValue;
               break;
            }

            if (matchedValues == null)
               matchedValues = new ArrayList<Object>(); // TODO: performance set the init-size here?

            if (nestedValue != null && i >= minContentSlot)
               anyContent = true;

            matchedValues.add(nestedValue);

            // Increment the current array index if we just successfully parsed an index in the current array value
            if (arrElement)
               rctx.arrIndex++;
         }
         if (i == numParselets) {
            // We need at least one non-null slot in a sequence for it to match
            if (anyContent) {
               if (matchedValues != null) {
                  if (value == null)
                     value = (ParentParseNode) newParseNode(lastMatchIndex);
                  int numMatchedValues = matchedValues.size();
                  for (i = 0; i < numMatchedValues; i++) {
                     Object nv = matchedValues.get(i);
                     //if (nv != null) // need an option to preserve nulls?
                     value.add(nv, parselets.get(i), -1, i, false, parser);
                  }
               }
               matched = true;
               matchedAny = true;
            }
            else
               matched = false;
         }
         else if (!matched) {
            if (extendedErrors) {
               // Restore the current index back to the lastMatchIndex - back track over any partial matches in the sequence for this entry
               parser.changeCurrentIndex(lastMatchIndex);
               lastError = null;

               // Consume the next skip token if we have not hit the exit, then retry
               if (!exitParselet.peek(parser)) {
                  int errorStart = parser.currentIndex;
                  // This will consume whatever it is that we can't parse until we get to the next statement.  We have to be careful with the
                  // design of the skipOnErrorParselet so that it leaves us in the state for the next match on this choice.  It should not breakup
                  // an identifier for example.
                  Object errorRes = parser.parseNext(skipOnErrorParselet);
                  if (errorRes instanceof ParseError) {
                     // We never found the exitParselet - i.e. > so the parent sequence will fail just like it did before.
                     // When this method returns null, we go with the result we produced in the first call to parseRepeatingSequence method.
                     return null;
                  }

                  if (value == null)
                     value = (ParentParseNode) newParseNode(lastMatchIndex);
                  value.add(new ErrorParseNode(new ParseError(skipOnErrorParselet, "Expected {0}", new Object[]{this}, errorStart, parser.currentIndex), errorRes.toString()), skipOnErrorParselet, -1, -1, true, parser);
                  extendedErrorMatches = true;
                  matched = true;
               }
               else {   // Found the exit parselet is next in the stream so we successfully consumed some error nodes so reset the lastMatchIndex to include them
                  lastMatchIndex = parser.currentIndex;
                  matchedAny = extendedErrorMatches;
               }
            }
         }
         if (listSize != -1 && listSize == rctx.arrIndex) {
            lastMatchIndex = parser.currentIndex;
            break;
         }
         if (repeatCount != -1 && value != null && value.children.size() == repeatCount) {
            lastMatchIndex = parser.currentIndex;
            break;
         }

      }

      //rctx.arrIndex = saveArrIndex;

      if (!matchedAny) {
         if (parser.enablePartialValues && !lookahead && lastError != null) {
            Object pv = lastError.partialValue;
            if ((pv != null || !optional) && !childParselet.getLookahead()) {
               if (errorValues == null)
                  errorValues = new ArrayList<Object>();
               errorValues.add(pv);
               // Add these null slots so that we do all of the node processing
               value = newRepeatSequenceResult(errorValues, value, lastMatchIndex, parser);
               return parsePartialError(parser, value, lastError, childParselet, "Partial array match: {0} ", this);
            }
               /* A bad case for the optional continuation is Foo.this - which needs to only match "Foo" as the identifier expression
                  and cannot match 'foo.' with the optional continuation, or else it consumes the '.' which won't match the '.this' selector
                  later on.  It seems like a bad idea to by default match an error case which is optional.  Instead, those specific error cases
                  should be ignored with skipOnErrorSlot or some other way.
            if (pv == null && optional && anyContent) {
               System.out.println("***");
               value = newRepeatSequenceResult(errorValues, value, lastMatchIndex, parser);
               ParseError err = parsePartialError(parser, value, lastError, childParselet, "Optional continuation: {0} ", this);
               err.optionalContinuation = true;
               return err;
            }
               */
         }

         if (optional) {
            parser.changeCurrentIndex(startIndex);
            return null;
         }
         return parseError(parser, "Expecting one or more of {0}", this);
      }
      else {
         if (parser.enablePartialValues && !lookahead && lastError != null) {
            Object pv = lastError.partialValue;
            // IF we partially matched the next sequence, we need to indicate the partial value
            // for this error if it's not already set.
            if (pv == null) {
               if (errorValues != null) {
                  ParentParseNode errVal = cloneParseNode(((ParentParseNode) value)) ;
                  lastError = lastError.propagatePartialValue(newRepeatSequenceResult(errorValues, errVal, lastMatchIndex, parser));
               }
               else
                  lastError = lastError.propagatePartialValue(value);
               // Instead of producing a value for this fragment and messing up the parse node for what might be a valid match
               // we mark this error as a "continuation".  This flag can then be used in the suggestCompletions
               // method to take into account the fact that there's an extra '.' at the end (or whatever)
               lastError.continuationValue = true;
            }
            /*
            if (pv != null && !childParselet.getLookahead()) {
               value = (ParentParseNode) newParseNode(lastMatchIndex);
               if (errorValues == null)
                  errorValues = new ArrayList<Object>();
               errorValues.add(pv);
               // Add these null slots so that we do all of the node processing
               for (int k = 0; k < numParselets; k++) {
                  Object nv;
                  if (errorValues.size() > k)
                     nv = errorValues.get(k);
                  else
                     nv = null;
                  value.add(nv, parselets.get(k), k, false, parser);
               }
               return parsePartialError(parser, value, lastError, childParselet, "Partial array match: {0} ", this);
            }
            */
         }
         parser.changeCurrentIndex(lastMatchIndex);
      }

      String customError;
      if ((customError = accept(parser.semanticContext, value, startIndex, parser.currentIndex)) != null)
         return parseError(parser, customError, value, this);

      if (lookahead)
         parser.changeCurrentIndex(startIndex);

      if (oldModel != null) {
         restoreOldNode(value, oldModel);
      }
      return value;
   }

   private void saveParseRepeatingSequence(IParseNode pn, ISemanticNode oldNode, SaveParseCtx sctx) {
      Parselet childParselet = null;
      int numParselets;

      Object sv = pn.getSemanticValue();
      ISemanticNode sn = null;
      if (sv instanceof ISemanticNode)
         sn = (ISemanticNode) sv;

      ParseOutStream pOut = sctx.pOut;

      ParentParseNode ppn = (ParentParseNode) pn;

      // Skip the parse loop entirely if we matched nothing
      int numPNChild = ppn.children == null ? 0 : ppn.children.size(); // true for either "not known" or we have entries to parse

      pOut.writeUInt(numPNChild);

      // Looping until we've processed all parse nodes.  We might add more than one from different child parselets
      for (int pnCt = 0; pnCt < numPNChild; ) {
         boolean arrElement;
         numParselets = parselets.size();
         for (int i = 0; i < numParselets; i++) {
            childParselet = parselets.get(i);
            if (childParselet.getDiscard() || childParselet.getLookahead())
               continue;

            Object oldChildObj = getArraySemanticValue(sn, i, sctx);

            Object childObj = ppn.children.get(pnCt++);

            // Set in getArraySemanticValue if oldChildObj is an element of the list oldModel - TODO: this should be a return from getArraySemanticValue
            arrElement = sctx.arrElement;
            sctx.arrElement = false;

            boolean needsParseletSave = false;
            IParseNode childPN = null;
            ISemanticNode oldChildNode = null;

            if (oldChildObj instanceof ISemanticNode && childObj instanceof IParseNode) {
               oldChildNode = (ISemanticNode) oldChildObj;
               childPN = (IParseNode) childObj;
               Object childSV = childPN.getSemanticValue();
               if (childSV instanceof ISemanticNode)
                  oldChildNode = (ISemanticNode) childSV;

               needsParseletSave = true;
            }
            else {
               pOut.saveChild(childParselet, childObj, sctx, false);
            }

            int saveArrIndex = 0;
            boolean needsArrIndexRestore = false;
            if (arrElement) {
               saveArrIndex = sctx.arrIndex;
               needsArrIndexRestore = true;
               if (oldChildNode != sn)
                  sctx.arrIndex = 0;
            }
            else if (oldChildNode != sn) {
               needsArrIndexRestore = true;
               saveArrIndex = sctx.arrIndex;
               sctx.arrIndex = 0;
            }

            if (needsParseletSave)
               childParselet.saveParse(childPN, oldChildNode, sctx);

            if (needsArrIndexRestore)
               sctx.arrIndex = saveArrIndex;

            if (arrElement) {
               // Increment the current array index if we just saved an element in the current array value
               sctx.arrIndex++;
            }
         }
      }
   }

   public Object parseExtendedErrors(Parser parser, Parselet exitParselet) {
      if (skipOnErrorParselet == null)
         return null;
      return parseRepeatingSequence(parser, true, exitParselet);
   }

   public Object reparseExtendedErrors(Parser parser, Parselet exitParselet, Object oldChildNode, DiffContext dctx, boolean forceReparse) {
      if (alwaysExtendErrors)
         return null; // already did this so don't do it again
      if (skipOnErrorParselet == null)
         return null;
      return reparseRepeatingSequence(parser, true, exitParselet, oldChildNode, dctx, forceReparse);
   }

   ParentParseNode newRepeatSequenceResult(ArrayList<Object> matchedValues, ParentParseNode value,
                                           int lastMatchIndex, Parser parser) {
      if (matchedValues != null) {
         if (value == null)
            value = (ParentParseNode) newParseNode(lastMatchIndex);
         int numMatchedValues = matchedValues.size();
         int numParselets = parselets.size();
         for (int i = 0; i < numParselets; i++) {
            Object nv = i < numMatchedValues ?  matchedValues.get(i) : null;
            value.add(nv, parselets.get(i), -1, i, false, parser);
         }
      }
      return value;
   }

   ParentParseNode newRepeatSequenceResultReparse(ArrayList<Object> matchedValues, ParentParseNode value,
                                                  int svCount, int newChildCount, int lastMatchIndex, Parser parser, ParentParseNode oldParent, DiffContext dctx) {
      if (matchedValues != null) {
         if (value == null) {
            value = resetOldParseNode(oldParent, lastMatchIndex, false, false);
         }
         int numMatchedValues = matchedValues.size();
         int numParselets = parselets.size();
         for (int i = 0; i < numParselets; i++) {
            Object nv = i < numMatchedValues ?  matchedValues.get(i) : null;
            Object oldChildParseNode = oldParent == null || i >= oldParent.children.size() ? null : oldParent.children.get(i);
            svCount += value.addForReparse(nv, parselets.get(i), svCount, newChildCount++, i, false, parser, oldChildParseNode, dctx, true, true);
         }
      }
      return value;
   }

   protected static final GenerateError INVALID_TYPE_IN_CHAIN = new GenerateError("Chained property - next item in sequence did not match expected item type.");
   protected static final GenerateError MISSING_ARRAY_VALUE = new GenerateError("Missing array value");
   protected static final GenerateError ACCEPT_ERROR = new GenerateError("Accept method failed");

   public Object generate(GenerateContext ctx, Object value) {
      if (trace)
         trace = trace;

      if (optional && emptyValue(ctx, value))
          return generateResult(ctx, null);

      // during generate, we may get a null semantic value and yet still need to generate something, like for a Keyword or something.
      // If we are optional, returning null here, not an error.  That does not seem 100% correct but there are cases like for generating i++
      // which need to return null when encountering an optional value which matches
      if (value != null && !dataTypeMatches(value))
         return optional ? generateResult(ctx, null) : ctx.error(this, SLOT_DID_NOT_MATCH, value, 0);

      // TODO: use acceptTree here since we should be validating the entire value right?
      String acceptError = accept(ctx.semanticContext, value, -1, -1);
      if (acceptError != null) {
         return ctx.error(this, ACCEPT_ERROR, value, 0);
      }

      ParentParseNode pnode = null;
      int progress = 0;

      if (getSemanticValueIsArray()) { // Repeat but not chained
         // TODO: is this chunk needed anymore because of the test above?
         if (value == null) {
            if (optional)
               return generateResult(ctx, null);
            else
               return ctx.error(this, MISSING_ARRAY_VALUE, value, progress);
         }

         if (!(value instanceof List)) {
            if (PString.isString(value)) {
               IString strValue = PString.toIString(value);
               while (strValue != null && strValue.length() > 0) {
                  if (pnode == null)
                     pnode = newGeneratedParseNode(value);
                  Object newVal = generateOne(ctx, pnode, strValue);
                  if (newVal instanceof GenerateError) {
                     if (optional)
                        break;
                     pnode.setSemanticValue(null, true, false);
                     return newVal;
                  }
                  else if (newVal == null)
                     strValue = null;
                  else {
                     strValue = PString.toIString(newVal);
                     progress += ctx.progress(strValue);
                  }
               }
            }
            else
               throw new IllegalArgumentException("Repeat node received non-list value to generate: " + value);
         }
         else if (parameterType != ParameterType.ARRAY) {
            pnode = newGeneratedParseNode(value);
            List nodeList = (List) value;
            int nodeListSize = nodeList.size();
            for (int i = 0; i < nodeListSize; i++) {
               Object arrVal = generateOne(ctx, pnode, nodeList.get(i));
               if (arrVal instanceof GenerateError) {
                  pnode.setSemanticValue(null, true, false);
                  ((GenerateError)arrVal).progress += progress;
                  return arrVal;
               }
               else
                  progress += ctx.progress(pnode);
            }
         }
         else {
            pnode = newGeneratedParseNode(value);
            SemanticNodeList nodeList = (SemanticNodeList) value;
            int numProcessed = 0;
            do {
               Object arrVal = generateOne(ctx, pnode, nodeList);
               if (arrVal instanceof PartialArrayResult) {
                  PartialArrayResult par = (PartialArrayResult) arrVal;
                  pnode.copyGeneratedFrom(par.resultNode);
                  progress += ctx.progress(par.resultNode);
                  nodeList = getRemainingValue(nodeList, par.numProcessed, 0);
                  numProcessed += par.numProcessed;
               }
               else if (arrVal instanceof GenerateError) {
                  if (numProcessed != 0)
                     return new PartialArrayResult(numProcessed, pnode, (GenerateError)arrVal);
                  else {
                     pnode.setSemanticValue(null, true, false);
                     ((GenerateError)arrVal).progress += progress;
                     return arrVal;
                  }
               }
               else if (arrVal == null) {
                  if (numProcessed != 0)
                     return new PartialArrayResult(numProcessed, pnode, null);
                  return generateResult(ctx, pnode);
               }
               else
                  return generateResult(ctx, pnode);

            } while(nodeList.size() > 0);
         }
      }
      else if (chainedPropertyMappings != null) {
         Object nextValue = value;
         ParentParseNode lastParseNode = null;
         ParentParseNode origParseNode;

         origParseNode = pnode = newGeneratedParseNode(value);

         do {
            if (nextValue != null &&
                    !getSemanticValueClass().isInstance(nextValue)) {
               if (pnode != null)
                  pnode.setSemanticValue(null, true, false);

               return ctx.error(this, INVALID_TYPE_IN_CHAIN, nextValue, progress);
            }

            ctx.maskProperty(nextValue, chainedPropertyMappings[0], null);

            Object res = generateOne(ctx, pnode, nextValue);

            Object chainValue = ctx.getPropertyValue(nextValue, chainedPropertyMappings[1]);

            /* It failed to match the entire chain - so reset the next link and try 
             * again.
             */
            if (res instanceof GenerateError &&
                chainValue != null && getSemanticValueClass().isInstance(chainValue)) {
               Object chainValueLHS = ctx.getPropertyValue(chainValue, chainedPropertyMappings[0]);
               ctx.maskProperty(nextValue, chainedPropertyMappings[1], chainValueLHS);

               res = generateOne(ctx, pnode, nextValue);

               ctx.unmaskProperty(nextValue, chainedPropertyMappings[1]);
            }
            else
               chainValue = null;

            ctx.unmaskProperty(nextValue, chainedPropertyMappings[0]);

            if (res instanceof GenerateError) {
               pnode.setSemanticValue(null, true, false);
               if (optional && emptyValue(ctx, value)) {
                  return generateResult(ctx, null);
               }
               return res;
            }

            nextValue = chainValue;
            if (lastParseNode != null) {
               lastParseNode.addGeneratedNode(pnode);
            }
            if (nextValue != null) {
               lastParseNode = pnode;
               progress += ctx.progress(pnode);
               pnode = newGeneratedParseNode(chainValue);
            }
         } while (nextValue != null);

         if (nextValue == null)
            return generateResult(ctx, origParseNode);

         if (pnode != null)
            pnode.setSemanticValue(null, true, false);

         return ctx.error(this, NO_OBJECT_FOR_SLOT, value, progress);
      }
      else {
         if (pnode == null)
            pnode = newGeneratedParseNode(value);

         Object res = generateOne(ctx, pnode, value);
         if (res instanceof GenerateError) {
            pnode.setSemanticValue(null, true, false);
            if (optional && emptyValue(ctx, value))
               return generateResult(ctx, null);
            return res;
         }
         else if (res instanceof PartialArrayResult)
            return res;
         // If we did not matching anything, do not associate this parse node with the semantic value.  This
         // acts as a signal to the caller that the value was not consumed, so it won't advance the array.
         else if (res == null)
            pnode.setSemanticValue(null, true, false);
      }
      return generateResult(ctx, pnode);
   }

   private final static GenerateError BAD_TYPE_FOR_SLOT = new GenerateError("Type received is not expected for this slot");
   private final static GenerateError NO_OBJECT_FOR_SLOT = new GenerateError("Null parentNode object for slot");
   private final static GenerateError SCALAR_FOR_ARRAY_SLOT = new GenerateError("Scalar value supplied for array slot");
   private final static GenerateError ARRAY_TOO_SMALL = new GenerateError("Missing element in array value");
   private final static GenerateError SLOT_DID_NOT_MATCH = new GenerateError("Part of this sequence did not match");
   private final static GenerateError PARTIAL_ARRAY_PROPERTY_MATCH = new GenerateError("Array property value only partially matched");

   public Object generateElement(GenerateContext ctx, Object value, boolean isArray) {
      ParentParseNode pnode = new ParentParseNode();
      Object x = generateOne(ctx, pnode, value);
      // validate that x is empty - i.e. all of the value was generated?
      return pnode;
   }

   private Object generateOne(GenerateContext ctx, ParentParseNode pnode, Object value) {
      int arrayIndex = 0;
      SemanticNodeList nodeList = parameterType != ParameterType.PROPAGATE && value instanceof List ? (SemanticNodeList) value : null;
      GenerateError bestError = null;

      boolean isValueString = PString.isString(value);

      //if (trace)
      //   System.out.println("*** Generating a traced element");

      ParentParseNode tnode = new ParentParseNode(this);
      int progress = 0;

      int numParselets = parselets.size();
      for (int i = 0; i < numParselets; i++) {
         Parselet childParselet = parselets.get(i);
         Object childValue = null;
         boolean mappingSkip = false;
         boolean processed = false;
         Object childNode = null;
         Object childNodeSuffix = null;
         boolean skipChild = false;
         int numProcessed = 0;
         boolean simpleStringNode = false;

         if (genMapping != null) {
            String mapStr = genMapping[i];
            int dotIx = mapStr.indexOf(".");
            if (dotIx != -1) {
               if (!mapStr.equals(".")) {
                  String prefix = dotIx == 0 ? null : mapStr.substring(0,dotIx);
                  childNodeSuffix = dotIx == mapStr.length() - 1 ? null : mapStr.substring(dotIx+1);
                  if (prefix != null)
                     tnode.addGeneratedNode(prefix, childParselet);
               }
            }
            else if (mapStr.length() == 0)
               mappingSkip = true;
            else {
               childNode = genMapping[i];
               processed = true;
            }
         }

         if (!processed) {
            if (parameterMapping == null || parameterMapping[i] == ParameterMapping.SKIP || mappingSkip) {
               mappingSkip = true;
               childValue = null;
            }
            else {
               switch (parameterMapping[i]) {
                  case STRING:
                  case PROPAGATE:
                  case INHERIT:
                     nodeList = null;
                     childValue = value;
                     break;
                  case ARRAY:
                     if (!getSemanticValueIsArray()) {
                        // If we are not a repeat node and the child is also a scalar, we need to pull apart the
                        // array value we are given and hand it element by element to the child node to generate.
                        // If we are a repeat node, we already have done this in "generate".
                        if (value != null && !ParseUtil.isArrayParselet(childParselet)) {
                           if (nodeList == null) {
                              if (isValueString) {
                                 IString strValue = PString.toIString(value);
                                 if (strValue != null && strValue.length() > 0) {
                                    Object newVal = ctx.generateChild(childParselet, strValue);
                                    if (newVal instanceof GenerateError) {
                                       if (emptyValue(ctx, strValue))
                                          break;
                                       ((GenerateError)newVal).progress += progress;
                                       return newVal;
                                    }
                                    else if (newVal == null)
                                       strValue = null;
                                    else {
                                       tnode.addGeneratedNode(newVal, childParselet);
                                       int newLen = PString.toIString(newVal).length();
                                       strValue = strValue.substring(newLen);
                                       progress += newLen;
                                    }
                                 }
                                 skipChild = true; // We processed this node so do not generate it below
                              }
                              else
                                 return SCALAR_FOR_ARRAY_SLOT;
                           }
                           else {
                              Object nodeValue;
                              if (arrayIndex >= nodeList.size()) {
                                 if (arrayIndex == 0)
                                    nodeValue = null;
                                 else
                                    return optional && emptyValue(ctx, value) ? null : ARRAY_TOO_SMALL;
                              }
                              else {
                                 nodeValue = nodeList.get(arrayIndex);
                              }


                              Object nodeVal;
                              if (nodeValue instanceof String && ctx.finalGeneration) {
                                 Object res = childParselet.language.parseString((String) childValue, childParselet);
                                 if (!(res instanceof ParseError))
                                    nodeVal = newGeneratedSimpleParseNode(childValue, childParselet);
                                 else {
                                    if (childParselet.optional && childParselet.emptyValue(ctx, childValue)) {
                                       nodeVal = null;
                                    }
                                    else
                                       nodeVal = ctx.error(this, SLOT_DID_NOT_MATCH, value, progress);
                                 }
                              }
                              else {
                                 nodeVal =  ctx.generateChild(childParselet, nodeValue);
                              }

                              if (nodeVal instanceof GenerateError) {
                                 ((GenerateError)nodeVal).progress += progress;
                                 return optional &&
                                        emptyValue(ctx, getRemainingValue(nodeList, arrayIndex, 0)) ? null : nodeVal;
                              }
                              // Make sure we actually consume the array value here.  If the parselet is optional, it could return an empty match instead
                              else if (nodeVal != null && (!(nodeVal instanceof IParseNode) || ((IParseNode) nodeVal).getSemanticValue() == nodeValue))
                                 arrayIndex++;

                              tnode.addGeneratedNode(nodeVal, childParselet);
                              progress += ctx.progress(nodeVal);

                              skipChild = true; // We processed this node so do not generate it below
                           }
                        }
                        else {
                           int numToTrim = 0;
                           for (int j = i + 1; j < numParselets; j++) {
                              if (parameterMapping[j] != ParameterMapping.ARRAY)
                                 break;
                              Parselet nextP = parselets.get(j);
                              if (ParseUtil.isArrayParselet(nextP) || nextP.optional)
                                 break;
                              // We need to reserve at least this many array elements to match the
                              // required scalar that forms part of this array.
                              numToTrim++;
                           }
                           childValue = getRemainingValue(nodeList, arrayIndex, numToTrim);
                           if (childValue != null)
                              numProcessed = nodeList.size() - arrayIndex - numToTrim;
                        }
                     }
                     else {
                        if (nodeList != null) {
                           // If we are not a repeat node and the child is also a scalar, we need to pull apart the
                           // array value we are given and hand it element by element to the child node to generate.
                           // If we are a repeat node, we already have done this in "generate".
                           if (value != null && !ParseUtil.isArrayParselet(childParselet)) {
                              if (arrayIndex >= nodeList.size())
                                 childValue = null;
                              else {
                                 childValue = ((List) value).get(arrayIndex);
                                 numProcessed = 1;
                              }
                           }
                           else {
                              childValue = getRemainingValue((SemanticNodeList) value, arrayIndex, 0);
                              if (childValue != null)
                                 numProcessed = nodeList.size() - arrayIndex;
                           }
                        }
                        else if (isValueString) {
                           childValue = value; // Try sending the remaining string to the child parselet
                        }
                        // We have a scalar value so if the child is also a scalar, we'll just pass it right along
                        else if (!ParseUtil.isArrayParselet(childParselet)) {
                           childValue = value;
                        }
                        else {
                           System.err.println("*** Unhandled case in array parselet regeneration");
                        }
                     }
                     break;

                  case NAMED_SLOT:
                     if (value == null || value instanceof String)
                        return ctx.error(this, BAD_TYPE_FOR_SLOT, value, progress);

                     Class svClass = getSlotClass();
                     Object cvtValue = value;
                     if (svClass == null || 
                        (cvtValue = ParseUtil.convertSemanticValue(svClass, value)) != ParseUtil.CONVERT_MISMATCH) {
                        childValue = ctx.getPropertyValue(cvtValue, slotMapping[i]);
                        if (!isValueString && childValue instanceof String && ctx.finalGeneration) {
                           simpleStringNode = true;
                        }
                     }
                     else
                        return optional ? null : ctx.error(this, BAD_TYPE_FOR_SLOT, value, progress);
                     break;
               }
            }

            if (!skipChild) {
               if (simpleStringNode) {
                  Object res = childParselet.language.parseString((String) childValue, childParselet);
                  if (!(res instanceof ParseError))
                     childNode = newGeneratedSimpleParseNode(childValue, childParselet);
                  else {
                     if (childParselet.optional && childParselet.emptyValue(ctx, childValue)) {
                        childNode = null;
                     }
                     else
                        childNode = ctx.error(this, SLOT_DID_NOT_MATCH, value, progress);
                  }
               }
               else {
                  childNode = ctx.generateChild(childParselet, childValue);
               }
               if (childNode instanceof PartialArrayResult) {
                  PartialArrayResult par = (PartialArrayResult) childNode;
                  arrayIndex += par.numProcessed;
                  childNode = par.resultNode;
                  progress += ctx.progress(par.resultNode);
                  if (par.error != null)
                     bestError = par.error;

                  // If we propagate our value and get back a partial result, we need to return the PartialArrayResult
                  // ourself below.
                  if (nodeList == null) {
                     if (!(value instanceof SemanticNodeList))
                        // TODO: the problem here is that childValue is an array and the numProcessed index failed
                        // to match.  Might be helpful to include that in the error.
                        return par.error == null ? ctx.error(this, PARTIAL_ARRAY_PROPERTY_MATCH, value, progress) : par.error;

                     nodeList = (SemanticNodeList) value;
                  }
               }
               else if (childNode instanceof GenerateError) {
                  // For strings, arrays or an empty value, we just return null.  The parent has to know we did not consume
                  // any value.
                  if (optional) {
                     if (emptyValue(ctx, childValue)) {
                        return null;
                     }
                     else {
                        if (isValueString || nodeList != null)
                           return null;
                        else {
                           return null;
                        }
                     }
                  }
                  ((GenerateError)childNode).progress += progress;
                  return childNode;
               }
               // If we are matching a child node from a slot and the value we pass is a string and the child node
               // does not consume all of the string, that is an error.
               else if (parameterMapping != null && parameterMapping[i] == ParameterMapping.NAMED_SLOT &&
                        PString.isString(childValue)) {
                  int nodeLen = childNode == null ? 0 : ParseUtil.toSemanticString(childNode).length();
                  if (nodeLen < PString.toIString(childValue).length())
                     return ctx.error(this, SLOT_DID_NOT_MATCH, childValue, progress);
               }
               // Only increment the array index if we processed the array elements.
               else if (childNode != null && !mappingSkip && nodeList != null && (!(childNode instanceof IParseNode) || !((IParseNode) childNode).isEmpty()))
                  arrayIndex += numProcessed;
               // If we have a null value for a sequence which allows null values, this is a match and we need to consume the element.
               else if (allowNullElements && !mappingSkip && childValue == null && childNode == null && nodeList != null)
                  arrayIndex += numProcessed;
               else if (childNode == null && !emptyValue(ctx, childValue) && parameterMapping != null && parameterMapping[i] == ParameterMapping.NAMED_SLOT) {
                  return ctx.error(this, SLOT_DID_NOT_MATCH, childValue, progress);
               }
               // We have an optional child node which did not consume the value.  If we too are optional, return null
               //else if (childNode instanceof IParseNode && ((IParseNode)childNode).getSemanticValue() == null && childValue != null && nodeList == null) {
               //   childNode = null;
               //}
            }
            // NOTE: we used to set childNode = null here and not continue but clearly in both cases above we added the result already to tnode so this was inserting
            // an extra null in IdentifierExpression.0 when we changed the first identifier (e.g. fed.size to ed.size)
            else
               continue;
         }

         tnode.addGeneratedNode(childNode, childParselet);
         progress += ctx.progress(childNode);
         if (childNodeSuffix != null)
            tnode.addGeneratedNode(childNodeSuffix, childParselet);

         /*
          * We have a string semantic value... in case it was formed from more than one parselet
          * we need to pull out the part which was just generated before passing the next chunk on.
          */
         if (!mappingSkip && isValueString && childNode != null) {
            IString strValue = PString.toIString(value);
            int childNodeLen = ParseUtil.toSemanticStringLength(childNode);
            if (strValue.length() >= childNodeLen) {
               strValue = strValue.substring(childNodeLen);
               value = strValue;
            }
            else
               value = null;
         }
      }

      if (nodeList != null && arrayIndex < nodeList.size())
         return new PartialArrayResult(arrayIndex, tnode, bestError);
      else {
         pnode.copyGeneratedFrom(tnode);
         return value;
      }
   }

   protected GenerateError regenerateElement(ParentParseNode pnode, Parselet childParselet, Object element, int parseNodeIx, ChangeType changeType) {
      if (parselets.size() == 1)
         return regenerateChild(pnode, childParselet, element, parseNodeIx, changeType);

      Object childResult = changeType == ChangeType.REMOVE ? null :
              generateElement(getLanguage().newGenerateContext(this, element), element, true);
      if (!(childResult instanceof GenerateError)) {
         int num = parselets.size();
         int pos = parseNodeIx * num;
         if (childResult != null) {
            List<Object> newChildren = ((ParentParseNode)childResult).children;
            if (changeType == ChangeType.REPLACE) {
               for (int i = pos; i < num; i++)
                  pnode.children.set(i, newChildren.get(i));
            }
            else if (changeType == ChangeType.ADD) {
               if (pnode.children == null)
                  pnode.children = new ArrayList<Object>();
               pnode.children.addAll(Math.min(pos,pnode.children.size()), newChildren);
            }
         }
         if (changeType == ChangeType.REMOVE) {
            for (int i = pos + num - 1; i >= pos; i--) {
               if (i < pnode.children.size())
                  pnode.children.remove(i);
               else {
                  return new GenerateError("Parse node is out of whack with child index!");
               }
            }
         }
         return null;
      }
      if (childResult == null)
         return new GenerateError("Not able to generate node");
      return (GenerateError) childResult;
   }

   public Parselet getChildParselet(Object parseNode, int index) {
      return getChildParseletForIndex(index);
   }

   public Parselet getChildParseletForIndex(int index) {
      if (slotMapping == null) {
         // When there's no slot mapping, we skip lookahead slots so make sure to pick the right parselet for those cases (e.g. fasterFloatPointLiteral)
         int useIndex = index;
         int sz = parselets.size();
         for (int i = 0; i < index; i++) {
            Parselet p = parselets.get(i % sz);
            if (p.getDiscard() || p.getLookahead())
               useIndex++;
         }
         return parselets.get(useIndex % sz);
      }
      return parselets.get(index % parselets.size());
   }

   /**
    * When we are trying to determine if a given node is produced by a given parselet, we use this method.
    * It returns true if this parselet could have produced this data type.
    */
   public boolean dataTypeMatches(Object other) {
      if (super.dataTypeMatches(other))
         return true;

      Class svClass = getSemanticValueClass();

      // Already verified that other is not a String - the default type in super.dataTypeMatches
      if (svClass == null) {
         return false;
      }

      // Could not be a match - the data types do not match
      if (!svClass.isInstance(other))
         return false;

      // In this case the specific type of the class
      if (parameterType == ParameterType.INHERIT)
         return true;

      if (parselets == null)
         return false;
      int sz = parselets.size();
      for (int i = 0; i < sz; i++) {
         Parselet child = parselets.get(i);
         if (parameterMapping == null)
            continue;
         switch (parameterMapping[i]) {
            case PROPAGATE:
               return child.dataTypeMatches(other);
         }
      }

      // We have no children which could produce this type so it is not a match
      return false;
   }

   /**
    * When we are trying to determine if a given node is produced by a given parselet, we use this method.
    * It returns true if this parselet could have produced this data type.
    */
   public boolean elementTypeMatches(Object other) {
      if (super.elementTypeMatches(other))
         return true;

      Class svClass = getSemanticValueComponentClass();

      // Could not be a match - the data types do not match
      if (!svClass.isInstance(other))
         return false;

      // In this case the specific type of the class
      if (parameterType == ParameterType.INHERIT)
         return true;

      if (parselets == null)
         return false;
      int sz = parselets.size();
      for (int i = 0; i < sz; i++) {
         Parselet child = parselets.get(i);
         if (parameterMapping == null)
            continue;
         switch (parameterMapping[i]) {
            case ARRAY:
               return child.dataTypeMatches(other);
            case PROPAGATE:
               return child.elementTypeMatches(other);
         }
      }

      // We have no children which could produce this type so it is not a match
      return false;
   }

   protected boolean disableExtendedErrors() {
      return false;
   }

}
