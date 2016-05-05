/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.lang.ISemanticNode;
import sc.util.PerfMon;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

public class ParentParseNode extends AbstractParseNode {
   NestedParselet parselet;
   boolean generated = false;

   public ArrayList<Object> children;

   // The semantic value (if any) 
   public Object value;

   public ParentParseNode() {}

   public ParentParseNode(Parselet p) {
      this.parselet = (NestedParselet) p;
   }

   public Parselet getParselet() {
      return parselet;
   }

   public void setParselet(Parselet p) {
      parselet = (NestedParselet) p;
   }

   public Object getSemanticValue() {
      return value;
   }

   public void setSemanticValue(Object val, boolean clearOld) {
      if (val == value)
         return;
      // First clear out the old value
      if (clearOld && value != null) {
         ParseUtil.clearSemanticValue(value, this);

         if (children != null) {
            for (Object p:children) {
               ParseUtil.clearSemanticValue(p, this);
            }
         }
      }
      value = val;
      if (value instanceof ISemanticNode)
         ((ISemanticNode) value).setParseNode(this);
   }

   public boolean addForReparse(Object node, Parselet p, int svIndex, int childIndex, int slotIndex, boolean skipSemanticValue, Parser parser, Object oldChildParseNode, DiffContext dctx,
                                 boolean removeExtraNodes, boolean parseArray) {
      // If there's no old value or we are inserting off the end, we must be inserting a new value
      if (children == null || childIndex >= children.size())
         return add(node, p, svIndex, slotIndex, skipSemanticValue, parser);
      else {
         boolean insert = false;
         if (node != oldChildParseNode) {
            Object oldNode = children.get(childIndex);
            if (parseArray && oldNode instanceof IParseNode) {
               IParseNode oldPN = (IParseNode) oldNode;
               // If this parse-node has not been reparsed yet (otherwise, we will duplicate it
               if (oldPN.getNewStartIndex() == -1) {
                  int startIx = oldPN.getOrigStartIndex();
                  // If the old parse-node in the matching slot fits in nicely after this one, just insert it rather than replacing it.  SameAgain may not be true here even after we've passed
                  // the
                  if (startIx + dctx.getNewOffsetForOldPos(startIx) == parser.currentIndex) {
                     insert = true;
                  }
               }
            }
            if (!insert) {
               if (node != children.get(childIndex)) {
                  Object replaced = children.set(childIndex, node);
               }
            }
            else {
               children.add(childIndex, node);
            }
         }

         if (removeExtraNodes) {
            clearParsedOldNodes(parser, childIndex + 1, dctx);
         }

         // Need to call this even if the parse node did not change.  A child of the parse-node might have changed.  This will
         // cause some properties to be changed to the same value but it may be good to signal the model that something underneath
         // has changed.
         return parselet.setSemanticValue(this, node, svIndex, slotIndex, skipSemanticValue, parser, false, true);
      }
   }

   public void clearParsedOldNodes(Parser parser, int startIx, DiffContext dctx) {
      int sz = children.size();
      for (int c = startIx; c < sz; c++) {
         Object oldChild = children.get(c);
         if (oldChild instanceof IParseNode) {
            IParseNode oldPN = (IParseNode) oldChild;
            int oldChildLen = oldPN.length();
            // We've already reparsed this old parse node so it must be in the right place.
            if (oldPN.getNewStartIndex() != -1)
               break;

            int oldStartOld = oldPN.getOrigStartIndex();
            int oldStartNew = oldStartOld + dctx.getNewOffsetForOldPos(oldStartOld+oldChildLen);
            if (oldStartNew + oldChildLen  <= parser.currentIndex) {
               // Used to have this test to avoid removing old parse nodes in the same again region which we haven't reached yet but
               /* caused us to we fail to remove parse-nodes we need to remove to do efficient incremental reparses.
               if (!dctx.sameAgain && oldStart > dctx.endChangeOldOffset) {
                  System.out.println("***");
                  //break;
               }
               */
               parselet.removeForReparse(parser, this, c);
               int newSz = children.size();
               // If we removed one readjust...
               if (newSz != sz) {
                  sz = newSz;
                  c--;
               }
            }
            else
               break;
         }
         // else - should we handle StringTokens here?  We could compute the startIndex relative to the parent and do the same thing?
      }
   }

   public boolean removeForReparse(int childIndex, int slotIndex, boolean skipSemanticValue, Parser parser) {
      // If there's no old value or we are inserting off the end, we must be inserting a new value
      if (children == null || childIndex >= children.size()) {
         System.err.println("*** Unable to remove parse node - no children");
         return false;
      }
      else {
         Object node = children.remove(childIndex);
         return parselet.removeFromSemanticValue(this, node, slotIndex, skipSemanticValue, parser, false, true);
      }
   }

   /**
    * Adds a child parse node to this parent node for the given child parselet.  The svIndex can be used for multi-valued
    * parselets to add the semantic-value to a specific array index for reparsing.  Usually it's -1 for appending the element.
    * The index specifies the slot index of the parselet 'p' in the parent.  If skipSemanticValue is true, the parse node is
    * added without updating the semantic value.
    */
   public boolean add(Object node, Parselet p, int svIndex, int index, boolean skipSemanticValue, Parser parser) {
      if (children == null)
         children = new ArrayList<Object>(parselet.parselets.size());

      boolean addChild = true;

      // Special case for adding a string to an "omitted" node.  In this case,
      // we'll just accumulate one string as our value.  When this guy gets 
      // added to its parentNode, it will get removed and the string goes on to represent
      // all of this element's children.
      if (node instanceof StringToken || node == null) {
         if (p.getDiscard() || p.getLookahead()) {
            if (node != null)
               return false;
            // TODO: else - is this the right code path here?
         }
         if (p.skip && !(parselet.needsChildren())) {
            if (children.size() == 1) {
               Object child = children.get(0);
               if (child instanceof StringToken) {
                  // For null nodes, be careful not to add an empty slot here cause then the next string won't get appended
                  if (node != null)
                     children.set(0, StringToken.concatTokens((StringToken)child, (StringToken)node));
                  addChild = false;
               }
            }
         }
      }
      else if (node instanceof String) {
         if (p.discard || p.lookahead)
            return false;
         if (p.skip && !(parselet.needsChildren())) {
            if (children.size() == 1)  {
               Object child = children.get(0);
               if (child instanceof String) { // TODO: should this be PString.isString?  See issue#24 where we are not collapsing string tokens into a single parse node
                  // this is n*n - the need to avoid the Strings...
                  children.set(0, ((String) child) + ((String) node));
                  addChild = false;
               }
            }
         }
      }
      else if (node instanceof IParseNode) {
         IParseNode pnode = (IParseNode) node;
         Parselet childParselet = pnode.getParselet();

         if (!childParselet.addResultToParent(pnode, this, index, parser))
            return false;
      }

      if (addChild)
         children.add(node);

      // Only nested parselets should be using the ParentParseNode
      return parselet.setSemanticValue(this, node, svIndex, index, skipSemanticValue, parser, false, false);
   }

   public void set(Object node, Parselet p, int index, boolean skipSemanticValue, Parser parser) {
      boolean setChild = true;

      // Special case for adding a string to an "omitted" node.  In this case,
      // we'll just accumulate one string as our value.  When this guy gets
      // added to its parentNode, it will get removed and the string goes on to represent
      // all of this element's children.
      if (node instanceof StringToken) {
         if (p.getDiscard() || p.getLookahead())
            return;
         if (p.skip && !(parselet.needsChildren())) {
            if (children.size() == 1) {
               Object child = children.get(0);
               if (child instanceof StringToken) {
                  children.set(0, StringToken.concatTokens((StringToken)child, (StringToken)node));
                  setChild = false;
               }
            }
         }
      }
      else if (node instanceof String) {
         if (p.discard || p.lookahead)
            return;
         if (p.skip && !(parselet.needsChildren())) {
            if (children.size() == 1)  {
               Object child = children.get(0);
               if (child instanceof String) { // TODO: should this be PString.isString?  See issue#24 where we are not collapsing string tokens into a single parse node
                  // this is n*n - the need to avoid the Strings...
                  children.set(0, ((String) child) + ((String) node));
                  setChild = false;
               }
            }
         }
      }
      else if (node instanceof IParseNode) {
         IParseNode pnode = (IParseNode) node;
         Parselet childParselet = pnode.getParselet();

         if (!childParselet.addResultToParent(pnode, this, index, parser))
            return;
      }

      if (setChild)
         children.set(index, node);

      // Only nested parselets should be using the ParentParseNode
      parselet.setSemanticValue(this, node, -1, index, skipSemanticValue, parser, true, false);
   }

   public void addGeneratedNode(Object node) {
      if (children == null)
         children = new ArrayList<Object>();

      children.add(node);
   }

   /** Faster version when we are generating strings a char at a time */
   public void addGeneratedNode(Object node, Parselet childParselet) {
      if (children == null)
         children = new ArrayList<Object>();

      if (childParselet.skip && !(parselet.needsChildren())) {
         if (children.size() == 1) {
            Object child = children.get(0);
            if (child instanceof StringToken) {
               children.set(0, StringToken.concatTokens((StringToken)child, (StringToken)node));
               return;
            }
         }
      }
      children.add(node);
   }

   public void addGeneratedNodeAt(int ix, Object node) {
      if (children == null)
         children = new ArrayList<Object>();

      if (ix > children.size())
         System.out.println("*** Adding generated node at invalid location");

      children.add(ix, node);
   }

   public CharSequence toSemanticString() {
      int sz;
      if (children == null || (sz = children.size()) == 0)
         return "";
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < sz; i++) {
         Object p = children.get(i);
         if (p instanceof IParseNode) {
            sb.append(((IParseNode) p).toSemanticString());
         }
         else if (p != null)
            sb.append((CharSequence) p);
      }
      return sb;
   }

   public int toSemanticStringLength() {
      int sz;
      int len = 0;
      if (children == null || (sz = children.size()) == 0)
         return 0;
      for (int i = 0; i < sz; i++) {
         Object p = children.get(i);
         if (p instanceof IParseNode) {
            len += ((IParseNode) p).toSemanticStringLength();
         }
         else if (p != null)
            len += ((CharSequence)p).length();
      }
      return len;
   }
   public String toString() {
      return formatString(null, null, -1);
   }

   private FormatContext createFormatContext(Object parSemVal, ParentParseNode curParseNode, int curChildIndex) {
      int initIndent;

      if (value instanceof ISemanticNode) {
         ISemanticNode sv = (ISemanticNode) value;
         initIndent = sv.getNestingDepth();
      }
      else
         initIndent = 0;
      return new FormatContext(curParseNode, curChildIndex, initIndent, getNextSemanticValue(parSemVal), parSemVal);
   }

   /**
    * Formats the parse node - turning it into a String.  Parent should specify the semantic node parent of this parse node.
    * If null is specified, it's no problem as long as this parse-node's semantic value has a parent.  Some primitive parse nodes have a string
    * semantic value with no ref to their parent.  For the spacing to be computed properly we need this context (for FormatContext.getNextSemanticValue())
    */
   public String formatString(Object parSemVal, ParentParseNode curParseNode, int curChildIndex) {
      // If the parse node is generated, we need to use the formatting process to add in
      // the spacing.  If the parse node was parsed, we toString it just as it
      // was parsed so we get back the identical input strings.
      // For non-generated parse nodes, the child.toString() should have all of the formatting already.  But we may have invalidated some children.
      // To get the proper spacing for those children, we need the parent parse nodes in the FormatContext - so they can find the prev/next chars to do proper spacing.
      // If there's overhead here, we could still optimize the case where there are no invalidated children nodes
      if (isGeneratedTree()) {
         FormatContext ctx = createFormatContext(parSemVal, curParseNode, curChildIndex);
         //ctx.append(FormatContext.INDENT_STR);
         PerfMon.start("format", false);
         format(ctx);
         PerfMon.end("format");
         return ctx.getResult().toString();
      }
      else {
         if (children == null)
              return "";
         if (children.size() == 1) {
            Object child = children.get(0);
            if (child == null)
               return "";
            return child.toString();
         }
         else {
            StringBuilder sb = new StringBuilder();
            for (Object o:children)
               if (o != null)
                  sb.append(o);
            return sb.toString();
         }
      }
   }

   public void styleNode(IStyleAdapter adapter, Object parSemVal, ParentParseNode parNode, int childIx) {
      // If the parse node is generated, we need to use the formatting process to add in
      // the proper spacing.  If the parse node was parsed, we toString it just as it
      // was parsed so we get back the identical input strings.
      if (generated) {
         // TODO: we should have a way to avoid this case - by re-formatting a file and converting generated nodes to
         // normal parse-nodes.  If we do it from the top-down, we can avoid the overhead of trying to recreate the
         // context for parse-nodes that need the 'nextChar' and prevChar to do proper spacing.
         FormatContext ctx = createFormatContext(parSemVal, parNode, childIx);
         adapter.setFormatContext(ctx);
         //ctx.append(FormatContext.INDENT_STR);
         formatStyled(ctx, adapter);
         adapter.setFormatContext(null);
      }
      else {
         if (children == null)
            return;
         Parselet childParselet;
         int sz = children.size();
         if (parselet.styleName != null)
            adapter.styleStart(parselet.styleName);
         for (int i = 0; i < sz; i++) {
            Object childNode = children.get(i);
            childParselet = parselet.getChildParselet(childNode, i);
            if (childNode != null) {
               Object childSV;
               if (childNode instanceof IParseNode) {
                  IParseNode childParseNode = (IParseNode) childNode;
                  childSV = childParseNode.getSemanticValue();
                  if (!(childSV instanceof ISemanticNode))
                      childParseNode.styleNode(adapter, getSemanticValue(), this, i);
                  else {
                     ISemanticNode childSVNode = (ISemanticNode) childSV;
                     if (childSVNode.getParseNode() == childNode)
                        ParseUtil.styleString(adapter, childSV, childParselet == null ? null : childParselet.styleName, null, false);
                     else
                        childParseNode.styleNode(adapter, getSemanticValue(), this, i);
                  }
               }
               else {
                  ParseUtil.styleString(adapter, childParselet.styleName, childNode.toString(), true);
               }
            }
         }
         if (parselet.styleName != null)
            adapter.styleEnd(parselet.styleName);
      }
   }

   public void addParent(ParentParseNode par, int currentChildIndex) {

   }

   private FormatContext.Entry visitForFormat(FormatContext ctx) {
      FormatContext.Entry ent = new FormatContext.Entry();
      List<FormatContext.Entry> pp = ctx.pendingParents;
      ent.parent = this;
      ent.currentIndex = 0;
      pp.add(ent);
      return ent;
   }

   private void endVisitForFormat(FormatContext ctx) {
      List<FormatContext.Entry> pp = ctx.pendingParents;
      pp.remove(pp.size()-1);
   }

   public void format(FormatContext ctx) {
      if (children == null || children.size() == 0)
         return;

      FormatContext.Entry ent = visitForFormat(ctx);
      try {
         for (Object p:children) {
            if (p instanceof IParseNode) {
               IParseNode node = (IParseNode) p;
               Parselet parselet = node.getParselet();
               if (parselet == null)
                  node.format(ctx);
               else
                  parselet.format(ctx, node);
            }
            else if (p != null) {
               ctx.append((CharSequence) p);
            }
            ent.currentIndex++;
         }
      }
      finally {
         endVisitForFormat(ctx);
      }
   }

   public IParseNode reformat() {
      if (children != null) {
         for (int i = 0; i < children.size(); i++) {
            Object childParseObj = children.get(i);
            if (childParseObj instanceof IParseNode) {
               IParseNode origChildParseNode = (IParseNode) childParseObj;
               IParseNode newChildParseNode = origChildParseNode.reformat();
               if (newChildParseNode != origChildParseNode)
                  children.set(i, newChildParseNode);
            }
         }
      }
      return super.reformat();
   }

   public void formatStyled(FormatContext ctx, IStyleAdapter adapter) {
      if (children == null || children.size() == 0)
         return;

      FormatContext.Entry ent = new FormatContext.Entry();
      ent.parent = this;
      ent.currentIndex = 0;
      ctx.pendingParents.add(ent);
      try {
         Parselet childParselet;
         int numChildren = parselet.parselets.size();
         int sz = children.size();
         for (int i = 0; i < sz; i++) {
            Object p = children.get(i);
            if (p instanceof IParseNode) {
               IParseNode node = (IParseNode) p;
               Parselet parselet = node.getParselet();
               if (parselet == null)
                  node.formatStyled(ctx, adapter);
               else {
                  String styleName = parselet.styleName;
                  if (styleName != null)
                     adapter.styleStart(styleName);
                  parselet.formatStyled(ctx, node, adapter);
                  if (parselet.styleName != null)
                     adapter.styleEnd(styleName);
               }
            }
            else if (p != null) {
               childParselet = parselet.parselets.get(i % numChildren);
               ParseUtil.styleString(adapter, null, childParselet.styleName, (CharSequence) p, true);
            }
            ent.currentIndex++;
         }
      }
      finally {
         ctx.pendingParents.remove(ctx.pendingParents.size()-1);
      }
   }

   public void computeLineNumberForNode(LineFormatContext ctx, IParseNode toFindPN) {
      if (children == null)
         return;

      FormatContext.Entry ent = visitForFormat(ctx);
      try {
         for (int i = 0; i < children.size(); i++) {
            Object childNode = children.get(i);
            if (childNode == toFindPN) {
               if (childNode instanceof IParseNode) {
                  IParseNode childParseNode = (IParseNode) childNode;
                  Object childValue = childParseNode.getSemanticValue();
                  if (childValue instanceof ISemanticNode) {
                     ISemanticNode semVal = (ISemanticNode) childValue;
                     // Some nodes mark their value as the end of the line - add in the contents of the parse node
                     if (semVal.isTrailingSrcStatement()) {
                        childParseNode.computeLineNumberForNode(ctx, toFindPN);
                        ctx.curLines--;
                     }
                  }
               }
               ctx.found = true;
               return;
            }
            else if (childNode instanceof IParseNode) {
               ((IParseNode) childNode).computeLineNumberForNode(ctx, toFindPN);
               if (ctx.found)
                  return;
            }
            else if (childNode instanceof CharSequence) {
               CharSequence childSeq = (CharSequence) childNode;
               ctx.append(childSeq);
            }
            ent.currentIndex++;
         }
      }
      finally {
         endVisitForFormat(ctx);
      }
   }


   public String toDebugString() {
      StringBuilder sb = new StringBuilder();
      if (parselet != null && parselet.getName() != null)
         sb.append(parselet.getName());
      if (children == null)
         sb.append("<empty>");
      else {
         for (Object o:children) {
            sb.append("[");
            if (o instanceof IParseNode)
               sb.append(((IParseNode)o).toDebugString());
            else
               sb.append(o);
            sb.append("]");
         }
      }
      return sb.toString();
   }

   public boolean refersToSemanticValue(Object sv) {
      if (value == sv)
         return true;

      if (children == null)
         return false;

      for (Object o:children) {
         if (o instanceof IParseNode) {
            boolean ref = ((IParseNode) o).refersToSemanticValue(sv);
            if (ref)
               return true;
         }
      }
      return false;
   }

   void copyGeneratedFrom(ParentParseNode from) {
      List fromChildren = from.children;
      if (fromChildren != null) {
         int sz = fromChildren.size();
         for (int i = 0; i < sz; i++) {
            Object o = fromChildren.get(i);
            addGeneratedNode(o);
         }
      }
   }

   void copyGeneratedAt(int ix, ParentParseNode from) {
      if (from.children != null)
         for (Object o:from.children)
            addGeneratedNodeAt(ix++, o);
   }

   public int firstChar() {
      if (children != null) {
         for (Object p:children) {
            int c =  ParseUtil.firstCharFromValue(p);
            if (c != -1)
               return c;
         }
      }
      return -1;
   }

   public boolean isGenerated() {
      return generated;
   }

   public int length() {
      if (children == null) return 0;
      int len = 0;
      for (int i = 0; i < children.size(); i++) {
         Object child = children.get(i);
         if (child instanceof CharSequence) {
            len += ((CharSequence) child).length();
         }
         else if (child != null) {
            len += child.toString().length();
         }
      }
      return len;
   }

   public int getSemanticLength() {
      if (children == null) return 0;
      int len = 0;
      boolean omitSpacing = true;

      // Going back to front since spacing is always at the end of a node
      for (int i = children.size()-1; i >= 0; i--) {
         Object child = children.get(i);
         if (child instanceof IParseNode) {
            IParseNode childParseNode = (IParseNode) child;
            if (omitSpacing) {
               if (!ParseUtil.isSpacingNode(childParseNode)) {
                  int newLen = childParseNode.getSemanticLength();
                  if (newLen > 0)
                     omitSpacing = false;
                  len += newLen;
               }
               // else - spacing node and we haven't found any semantic value so just skip it
            }
            // We've found some semantic value
            else
               len += childParseNode.length();
         }
         else if (child instanceof CharSequence) {
            int newLen = ((CharSequence) child).length();
            if (newLen > 0)
               omitSpacing = false;
            len += newLen;
         }
         else if (child != null) {
            len += child.toString().length();
         }
      }
      return len;
   }

   public boolean isEmpty() {
      if (children == null) return true;
      for (int i = 0; i < children.size(); i++) {
         Object child = children.get(i);
         if (child instanceof CharSequence) {
            if (((CharSequence) child).length() > 0)
               return false;
         }
         else if (child != null) {
            if (child.toString().length() > 0)
               return false;
         }
      }
      return true;
   }

   public char charAt(int ix) {
      if (children == null || ix < 0)
         throw new IndexOutOfBoundsException();
      int len = 0;
      for (int i = 0; i < children.size(); i++) {
         int nextLen;
         Object child = children.get(i);
         CharSequence childSeq;
         if (child instanceof CharSequence) {
            childSeq = (CharSequence) child;
            nextLen = childSeq.length();
         }
         else if (child != null) {
            nextLen = (childSeq = child.toString()).length();
         }
         else {
            nextLen = 0;
            childSeq = null;
         }

         int offset;
         if ((offset = ix - len) < nextLen) {
            return childSeq.charAt(offset);
         }
         len += nextLen;
      }
      throw new IndexOutOfBoundsException();
   }

   // TODO: not efficient for large sequences
   public CharSequence subSequence(int start, int end) {
      StringBuilder sb = new StringBuilder();
      for (int i = start; i < end; i++)
         sb.append(charAt(i));
      return sb.toString();
   }

   public void changeLanguage(Language newLanguage) {
      super.changeLanguage(newLanguage);
      if (children == null)
         return;
      for (Object child:children) {
         if (child instanceof IParseNode) {
            ((IParseNode) child).changeLanguage(newLanguage);
         }
      }
   }

   public void advanceStartIndex(int ix) {
      int myIx = getStartIndex();
      setStartIndex(myIx == -1 ? ix : myIx + ix);
      if (children != null) {
         for (Object child:children) {
            if (child instanceof IParseNode) {
               ((IParseNode) child).advanceStartIndex(ix);
            }
         }
      }
   }

   public ParentParseNode deepCopy() {
      ParentParseNode res = (ParentParseNode) super.deepCopy();
      ArrayList<Object> newChildren;
      if (children != null) {
         res.children = newChildren = new ArrayList<Object>(children.size());
         for (Object child:children) {
            if (child instanceof IParseNode)
               newChildren.add(((IParseNode) child).deepCopy());
            else
               newChildren.add(child);
         }
      }
      return res;
   }

   public ParentParseNode shallowCopy() {
      ParentParseNode res = (ParentParseNode) super.clone();
      ArrayList<Object> newChildren;
      if (children != null) {
         res.children = newChildren = new ArrayList<Object>(children.size());
         for (Object child:children) {
            if (child instanceof IParseNode)
               newChildren.add(child);
            else
               newChildren.add(child);
         }
      }
      return res;
   }

   public void updateSemanticValue(IdentityHashMap<Object, Object> oldNewMap) {
      if (value != null && value instanceof ISemanticNode) {
         ISemanticNode oldVal = (ISemanticNode) value;
         ISemanticNode newVal = (ISemanticNode) oldNewMap.get(oldVal);
         if (newVal != null) {
            if (oldVal.getParseNode().getParselet() == getParselet())
               newVal.setParseNode(this);
            value = newVal;
         }
         else { // This is a semantic value - like an extra list or something which was not included in the model itself.  So this parse node refers to something in the old parse tree which is probably not great so make a clone here.
            newVal = oldVal.deepCopy(ISemanticNode.SkipParseNode, null);
            newVal.setParseNode(this);
            value = newVal;
         }
      }
      if (children != null) {
         for (Object child:children) {
            if (child instanceof IParseNode)
               ((IParseNode) child).updateSemanticValue(oldNewMap);
         }
      }
   }

   /** Some semantic nodes compress the parse-tree and so cannot listen for changes on their children.  */
   public boolean isCompressedNode() {
      return children != null && children.size() == 1 && children.get(0) instanceof FormattedParseNode;
   }

   public ISemanticNode getNodeAtLine( NodeAtLineCtx ctx, int lineNum) {
      ISemanticNode res = super.getNodeAtLine(ctx, lineNum);
      if (res != null)
         return res;

      if (children == null)
         return null;

      List listVal = null;
      boolean isList = value instanceof List;
      if (isList)
         listVal = (List) value;
      int listIx = 0;

      FormatContext.Entry ent = visitForFormat(ctx);
      try {
         int childIx = 0;
         for (Object childNode:children) {
            // For repeat nodes, as we walk past each significant value, update the lastVal
            if (isList) {
               if (parselet == null || parselet.parselets == null)
                  System.err.println("*** parselet not initialized in getNodeAtLine");
               int parseletIndex = childIx % parselet.parselets.size();
               if (parseletIndex == 0 && listVal.size() > listIx) {
                  // We have a child of a parselet which produces a list.  For this current slot mapping:
                  // SKIP corresponds to whitespace which should not match the node.  PROPAGATE and ARRAY slots which support the appropriate semantic value that's tied into the tree do match.
                  if (parselet.parameterMapping == null || parselet.parameterMapping[parseletIndex] != NestedParselet.ParameterMapping.SKIP) {
                     if (childNode instanceof IParseNode) {
                        Object elemObj = listVal.get(listIx++);
                        if (elemObj instanceof ISemanticNode) {
                           ISemanticNode node = (ISemanticNode) elemObj;
                           if (node.getParentNode() != null) {
                              ctx.lastVal = node;
                           }
                        }
                     }
                  }
               }
            }
            if (PString.isString(childNode)) {
               CharSequence nodeStr = (CharSequence) childNode;
               int oldLine = ctx.curLines;
               ctx.append(nodeStr);
               if (ctx.curLines >= lineNum) {
                  // This is the case where we have a token which includes a newline - say }\n that's a child parselet for a block statement.  In this case, we need to match with the block statement as the semantic value.
                  // Specifically for the block statement note that we match the parent value as a node to match the end brace.  This is a little odd... should we really match the semicolonEOL parselet?
                  // for IntelliJ at least, the block statement uses the navigation element to point to the close brace so it fixes that oddity but if that becomes a problem for another framework we can fix this another way.
                  if (value instanceof ISemanticNode && value != ctx.lastVal)
                     ctx.lastVal = (ISemanticNode) value;
                  return ctx.lastVal;
               }
               // When a string token has a newline and it does not match, we don't want to count the next
               // line as part of the value.
               else if (oldLine != ctx.curLines) {
                  ctx.lastVal = null;
               }
            }
            else if (childNode != null) {
               res = ((IParseNode) childNode).getNodeAtLine(ctx, lineNum);
               if (res != null || ctx.curLines >= lineNum) {
                  // We have just passed the line we are looking for but haven't matched a value yet.  This means the parent node includes the line in question.
                  // If our value is suitable let's use it as the starting point.  If not, we'll pass the buck to our parent node.
                  if (res == null && value instanceof ISemanticNode) {
                     ISemanticNode semValue = (ISemanticNode) value;
                     if (semValue.getParentNode() != null)
                        return (ISemanticNode) value;
                  }
                  return res;
               }

               // We take the most specific value which has a parent, then walk up the parent hierarchy till we find
               // a statement at the right level.  So no resetting the current value here.
               //ctx.lastVal = parentVal;
            }
            childIx++;
            ent.currentIndex++;
         }
      }
      finally {
         endVisitForFormat(ctx);
      }
      return null;
   }

   public IParseNode findParseNode(int startIndex, Parselet matchParselet) {
      IParseNode res = super.findParseNode(startIndex, matchParselet);
      if (res != null)
         return res;

      if (children != null) {
         for (Object child:children) {
            if (child instanceof IParseNode) {
               res = ((IParseNode) child).findParseNode(startIndex, matchParselet);
               if (res != null)
                  return res;
            }
         }
      }
      return null;
   }

   public Object getSkippedValue() {
      return value;
   }

   public boolean isGeneratedTree() {
      if (generated)
         return true;
      if (value instanceof ISemanticNode && !((ISemanticNode) value).isParseNodeValid())
         return true;
      if (children != null) {
         int sz = children.size();
         for (int i = 0; i < sz; i++) {
            Object node = children.get(i);
            if (node instanceof ParentParseNode && ((ParentParseNode) node).isGeneratedTree())
               return true;
         }
      }
      return false;
   }

   public int resetStartIndex(int ix, boolean validate) {
      if (validate && ix != getStartIndex())
         System.out.println("*** Invalid start index found");
      startIndex = ix;
      newStartIndex = -1;

      if (children != null) {
         int sz = children.size();
         for (int i = 0; i < sz; i++) {
            Object child = children.get(i);
            if (child != null) {
               if (child instanceof IParseNode) {
                  ix = ((IParseNode) child).resetStartIndex(ix, validate);
               }
               else if (child instanceof CharSequence) {
                  ix += ((CharSequence) child).length();
               }
            }
         }
      }
      return ix;
   }

   public boolean isLastChild(int ix) {
      if (children == null)
         return true;

      int sz = children.size();
      int lastIx = sz - 1;
      if (lastIx == ix)
         return true;
      while (lastIx > ix && children.get(lastIx) == null)
         lastIx--;
      return lastIx == ix;
   }

   // Returns the index of the last parse-node which should be considering at the ending spot if there's an error
   // after this parse node.  If we return the children.size() it means there are no error parse nodes here.
   public int getEndingErrorIx() {
      if (children == null)
         return 0;

      int sz = children.size();
      boolean foundError = false;
      int i;
      for (i = sz - 1; i >= 0; i--) {
         Object child = children.get(i);
         if (child != null) {
            if (child instanceof ErrorParseNode) {
               foundError = true;
            }
            else {
               break;
            }
         }
      }
      return foundError ? i : sz;
   }

   public boolean isFirstChild(int ix) {
      if (children == null)
         return true;

      if (ix == 0)
         return true;

      int sz = children.size();
      if (ix >= sz) {
         System.err.println("*** Invalid child index");
         return false;
      }
      for (int i = 1; i < ix; i++) {
         if (children.get(i) != null)
            return false;
      }
      return true;
   }

   public void findStartDiff(DiffContext ctx, boolean atEnd) {
      if (children == null) {
         return;
      }
      int sz = children.size();
      // If our parented ended with an ErrorParseNode then there's an error after this parse-node.
      // We also may have an ErrorParseNode here at the end.  This is an indication of an incomplete parse
      // and so a signal for us to set the beforeFirstNode in front of the error so we do not accept it as
      // a complete parse when the extra text is added.
      int endingErrorIx = getEndingErrorIx();
      for (int i = 0; i < sz; i++) {
         Object child = children.get(i);
         if (child != null) {
            boolean lastChild = isLastChild(i);
            boolean last = atEnd && lastChild;
            if (child instanceof IParseNode) {
               IParseNode childNode = (IParseNode) child;
               childNode.findStartDiff(ctx, last);
               if (ctx.firstDiffNode != null) {
                  ctx.addChangedParent(this);
                  return;
               }
               // ARG - this doesn't work because we don't have a way to tease apart the boundary at the edge.  In some cases we want to include
               // an entire parent parse-node with it's children
               //if (!last || !endsWithError)
               if (i == 0 || !(childNode instanceof ErrorParseNode))
                  ctx.lastVisitedNode = childNode;
               // else - this node ends with an error so don't mark it as visited
            }
            else if (child instanceof CharSequence) {
               CharSequence childSeq = (CharSequence) child;
               int len = childSeq.length();
               int startChange = ctx.startChangeOffset;
               String text = ctx.text;
               int textLen = text.length();
               for (int c = 0; c < len; c++) {
                  if (startChange >= textLen || childSeq.charAt(c) != text.charAt(startChange)) {
                     ctx.firstDiffNode = this;
                     // Is there any content inside of this node?  If so, the change starts inside the node.  Otherwise,
                     // we need to choose the previous node so we "bracket" the changed region.
                     // TODO: In test re22/GenerateUCs32.sc - we detect the change inside of the first space so c == 1 here
                     // clear '2' is not right here - maybe we always use the lastVisitedNode?   Should we always do this for
                     // spacing parselets by adding a new 'glueContent' or something attribute to the parselet?
                     if (c < 2) {
                        IParseNode lastVisited = ctx.lastVisitedNode;
                        if (lastVisited != null)
                           ctx.beforeFirstNode = lastVisited.getParselet().getBeforeFirstNode(lastVisited);
                        else
                           ctx.beforeFirstNode = this;
                     }
                     else
                        ctx.beforeFirstNode = this;
                     ctx.startChangeOffset = startChange;
                     return;
                  }
                  else {
                     startChange++;
                  }
               }
               ctx.startChangeOffset = startChange;
               if (last && text.length() > startChange) {
                  ctx.firstDiffNode = this;
                  // Not using lastVisitedNode since in this case the containing parent node tree is all we need to find
                  // the right place to start changes.
                  ctx.beforeFirstNode = ctx.firstDiffNode;
                  return;
               }
            }
         }
      }
   }

   public void findEndDiff(DiffContext ctx) {
      if (children == null) {
         return;
      }
      int sz = children.size();
      IParseNode lastChildNode = null;
      for (int i = sz - 1; i >= 0; i--) {
         Object child = children.get(i);
         if (child != null) {
            if (child instanceof IParseNode) {
               IParseNode childNode = (IParseNode) child;
               childNode.findEndDiff(ctx);
               if (ctx.lastDiffNode != null) {
                  // If the lastDiff is a child of the firstDiffNode, we won't find it as we prune the first diff node
                  // Instead, treat the entire node as changed.
                  if (ctx.firstDiffNode == this) {
                     ctx.lastDiffNode = this;
                  }
                  // Also reset the afterLastNode if it happens to be the last child we've processed.  Once we are processing
                  // a change,
                  /*  this ends up marking way too much as changed in some cases */
                  /*
                  if (ctx.afterLastNode == lastChildNode) {
                     //ctx.afterLastNode = this;
                  }
                  */
                  return;
               }
               lastChildNode = childNode;
            }
            else if (child instanceof CharSequence) {
               CharSequence childSeq = (CharSequence) child;
               int len = childSeq.length();
               String text = ctx.text;
               for (int c = len - 1; c >= 0 && ctx.endChangeNewOffset >= 0 && ctx.endChangeOldOffset >= 0; c--) {
                  if (childSeq.charAt(c) != text.charAt(ctx.endChangeNewOffset)) {
                     ctx.lastDiffNode = this;
                     // TODO: if c != 0 or c != len - 1 can we optimize this and choose this node instead of the "afterLast' node
                     ctx.afterLastNode = ctx.lastVisitedNode;
                     ctx.addSameAgainChildren(ctx.afterLastNode);
                     break;
                  }
                  else {
                     ctx.endChangeOldOffset--;
                     ctx.endChangeNewOffset--;
                  }
               }
            }
         }
      }
      ctx.lastVisitedNode = this;
   }
}

