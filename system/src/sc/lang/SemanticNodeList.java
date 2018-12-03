/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.binf.BinfConstants;
import sc.binf.BinfOutStream;
import sc.lifecycle.ILifecycle;
import sc.parser.*;
import sc.type.CTypeUtil;
import sc.util.FileUtil;

import java.util.*;

public class SemanticNodeList<E> extends ArrayList<E> implements ISemanticNode, ILifecycle {
   transient public IParseNode parseNode;
   transient public ISemanticNode parentNode;

   // TODO: performance - turn into bitfields - 6 bytes => 1
   transient public boolean parseNodeInvalid = false;
   transient public int parseletId = -1;
   transient protected boolean initialized;
   transient protected boolean started;
   transient protected boolean transformed;
   transient protected boolean validated;
   transient protected boolean processed;

   public SemanticNodeList() {
      super();
   }

   public SemanticNodeList(int i) {
      super(i);
   }

   public SemanticNodeList(ISemanticNode parent) {
      parentNode = parent;
   }


   public SemanticNodeList(ISemanticNode parent, int size) {
      super(size);
      parentNode = parent;
   }

   public boolean isInitialized() {
      return initialized;
   }
   
   public boolean isStarted() {
      return started;
   }

   public boolean isValidated() {
      return validated;
   }

   public boolean isProcessed() {
      return processed;
   }

   public void setParseNode(IParseNode pn) {
      // We want the parselet which first creates each semantic value - it's SemanticValueClass will match the class of the type.
      // Ordinarily the parseNode is set to the last parselet to modify the semanticValue - it can be a more generic parselet which has
      // populated additional properties.
      if (parseNode == null && pn != null) {
         parseletId = pn.getParselet().id;
      }
      parseNode = pn;
   }
   public IParseNode getParseNode() {
      return parseNode;
   }

   public void setParseletId(int id) {
      parseletId = id;
   }

   public ISemanticNode getParentNode() {
      return parentNode;
   }

   public void setParentNode(ISemanticNode parentNode) {
      this.parentNode = parentNode;
   }

   public ISemanticNode getRootNode() {
      if (parentNode == null)
         return this;
      return parentNode.getRootNode();
   }

   public int getNestingDepth() {
      if (parentNode != null)
         return parentNode.getChildNestingDepth();
      return 0;
   }

   public int getChildNestingDepth() {
      if (parentNode != null)
         return parentNode.getChildNestingDepth();
      return 0;
   }

   public void init() {
      if (initialized)
         return;
      initialized = true;

      int sz = size();
      for (int i = 0; i < sz; i++) {
         Object val = get(i);
         if (val instanceof ILifecycle)
            ((ILifecycle) val).init();
      }
   }

   public void start() {
      if (started)
         return;
      started = true;

      try {
         int sz = size();
         for (int i = 0; i < sz; i++) {
            Object val = get(i);
            if (val instanceof ILifecycle) {
               ILifecycle lval = (ILifecycle) val;
               lval.start();
            }
         }
      }
      catch (RuntimeException exc) {
         clearStarted();
         throw exc;
      }
   }

   public void validate() {
      if (validated)
         return;
      validated = true;

      int sz = size();
      for (int i = 0; i < sz; i++) {
         Object val = get(i);
         if (val instanceof ILifecycle)
            ((ILifecycle) val).validate();
      }
   }

   public void process() {
      if (processed)
         return;
      processed = true;

      int sz = size();
      for (int i = 0; i < sz; i++) {
         Object val = get(i);
         if (val instanceof ISemanticNode)
            ((ISemanticNode) val).process();
      }
   }

   public void stop() {
      initialized = false;
      started = false;
      validated = false;
      processed = false;

      int sz = size();
      for (int i = 0; i < sz; i++) {
         Object val = get(i);
         if (val instanceof ILifecycle)
            ((ILifecycle) val).stop();
      }
   }

   public void clearStarted() {
      if (started) {
         started = false;
         if (parentNode != null)
            parentNode.clearStarted();
      }
   }

   public boolean needsTransform() {
      boolean any = false;
      for (int i = 0; i < size(); i++) {
         Object val = get(i);
         if (val instanceof ISemanticNode) {
            ISemanticNode nodeVal = (ISemanticNode) val;
            if (nodeVal.needsTransform()) {
               any = true;
            }
         }
      }
      return any;
   }

   public boolean transform(ILanguageModel.RuntimeType runtime) {
      if (transformed)
         return false;
      transformed = true;
      boolean any = false;
      for (int i = 0; i < size(); i++) {
         Object val = get(i);
         if (val instanceof ISemanticNode) {
            ISemanticNode nodeVal = (ISemanticNode) val;
            if (!nodeVal.getTransformed() && nodeVal.transform(runtime)) {
               any = true;
            }
         }
      }
      return any;
   }

   public void add(int index, E element) {
      add(index, element, true, true);
   }

   private void updateElement(int index, E element, boolean changeParent, NestedParselet.ChangeType changeType, boolean initOnly, boolean updateParseNodes) {
      // For semantic nodes, we'll populate the parentNode pointer.
      if (changeParent && element instanceof ISemanticNode)
         ((ISemanticNode) element).setParentNode(this);

      // Don't do this during the initial parsing phase
      if (started) {
         // Need to lazily initialize any non-initialized nodes.  As the model is changed, new nodes
         // can be added so we lazily init them as they are added to an already initialized node.
         if (element instanceof ILifecycle) {
            ILifecycle lcval = (ILifecycle) element;
            if (!lcval.isInitialized()) {
               lcval.init();

            }
            if (!initOnly && !lcval.isStarted()) {
               lcval.start();
               if (validated)
                  lcval.validate();
               if (processed)
                  lcval.process();
            }
         }

         if (parseNode == null) // not yet hooked into a semantic tree yet
            return;

         if (updateParseNodes) {
            Parselet parselet = parseNode.getParselet();

            if (parselet instanceof NestedParselet) {
               //if (!parselet.language.trackChanges) {
               //   parseNodeInvalid = true;
               //} else
               if (!((NestedParselet) parselet).arrayElementChanged(parseNode, this, index, element, changeType))
                  System.err.println("*** Unable to regenerate after an array element add to list: " + this + " index: " + index + " element: " + element);
               //else {
               //   checkParseTree();
               //}
            }
            else {
               if (parselet.language.trackChanges) {
                  Object parseNodeObj = parselet.generate(parselet.getLanguage().newGenerateContext(parselet, this), this);
                  if (parseNodeObj instanceof GenerateError)
                     System.err.println("*** Unable to regenerate after an array element: " + changeType + " " + this + " index: " + index + " element: " + element);
                  else
                     parseNode = (IParseNode) parseNodeObj;
               }
               else
                  setParseNodeValid(false);
            }
         }
      }
   }

   public void add(int index, E element, boolean changeParent, boolean updateParseNodes) {
      super.add(index, element);

      // For semantic nodes, we'll populate the parentNode pointer.
      if (changeParent && element instanceof ISemanticNode)
         ((ISemanticNode) element).setParentNode(this);

      updateElement(index, element, changeParent, NestedParselet.ChangeType.ADD, false, updateParseNodes);

      /*
       * Use this to debug cases where an uninitailzied model is hooked up to an initialized model.  we don't automatically initialize cause there are some cases where this happens too early (if I recall correctly)
      else if (initialized) {
         if (element instanceof ILifecycle) {
            ILifecycle lcval = (ILifecycle) element;
            if (!lcval.isInitialized())
               System.out.println("*** Error not initialized!");
         }
      }
      */
   }

   public E set(int index, E element) {
      return set(index, element, true, true);
   }

   public E set(int index, E element, boolean changeParent, boolean updateParseNodes) {
      E ret = super.set(index, element);

      updateElement(index, element, changeParent, NestedParselet.ChangeType.REPLACE, false, updateParseNodes);

      return ret;
   }

   public boolean remove(Object o) {
      int ix = indexOf(o);
      if (ix == -1)
         return false;
      else
         return remove(ix) != null;
   }

   public E remove(int index) {
      return remove(index, true);
   }

   public E remove(int index, boolean updateParseNodes) {
      E element = super.get(index);
      if (started && parseNode != null && updateParseNodes) {
         Parselet parselet = parseNode.getParselet();

         //if (!parselet.language.trackChanges) {
         //   parseNodeInvalid = true;
         //} else
         if (parselet instanceof NestedParselet) {
            if (!((NestedParselet)parselet).arrayElementChanged(parseNode, this, index, element, NestedParselet.ChangeType.REMOVE)) {
               E x = super.remove(index);
               if (x != null) {
                  if (parselet.language.trackChanges) {
                     if (!((NestedParselet) parselet).regenerate((ParentParseNode) parseNode, false)) {
                        System.err.println("*** Unable to regenerate after an array element remove from list: " + this + " index: " + index + " element: " + element);

                     }
                  }
                  else {
                     setParseNodeValid(false);
                  }
               }
               return x;
            }
         }
         else {
            if (parselet.language.trackChanges) {
               Object parseNodeObj = parselet.generate(parselet.getLanguage().newGenerateContext(parselet, this), this);
               if (parseNodeObj instanceof GenerateError)
                  System.err.println("*** Unable to regenerate after an array element remove from list: " + this + " index: " + index + " element: " + element);
               else
                  parseNode = (IParseNode) parseNodeObj;
            }
            else
               setParseNodeValid(false);
         }
      }
      return super.remove(index);
   }

   public boolean add(E element) {
      add(size(), element, true, true);
      return true;
   }

   public boolean add(E element, boolean setParent, boolean updateParseNodes) {
      add(size(), element, setParent, updateParseNodes);
      return true;
   }

   public boolean addAll(Collection<? extends E> c) {
      return addAll(c, true, true);
   }

   public boolean addAll(Collection<? extends E> c, boolean setParent, boolean updateParseNodes) {
      return addAll(size(), c, setParent, updateParseNodes);
   }

   public boolean addAll(int index, Collection<? extends E> c) {
      return addAll(index, c, true, true);
   }

   public boolean addAll(int index, Collection<? extends E> c, boolean setParent, boolean updateParseNodes) {
      if (c == this) {
         System.out.println("*** Error - attempt to add a collection to itself");
         return false;
      }
      ensureCapacity(size() + c.size());
      int i = 0;
      for (E e:c)
         super.add(index + i++, e);

      // It's best to first init and then start all components after all have been added.  Otherwise, for example, the start method
      // after each statement will revalidate the caches.  We need init to run first so the propertyName of all methodDefinitions is set
      // when the start method of the first entry is run.
      i = 0;
      for (E e:c)
         updateElement(index + i++, e, setParent, NestedParselet.ChangeType.ADD, true, updateParseNodes);

      i = 0;
      for (E e:c)
         updateElement(index + i++, e, setParent, NestedParselet.ChangeType.ADD, false, false);

      return i != 0;
   }

   // TODO: set, remove

   public String toHeaderString() {
      StringBuffer sb = new StringBuffer();
      sb.append("[");
      sb.append(size());
      sb.append("]");
      if (size() > 0) {
         sb.append("{");
         if (size() > 5) {
            sb.append(get(0));
            sb.append(",");
            sb.append(get(1));
            sb.append("....");
            sb.append(get(size()-1));
         }
         else {
            for (int i = 0; i < size(); i++) {
               if (i != 0)
                  sb.append(",");
               sb.append(get(i));
            }
         }
         sb.append("}");
      }
      return sb.toString();
   }

   public String toLanguageString() {
      return toLanguageString(null);
   }

   public String toLanguageString(Parselet parselet) {
      if (parseNode != null) {
         if (parseNodeInvalid) {
            validateParseNode(false);
         }
         return parseNode.toString();
         // For this case, we could walk up to the root node, use the start parselet from that language and generate it.
         // then presumably we'd have a parse node we could use.
      }
      else if (parselet == null)
         throw new IllegalArgumentException("No parse tree for semantic node list");
      else {
         Object result = parselet.generate(parselet.getLanguage().newGenerateContext(parselet, this), this);
         if (result instanceof ParseError || result instanceof GenerateError) {
            System.err.println("*** Error generating code for parselet: " + parselet + " language: " + parselet.getLanguage() + " and model: " + this);
            return "Error generating list from parselet: " + parselet;
         }
         else if (result instanceof IParseNode)
            return ((IParseNode) result).formatString(null, null, -1, true).toString();
         else
            return result.toString();
      }
   }

   /** Find the supplied node in our list */
   public boolean containsChild(Object toReplace) {
      int ix = indexOf(toReplace);
      if (ix == -1)
         return false;
      return true;
   }

   /** Find the supplied node in our list, and replace it with the other one */
   public int replaceChild(Object toReplace, Object other) {
      int ix = indexOf(toReplace);
      if (ix == -1) {
         return -1;
      }
      set(ix, (E) other);
      return ix;
   }

   /** Find the supplied node in our list, and replace it with the other one */
   public int removeChild(Object toRemove) {
      int ix = indexOf(toRemove);
      if (ix == -1)
         return -1;
      remove(ix);
      return ix;
   }

   public void clear() {
      clear(false);
   }

   public void clear(boolean updateParseNodes) {
      while (size() > 0)
         remove(size()-1, updateParseNodes);
   }

   public ISemanticNode deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap) {
      int sz = size();
      SemanticNodeList newList = new SemanticNodeList(sz);

      int propagateOptions = options;

      if ((options & CopyParseNode) != 0) {
         propagateOptions = (options & ~CopyParseNode) | SkipParseNode;
         if (oldNewMap != null)
            throw new IllegalArgumentException("CopyParseNode does not support oldNewMap");
         oldNewMap = new IdentityHashMap<Object,Object>();
      }

      for (int i = 0; i < sz; i++) {
         Object val = get(i);
         if (val instanceof ISemanticNode)
            val = ((ISemanticNode) val).deepCopy(propagateOptions, oldNewMap);
         newList.add(val);
      }
      Parselet p;

      if (oldNewMap != null)
         oldNewMap.put(this, newList);

      if ((options & SkipParseNode) == 0) {
         IParseNode newP, oldP;
         ParentParseNode newPP;
         oldP = parseNode;
         // Parsenode does not get copied so this can't be invalid yet.
         if (parseNode != null && (p = parseNode.getParselet()) != null) {

            if ((options & CopyParseNode) == 0) {
               newList.parseNode = newPP = new ParentParseNode(p);
               newList.parseNode.setSemanticValue(newList, true);
               newPP.setStartIndex(parseNode.getStartIndex());
               newList.parseNodeInvalid = true;
            }
            else {
               newList.parseNode = newP = oldP.deepCopy();

               // This goes and replaces all semantic values in our map so that the new parse nodes point to the new semantic values and vice versa.
               newP.updateSemanticValue(oldNewMap);
            }
         }
         else {
            newList.parseNodeInvalid = false;
         }
      }
      else {
         // Not copying the parse node so it cannot revalidate itself
         newList.parseNodeInvalid = false;
      }

      if ((options & CopyInitLevels) != 0) {
         newList.initialized = initialized;
         newList.started = started;
         newList.validated = validated;
         newList.processed = processed;
      }
      return newList;
   }

   public void setTransformed(boolean tf) {
      transformed = tf;
   }

   public boolean getTransformed() {
      return transformed;
   }

   public boolean regenerate(boolean finalGen) {
      if (parseNode == null) {
         if (parentNode == null)
             return false;
         if (parentNode.regenerate(finalGen))
            parseNodeInvalid = false;
         else {
            System.err.println("*** regenerate of list failed: " + this.getClass());
            return false;
         }
      }
      else {
         NestedParselet parselet = (NestedParselet) parseNode.getParselet();
         if (parselet.regenerate(parseNode, finalGen))
            parseNodeInvalid = false;
         else {
            System.err.println("*** regenerate of list failed: " + this.getClass());
            return false;
         }
      }
      return true;
   }

   public boolean isSemanticChildValue(ISemanticNode child) {
      return true;
   }

   public void regenerateIfTracking(boolean finalGen) {
      if (parseNode == null) {
         if (parentNode == null)
            return;
         if (parentNode.isSemanticChildValue(this))
            parentNode.regenerateIfTracking(finalGen);
      }
      else {
         NestedParselet parselet = (NestedParselet) parseNode.getParselet();
         if (parselet.language.trackChanges)
            parselet.regenerate((ParentParseNode) parseNode, finalGen);
      }
   }

   public void validateParseNode(boolean finalGen) {
      if (parseNodeInvalid) {
         regenerate(finalGen);
      }
      else {
         int sz = size();
         for (int i = 0; i < sz; i++) {
            Object val = get(i);
            if (val instanceof ISemanticNode)
               ((ISemanticNode) val).validateParseNode(finalGen);
         }
      }
   }

   public void styleNode(IStyleAdapter adapter) {
      if (parseNode != null) {
         if (parseNodeInvalid) {
            validateParseNode(false);
         }
         parseNode.styleNode(adapter, null, null, -1);
      }
      // For this case unless it is a rootless node, we could walk up to the root node, use the start parselet from that language and generate it.
      // then presumably we'd have a parse node we could use.
      else
         throw new IllegalArgumentException("No parse tree for semantic node");
   }


   public CharSequence toModelString() {
      StringBuilder sb = new StringBuilder();
      int sz = size();
      sb.append("[");
      for (int i = 0; i < sz; i++) {
         Object node = get(i);
         if (node instanceof ISemanticNode)
            sb.append(((ISemanticNode)node).toModelString());
         else
            sb.append(node.toString());
      }
      return sb;
   }

   public static SemanticNodeList create(Object... args) {
      SemanticNodeList snl = new SemanticNodeList(args.length);
      for (int i = 0; i < args.length; i++)
         snl.add(args[i]);
      return snl;
   }

   public void setParseNodeValid(boolean val) {
      parseNodeInvalid = !val;
   }

   public boolean isParseNodeValid() {
      return !parseNodeInvalid;
   }

   public void changeLanguage(Language l) {
      int sz = size();

      if (parseNode != null)
         parseNode.changeLanguage(l);

      for (int i = 0; i < sz; i++) {
         Object node = get(i);
         if (node instanceof ISemanticNode)
            ((ISemanticNode) node).changeLanguage(l);
      }
   }

   public boolean deepEquals(Object o) {
      if (o == this)
         return true;
      if (!(o instanceof List))
         return false;

      int sz = size();
      List oList = (List) o;
      int osz = oList.size();
      if (sz != osz)
         return false;

      for (int i = 0; i < sz; i++) {
         Object otherProp = oList.get(i);
         Object thisProp = get(i);
         if (thisProp != otherProp &&
                 (thisProp == null || otherProp == null ||
                         !(thisProp instanceof ISemanticNode ? ((ISemanticNode) thisProp).deepEquals(otherProp) :
                                 // If thisProp is a String and otherProp is an IString we need to compare it the other way around
                                 (thisProp instanceof String ? otherProp.equals(thisProp) : thisProp.equals(otherProp))))) {
            return false;
         }
      }
      return true;
   }

   private static void diffAppend(StringBuilder diffs, Object val) {
      if (SemanticNode.debugDiffTrace)
         diffs = diffs; // set breakpoint here
      diffs.append(val);
   }

   public void diffNode(Object o, StringBuilder diffs) {
      if (o == this)
         return;
      if (!(o instanceof List)) {
         diffAppend(diffs, "Other item is not a list!");
         diffs.append(FileUtil.LINE_SEPARATOR);
         return;
      }

      int sz = size();
      List oList = (List) o;
      int osz = oList.size();

      int diffSz = sz;
      if (sz != osz) {
         diffAppend(diffs, "List size: " + sz + " != other size: " + osz);
         diffs.append(FileUtil.LINE_SEPARATOR);

         diffSz = Math.min(sz, osz);
      }

      for (int i = 0; i < diffSz; i++) {
         Object otherProp = oList.get(i);
         Object thisProp = get(i);
         // If both are null it is ok.  If thisProp is not null it has to equal other prop to go on.
         if (thisProp != otherProp) {
            if (thisProp instanceof ISemanticNode && otherProp != null) {
               ISemanticNode thisPropNode = (ISemanticNode) thisProp;
               StringBuilder newDiffs = new StringBuilder();
               thisPropNode.diffNode(otherProp, newDiffs);
               if (newDiffs.length() != 0) {
                  diffAppend(diffs, "[" + i + "]->");
                  diffs.append(newDiffs);
               }
               else if (!thisPropNode.deepEquals(otherProp)) {
                  diffAppend(diffs, CTypeUtil.getClassName(getClass().getName()) + " - [" + i + "] = " + thisProp + " other's = " + otherProp);
                  diffs.append(", ");
               }
            }
            else if (thisProp == null || otherProp == null || !thisProp.equals(otherProp)) {
               diffAppend(diffs, CTypeUtil.getClassName(getClass().getName()) + " - [" + i + "] = " + thisProp + " other's = " + otherProp);
               diffs.append(", ");
            }
         }
      }
      if (sz > diffSz) {
         diffs.append(" - extra in this: ");
         for (int i = diffSz; i < sz; i++) {
            diffs.append("[");
            diffs.append(i);
            diffs.append("] = ");
            diffs.append(get(i));
         }
      }
      else if (osz > diffSz) {
         diffs.append(" - extra in other: ");
         for (int i = diffSz; i < osz; i++) {
            diffs.append("[");
            diffs.append(i);
            diffs.append("] = ");
            diffs.append(oList.get(i));
         }
      }
   }

   public String getNodeErrorText() {
      return null;
   }

   public boolean getNotFoundError() {
      return false;
   }

   public String getNodeWarningText() {
      return null;
   }

   public String getDependencyDisabledText() {
      return null;
   }

   public SemanticNodeList refreshNode() {
      return this;
   }

   public boolean isTrailingSrcStatement() {
      return false;
   }

   @Override
   public void setParseErrorNode(boolean v) {
      throw new UnsupportedOperationException(); // No use case yet for setting this here
   }

   @Override
   public boolean getParseErrorNode() {
      return false;
   }

   public int getNodeCount() {
      int ct = 1;

      int sz = size();
      for (int i = 0; i < sz; i++) {
         Object node = get(i);
         if (node instanceof ISemanticNode) {
            ct += ((ISemanticNode) node).getNodeCount();
         }
      }
      return ct;
   }

   public int indexOf(Object elem) {
      return super.indexOf(elem);
   }

   public int identityIndexOf(Object elem) {
      int sz = size();
      for (int i = 0; i < sz; i++)
         if (get(i) == elem)
            return i;
      return -1;
   }

   public void serialize(BinfOutStream out) {
      int saveCurrentListId = out.currentListId;
      out.currentListId = parseletId;
      if (parseNode != null && !parseNode.getParselet().getSemanticValueIsArray()) {
         // This is a generic list of different sub-types.  The class of the sub-type is determined in the sub-parselet like normal
         out.writeUInt(BinfConstants.ListId);
      }
      else {
         // Here we save the lists parseletId because we need to use it's component type as the element type since the element may not have it's own parseNode
         out.writeUInt(parseletId);
      }

      int sz = size();
      out.writeUInt(sz);
      for (int i = 0; i < sz; i++) {
         Object val = get(i);
         out.writeValue(val);
      }
      out.currentListId = saveCurrentListId;
   }
}
