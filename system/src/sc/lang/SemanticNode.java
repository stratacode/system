/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.js.JSSettings;
import sc.lang.java.*;
import sc.layer.SrcEntry;
import sc.lifecycle.ILifecycle;
import sc.obj.EditorSettings;
import sc.type.*;
import sc.util.FileUtil;
import sc.util.PerfMon;
import sc.util.StringUtil;
import sc.parser.*;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

@JSSettings(jsLibFiles="js/javasys.js", replaceWith="jv_Object") // Because ISemanticNode and Element use parentNode as the name of the property, this type gets exposed to JS but we replace it with an object.
public abstract class SemanticNode implements ISemanticNode, ILifecycle {
   // These are the fields that represent these properties in the model.  We treat them differently from the other
   // properties which are really part of the semantic value.
   public final static Object PARENT_NODE_PROPERTY = TypeUtil.getPropertyMapping(ISemanticNode.class, "parentNode", ISemanticNode.class, null);
   public final static Object PARSE_NODE_PROPERTY = TypeUtil.getPropertyMapping(ISemanticNode.class, "parseNode", IParseNode.class, null);

   /**
    * This refers to the parse node from which this semantic node was generated. 
    * This will be the "root node" which produced this object - i.e. the one which
    * contains the model's definition in its parsed value.
    */
   transient public IParseNode parseNode;
   transient public boolean parseNodeInvalid = false;

   transient public ISemanticNode parentNode;

   // TODO performance: turn these into bitfields
   transient protected boolean initialized;
   transient protected boolean started;
   transient protected boolean validated;
   transient protected boolean processed;
   transient protected boolean transformed;

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

   public static boolean debugDiffTrace = false;

   public void init() {
      if (initialized)
          return;
      initialized = true;

      DynType type = TypeUtil.getPropertyCache(getClass());
      IBeanMapper[] semanticProps = type.getSemanticPropertyList();

      for (int i = 0; i < semanticProps.length; i++) {
         IBeanMapper mapper = semanticProps[i];
         Object val = PTypeUtil.getProperty(this, mapper.getField());
         if (val instanceof ILifecycle)
            ((ILifecycle) val).init();
      }
   }

   public void start() {
      if (started)
         return;
      started = true;

      DynType type = TypeUtil.getPropertyCache(getClass());
      IBeanMapper[] semanticProps = type.getSemanticPropertyList();

      for (int i = 0; i < semanticProps.length; i++) {
         IBeanMapper mapper = semanticProps[i];
         Object val = PTypeUtil.getProperty(this, mapper.getField());
         if (val instanceof ILifecycle)
            ((ILifecycle) val).start();
      }
   }

   public void validate() {
      if (validated)
         return;
      validated = true;

      DynType type = TypeUtil.getPropertyCache(getClass());
      IBeanMapper[] semanticProps = type.getSemanticPropertyList();

      for (int i = 0; i < semanticProps.length; i++) {
         IBeanMapper mapper = semanticProps[i];
         Object val = PTypeUtil.getProperty(this, mapper.getField());
         if (val instanceof ILifecycle)
            ((ILifecycle) val).validate();
      }
   }

   public void ensureValidated() {
      ModelUtil.ensureStarted(this, true);
   }

   public void setParseNodeValid(boolean val) {
      parseNodeInvalid = !val;
   }

   public void validateParseNode(boolean finalGen) {

      boolean isCompressedNode = parseNode != null && parseNode.isCompressedNode();
      if (parseNodeInvalid || isCompressedNode) {
         PerfMon.start("validateParseNode");
         regenerate(finalGen);
         PerfMon.end("validateParseNode");
      }
      else {
         DynType type = TypeUtil.getPropertyCache(getClass());
         IBeanMapper[] semanticProps = type.getSemanticPropertyList();

         for (int i = 0; i < semanticProps.length; i++) {
            IBeanMapper mapper = semanticProps[i];
            Object val = PTypeUtil.getProperty(this, mapper.getField());
            if (val instanceof ISemanticNode)
               ((ISemanticNode) val).validateParseNode(finalGen);
         }
      }
   }

   public void process() {
      if (processed)
         return;
      processed = true;

      DynType type = TypeUtil.getPropertyCache(getClass());
      IBeanMapper[] semanticProps = type.getSemanticPropertyList();

      for (int i = 0; i < semanticProps.length; i++) {
         IBeanMapper mapper = semanticProps[i];
         Object val = PTypeUtil.getProperty(this, mapper.getField());
         if (val instanceof ISemanticNode)
            ((ISemanticNode) val).process();
      }
   }

   public void stop() {
      initialized = false;
      started = false;
      validated = false;
      processed = false;

      DynType type = TypeUtil.getPropertyCache(getClass());
      IBeanMapper[] semanticProps = type.getSemanticPropertyList();

      for (int i = 0; i < semanticProps.length; i++) {
         IBeanMapper mapper = semanticProps[i];
         Object val = PTypeUtil.getProperty(this, mapper.getField());
         if (val instanceof ILifecycle) {
            if (val == this) {
               System.err.println("*** Recursive element tree - child: " + mapper.getPropertyName() + " refers to parent in stop method");
               return;
            }
            ((ILifecycle) val).stop();
         }
      }
   }

   /** Override this to implement a model transformation required to generate a representation in this new runtime language */
   public boolean transform(ILanguageModel.RuntimeType runtime) {
      if (transformed)
         return false;
      transformed = true;
      boolean any = false;
      DynType type = TypeUtil.getPropertyCache(getClass());
      IBeanMapper[] semanticProps = type.getSemanticPropertyList();

      for (int i = 0; i < semanticProps.length; i++) {
         IBeanMapper mapper = semanticProps[i];
         Object val = PTypeUtil.getProperty(this, mapper.getField());
         if (val instanceof ISemanticNode) {
            ISemanticNode nodeVal = (ISemanticNode) val;
            if (!nodeVal.getTransformed() && nodeVal.transform(runtime))
               any = true;
         }
      }
      return any;
   }

   public boolean needsTransform() {
      boolean any = false;
      DynType type = TypeUtil.getPropertyCache(getClass());
      IBeanMapper[] semanticProps = type.getSemanticPropertyList();

      for (int i = 0; i < semanticProps.length; i++) {
         IBeanMapper mapper = semanticProps[i];
         Object val = PTypeUtil.getProperty(this, mapper.getField());
         if (val instanceof ISemanticNode) {
            ISemanticNode nodeVal = (ISemanticNode) val;
            if (nodeVal.needsTransform())
               any = true;
         }
      }
      return any;
   }

   public IParseNode getParseNode() {
      return parseNode;
   }

   public void setParseNode(IParseNode pn) {
      parseNode = pn;
   }

   @EditorSettings(visible=false)
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

   public ILanguageModel getLanguageModel() {
      return (ILanguageModel) getRootNode();
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

   public String toModelString() {
      return toModelString(new IdentityHashMap(), 0);
   }

   public String toModelString(Map visited, int indent) {
      if (visited.get(this) != null)
          return ".o.";

      visited.put(this, Boolean.TRUE);

      Field[] fields = RTypeUtil.getFields(getClass());
      StringBuffer sb = new StringBuffer();
      sb.append(getClass().getName().substring(getClass().getPackage().getName().length()+1));
      sb.append(" {");
      sb.append(FileUtil.LINE_SEPARATOR);
      indent++;
      for (int i = 0; i < fields.length; i++) {
         String name = fields[i].getName();
         if (name.equals("parseNode") || name.equals("parentNode")) {
            Object val = PTypeUtil.getProperty(this, fields[i]);
            if (val == null)
                sb.append("Warning - NULL for: " + name + FileUtil.LINE_SEPARATOR);
            continue;
         }

         if (!isSemanticProperty(fields[i]))
            continue;

         sb.append(indentStr(indent));
         sb.append(name);
         sb.append(" = ");
         sb.append(getStringValue(PTypeUtil.getProperty(this, fields[i]), visited, indent));
         sb.append(FileUtil.LINE_SEPARATOR);
      }
      indent--;
      sb.append(indentStr(indent));
      sb.append("}");
      return sb.toString();
   }

   static protected boolean isSemanticProperty(IBeanMapper prop) {
      if (prop == null)
         return false;  // 0 is a reserved slot for properties
      Field f = (Field) prop.getField();
      if (f == null)
         return false;

      return isSemanticProperty(f);
   }

   static protected boolean isSemanticProperty(Field f) {
      int modifiers = f.getModifiers();
      if ((modifiers & (Modifier.STATIC | Modifier.TRANSIENT)) != 0)
         return false;

      if (f.getName().equals("parseNode") || f.getName().equals("parentNode"))
         return false;

      return true;
   }

   public String toHeaderString() {
      Field[] fields = RTypeUtil.getFields(getClass());

      StringBuffer sb = new StringBuffer();

      sb.append(getClass().getName());
      sb.append("(");

      boolean first = true;

      for (int i = 0; i < fields.length; i++)
      {
         Field field = fields[i];
         if (!isSemanticProperty(field))
            continue;

         String name = field.getName();

         Object obj = PTypeUtil.getProperty(this, field);
         if (obj != null && !(obj instanceof ISemanticNode) && !(obj instanceof List))
         {
            if (!first)
               sb.append(",");
            first = false;
            sb.append(name);
            sb.append("=");
            sb.append(obj);
         }
      }

      sb.append(")");

      return sb.toString();
   }

   private static String indentStr(int indent) {
      StringBuffer sb = new StringBuffer();
      for (int i = 0; i < indent; i++)
         sb.append("   ");
      return sb.toString();
   }

   private static String getStringValue(Object obj, Map visited, int indent) {
      if (obj == null)
         return null;
      if (obj instanceof List) {
         List l = (List) obj;
         StringBuffer sb = new StringBuffer();
         sb.append("[");
         for (int i = 0; i < l.size(); i++) {
            if (i != 0)
               sb.append(", ");
            sb.append(getStringValue(l.get(i), visited, indent));
         }
         sb.append("]");
         return sb.toString();
      }
      if (obj instanceof SemanticNode)
         return ((SemanticNode)obj).toModelString(visited, indent);
      return obj.toString();
   }

   public void setProperty(Object selector, Object value) {
      setProperty(selector, value, true, true);
   }

   /**
    * This method sets a semantic properties value.  if the value is a SemanticNode, it sets the parent
    * property of that node.  If changeParseTree is true and this object is started and there is an associated
    * parse-node, that parse-tree is updated incrementally.
    */
   public void setProperty(Object selector, Object value, boolean changeParseTree, boolean changeParent) {
      if (value instanceof ISemanticNode && changeParent) {
         ISemanticNode semVal = (ISemanticNode) value;
         semVal.setParentNode(this);
      }

      TypeUtil.setProperty(this, selector, value);
      if (initialized) {
         // Need to lazily initialize any non-initialized nodes.  As the model is changed, new nodes
         // can be added so we lazily init them as they are added to an already initialized node.
         if (value instanceof ILifecycle) {
            ILifecycle lval = (ILifecycle) value;
            if (!lval.isInitialized()) {
               lval.init();
               if (started)
                  lval.start();
               if (validated)
                  lval.validate();
               if (processed)
                  lval.process();
            }
         }

         if (changeParseTree) {
            IParseNode parentParseNode = getParseNode();
            if (parentParseNode != null) {
               Parselet pp = parentParseNode.getParselet();
               if (pp instanceof NestedParselet) {
                  NestedParselet parentParselet = (NestedParselet) pp;
                  // Invalidate the old parse tree - will be regenerated next time it needs
                  // This attempts to do things incrementally but if not regenerates now so there's always a valid language
                  // representation associated with the model.  Nice for debugging but too slow for production.
                  if (!parentParselet.semanticPropertyChanged(parentParseNode, this, selector, value))
                     System.err.println("*** Unable to regenerate after a property change: " + this + " property: " + selector + " value: " + value);
               }
            }
            else {
               regenerateIfTracking(false);
            }
         }
      }
   }

   public boolean containsChild(Object toReplace) {
      DynType type = TypeUtil.getPropertyCache(getClass());
      IBeanMapper[] props = type.getSemanticPropertyList();

      for (int i = 0; i < props.length; i++) {
         IBeanMapper prop = props[i];
         Object thisProp = PTypeUtil.getProperty(this, prop.getField());
         if (thisProp == toReplace)
            return true;
      }
      return false;
   }

   /**
    * Provided with another semantic node that should be type equivalent with this one, we first
    * find this node in the parent's value;
    */
   public int replaceChild(Object toReplace, Object other) {
      DynType type = TypeUtil.getPropertyCache(getClass());
      IBeanMapper[] semanticProps = type.getSemanticPropertyList();

      for (int i = 0; i < semanticProps.length; i++) {
         IBeanMapper prop = semanticProps[i];
         // Not sure why this one is using the property, not the field
         Object thisProp = PTypeUtil.getProperty(this, prop.getField());
         if (thisProp == toReplace) {
            setProperty(prop, other);
            return i;
         }
      }
      return -1;
   }

   public int removeChild(Object toRemove) {
      DynType type = TypeUtil.getPropertyCache(getClass());
      IBeanMapper[] props = type.getSemanticPropertyList();
      for (int i = 0; i < props.length; i++) {
         IBeanMapper prop = props[i];
         Object field = prop.getField();
         Object thisProp = PTypeUtil.getProperty(this, field);
         if (thisProp == toRemove) {
            setProperty(field, null);
            return i;
         }
      }
      return -1;
   }

   /**
    * The semantic node classes are treated like value classes - they are equal if all of their properties
    * are equal.
    */
   public boolean deepEquals(Object other) {
      if (other == this)
         return true;

      if (other == null)
         return false;

      if (getClass() != other.getClass())
         return false;

      DynType type = TypeUtil.getPropertyCache(getClass());
      IBeanMapper[] props = type.getSemanticPropertyList();
      for (int i = 0; i < props.length; i++) {
         Field field = (Field) props[i].getField();
         Object thisProp = PTypeUtil.getProperty(this, field);
         Object otherProp = PTypeUtil.getProperty(other, field);
         // If both are null it is ok.  If thisProp is not null it has to equal other prop to go on.
         if (thisProp != otherProp &&
                (thisProp == null || otherProp == null ||
                   !(thisProp instanceof ISemanticNode ? ((ISemanticNode) thisProp).deepEquals(otherProp) : thisProp.equals(otherProp)))) {
            return false;
         }
      }
      return true;
   }

   static void diffAppend(StringBuilder diffs, Object val) {
      if (debugDiffTrace)
         diffs = diffs; // set breakpoint here
      diffs.append(val);
   }

   /**
    * The semantic node classes are treated like value classes - they are equal if all of their properties
    * are equal.
    */
   public void diffNode(Object other, StringBuilder diffs) {
      if (other == this)
         return;

      if (other == null) {
         diffAppend(diffs, "No other model for comparison against this: " + toString());
         return;
      }

      if (getClass() != other.getClass()) {
         diffAppend(diffs, "class mismatch - this: " + CTypeUtil.getClassName(getClass().getName()) + " = " + toString() + " other: " + CTypeUtil.getClassName(other.getClass().getName()) + " = " + other.toString());
         return;
      }

      DynType type = TypeUtil.getPropertyCache(getClass());
      IBeanMapper[] props = type.getSemanticPropertyList();
      for (int i = 0; i < props.length; i++) {
         Field field = (Field) props[i].getField();
         Object thisProp = PTypeUtil.getProperty(this, field);
         Object otherProp = PTypeUtil.getProperty(other, field);
         // If both are null it is ok.  If thisProp is not null it has to equal other prop to go on.
         if (thisProp != otherProp) {
            if (thisProp instanceof ISemanticNode && otherProp != null) {
               ISemanticNode thisPropNode = (ISemanticNode) thisProp;
               StringBuilder newDiffs = new StringBuilder();
               thisPropNode.diffNode(otherProp, newDiffs);
               if (newDiffs.length() != 0) {
                  diffAppend(diffs, field.getName());
                  diffAppend(diffs, "->");
                  diffAppend(diffs, newDiffs);
               }
               else if (!thisPropNode.deepEquals(otherProp)) {
                  diffAppend(diffs, CTypeUtil.getClassName(getClass().getName()) + "." + field.getName() + " = " + thisProp + " other's = " + otherProp);
                  diffAppend(diffs, FileUtil.LINE_SEPARATOR);
               }
            }
            else if (thisProp == null || otherProp == null || !thisProp.equals(otherProp)) {
               diffAppend(diffs, CTypeUtil.getClassName(getClass().getName()) + "." + field.getName() + " = " + thisProp + " other's = " + otherProp);
            }
         }
      }
   }

   public boolean equals(Object other) {
      return deepEquals(other);
   }

   public int hashCode() {
      int hashCode = 0;
      DynType type = TypeUtil.getPropertyCache(getClass());
      IBeanMapper[] props = type.getSemanticPropertyList();
      for (int i = 0; i < props.length; i++) {
         IBeanMapper mapper = props[i];
         Object thisProp = PTypeUtil.getProperty(this, mapper.getField());
         if (thisProp == this) {
            System.out.println("*** Ack!");
            continue;
         }
         if (thisProp != null)
            hashCode += thisProp.hashCode();
      }
      return hashCode;
   }

   public String toLanguageString() {
      // TODO: should be some way to get the language by walking up the tree.  Then find the parselet by mapping the implementation class
      // to the parselet.  Even if there's no parent, why not have a way to choose the first parselet... they will typically be the same anyway though
      // maybe they will leave artifacts of the wrong language in the parse node tree if this node ever does get added to a tree that has a langauge.
      return toLanguageString(null);
   }

   public String toLanguageString(Parselet parselet) {
      if (parseNode != null) {
         if (parseNodeInvalid) {
            validateParseNode(false);
         }
         if (parseNode == null)
            return "<invalid-semantic-node>";
         return parseNode.toString();
      }

      // For this case unless it is a rootless node, we could walk up to the root node, use the start parselet from that language and generate it.
      // then presumably we'd have a parse node we could use.  Or maybe a short cut, we could just ask for and validate the language definition of the parent nodes
      // which would in most cases re-generate this one.  Right now the only case this happens so far is toString and there's an easy workaround for that
      else if (parselet == null)
         throw new IllegalArgumentException("No parse tree for semantic node");
      else {
         Object result = parselet.generate(newGenerateContext(parselet), this);
         if (result instanceof ParseError) {
            System.err.println("*** Error generating ad-hoc model: " + this);
            return "Error translating";
         }
         return result.toString();
      }
   }

   public String getUserVisibleName() {
      return CTypeUtil.getClassName(getClass().getName());
   }

   public String toDefinitionString(boolean addAt) {
      return toDefinitionString(0, addAt, addAt);
   }

   public String toDefinitionString() {
      return toDefinitionString(0, true, true);
   }

   public String toDeclarationString() {
      return toSafeLanguageString();
   }

   public String toDefinitionString(int indent, boolean addAt, boolean addNear) {
      StringBuilder sb = new StringBuilder();
      sb.append(toDeclarationString().trim());
      if (addAt)
         appendAtString(sb, indent, false, true, addNear, null);
      else if (addNear)
         sb.append(computeNearString(indent));
      return sb.toString();
   }

   public String toLocationString() {
      return toLocationString(null, true, true, true);
   }

   public String toLocationString(boolean addFile, boolean addNear) {
      return toLocationString(null, addFile, true, addNear);
   }

   public String toLocationString(Parselet parselet, boolean addFile, boolean addAt, boolean noNear) {
      StringBuilder sb = new StringBuilder();
      appendAtString(sb, 0, addFile, addAt, noNear, parselet);
      return sb.toString();
   }

   private boolean regenerateForToString(StringBuilder sb, Parselet parselet) {
      if (parseNode == null) {
         regenerate(false);
      }
      if (parseNode == null && parselet != null) {
         // If given a parselet and there's no definition that we can use automatically, we'll generate a temporary
         // one so we see this definition.
         Object result = parselet.generate(newGenerateContext(parselet), this);
         if (result instanceof IParseNode)
            sb.append(result.toString());
         return true;
      }
      return false;
   }

   private GenerateContext newGenerateContext(Parselet parselet) {
      return parselet.getLanguage().newGenerateContext(parselet, this);
   }

   protected IParseNode getAnyChildParseNode() {
      return null;
   }

   protected int getStartIndex() {
      if (parseNode != null)
         return parseNode.getStartIndex();
      IParseNode pp = getAnyChildParseNode();
      if (pp != null)
         return pp.getStartIndex();
      return -1;
   }

   private void appendAtString(StringBuilder sb, int indent, boolean addFile, boolean addAt, boolean addNear, Parselet parselet) {
      IParseNode pp = parseNode;
      if (pp == null)
         pp = getAnyChildParseNode();

      int startIx = getStartIndex();

      if (startIx != -1) {
         ISemanticNode rootNode = getRootNode();
         if (rootNode != null && rootNode instanceof ILanguageModel) {
            ILanguageModel rootModel = (ILanguageModel) rootNode;
            List<SrcEntry> srcEnts = rootModel.getSrcFiles();
            if (srcEnts != null && srcEnts.size() > 0) {
               String fileName = srcEnts.get(0).absFileName;
               if (addFile) {
                  sb.append("File: ");
                  sb.append(fileName);
               }
               if (addAt) {
                  String atStr = ParseUtil.charOffsetToLine(new File(fileName), startIx);
                  if (atStr == null && startIx != 0)
                     atStr = "char " + startIx;
                  if (atStr != null) {
                     //sb.append(" at ");
                     sb.append(" ");
                     sb.append(atStr);
                  }
               }
            }
            else if (rootModel.getEditorContext() != null) {
               EditorContext ctx = rootModel.getEditorContext();
               if (addFile) {
                  sb.append("Script: ");
                  sb.append(ctx.getCurrentFile());
               }
               if (addAt) {
                  sb.append(" at ");
                  sb.append(ctx.getCurrentFilePosition());
               }
            }
            else if (addAt) {
               sb.append(" at ");
               if (rootNode.getParseNode() != null)
                  sb.append(ParseUtil.charOffsetToLine(rootNode.toLanguageString(), startIx));
               else
                  sb.append("<unknown line>");
            }
            if (addNear)
               sb.append(computeNearString(indent));
         }
         else {
            sb.append(" ");
            sb.append(toSafeLanguageString());
         }
      }
      else {
         sb.append(" ");
         sb.append(toSafeLanguageString());
      }
   }

   public String computeNearString(int indent) {
      IParseNode pp = parseNode;
      if (pp == null) {
         pp = getAnyChildParseNode();
      }
      if (pp == null) {
         // This node does not have a parse-node but we do have a file - just don't print anything (for imports right now)
         if (getStartIndex() != -1)
            return "";
         return ": <no source available>";
      }

      StringBuilder sb = new StringBuilder();
      sb.append("\n");
      sb.append(StringUtil.indent(indent + 1));
      sb.append("near: ");
      String trimmed = pp.toString().trim();
      int parseNodeLen = trimmed.length();
      if (parseNodeLen > 75) {
         sb.append(trimmed.subSequence(0, 35));
         sb.append(" ... ");
         sb.append(trimmed.subSequence(parseNodeLen-35, parseNodeLen));
      }
      else {
         sb.append(trimmed);
      }
      return sb.toString();
   }

   public ISemanticNode deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap) {
      SemanticNode copy = (SemanticNode) RTypeUtil.createInstance(getClass());
      Parselet p;
      ParentParseNode newPP;
      IParseNode newP, oldP;

      int propagateOptions = options;
      if ((options & CopyParseNode) != 0) {
         propagateOptions = (options & ~CopyParseNode) | SkipParseNode;
         if (oldNewMap != null)
            throw new IllegalArgumentException("CopyParseNode does not support oldNewMap");
         oldNewMap = new IdentityHashMap<Object,Object>();
      }

      if (oldNewMap != null)
         oldNewMap.put(this, copy);

      DynType type = TypeUtil.getPropertyCache(getClass());
      IBeanMapper[] props = type.getSemanticPropertyList();
      for (int i = 0; i < props.length; i++) {
         IBeanMapper mapper = props[i];
         Field field = (Field) mapper.getField();
         Object oldVal = PTypeUtil.getProperty(this, field);
         Object val;
         if (oldVal instanceof ISemanticNode) {
            // This clones both the semantic node and if CopyParseNode is set, it also copies the parsed representation of that node.
            val = ((ISemanticNode) oldVal).deepCopy(propagateOptions, oldNewMap);
         }
         else
            val = oldVal;

         if (val != null) {
            copy.setProperty(field, val);
         }
      }

      if ((options & CopyInitLevels) != 0) {
         copy.initialized = initialized;
         copy.started = started;
         copy.validated = validated;
         copy.processed = processed;
      }

      if ((options & SkipParseNode) == 0) {
         oldP = parseNode;
         if (oldP != null && (p = oldP.getParselet()) != null) {
            if ((options & CopyParseNode) == 0) {
               // Create a dummy parse node to preserve the parselet mapping for this clone.  We can use this to regenerate
               // the node more easily after the copy.  Otherwise we need to start at a node with a known parselet.
               copy.parseNode = newPP = new ParentParseNode(p);
               copy.parseNode.setSemanticValue(copy, true);
               newPP.setStartIndex(oldP.getStartIndex());
               copy.parseNodeInvalid = true;
            }
            else {
               copy.parseNode = oldP.deepCopy();
               newP = copy.parseNode;

               PerfMon.start("updateSemanticValue");

               // This goes and replaces all semantic values in our map so that the new parse nodes point to the new semantic values and vice versa.
               newP.updateSemanticValue(oldNewMap);

               PerfMon.end("updateSemanticValue");

               // This is close to what we want but it would need a new option.  Right now, this is used to update only the semantic value's
               // pointer to the parse node.  It requires that the semantic values in the two trees match to find a match.   We need a version
               // which updates both directions.  For now, we'll do it using the oldNewMap.
               //p.updateParseNodes(copy, newPP);
               //
            }
         }
         else {
            // Parsenode does not get copied so this can't be invalid yet.
            copy.parseNodeInvalid = false;
         }
      }

      return copy;
   }

   public boolean getTransformed() {
      return transformed;
   }

   /** If you create a semantic value by hand that you might want to generate as a top-level object later, this method lets you associate a parselet with that semantic value. */
   public void setParselet(Parselet p) {
      if (parseNode == null) {
         parseNode = new ParentParseNode(p);
         parseNode.setSemanticValue(this, true);
         parseNodeInvalid = true;
      }
   }

   public void regenerateIfTracking(boolean finalGen) {
      if (parseNode == null) {
         if (parentNode == null) {
            // This error occurs kind of unavoidably when we've stripped the parse node from a semantic value and
            // then go and transform the model.  It should be ok at least for root nodes for the parentNode to be null.
            //System.out.println("*** Unable to regenerate node - no parent with a parse node found.");
            return;
         }
         // Don't propagate these events up the tree if it's not part of the real semantic value (e.g. the generated rootType of a Template)
         if (!parentNode.isSemanticChildValue(this))
            return;
         parentNode.regenerateIfTracking(finalGen);
      }
      else {
         NestedParselet parselet = (NestedParselet) parseNode.getParselet();
         if (parselet.language.trackChanges)
            parselet.regenerate((ParentParseNode) parseNode, finalGen);
         else
            setParseNodeValid(false);
      }
   }

   public boolean isSemanticChildValue(ISemanticNode child) {
      return true;
   }

   public boolean regenerate(boolean finalGen) {
      if (parseNode == null) {
         if (parentNode == null) {
            // This error occurs kind of unavoidably when we've stripped the parse node from a semantic value and
            // then go and transform the model.  It should be ok at least for root nodes for the parentNode to be null.
            //System.out.println("*** Unable to regenerate node - no parent with a parse node found.");
            return false;
         }
         // Some children, like the hiddenBody element are not semantic children even though they set parentNode
         if (!parentNode.isSemanticChildValue(this))
            return false;
         if (parentNode.regenerate(finalGen))
            parseNodeInvalid = false;
         else {
            System.err.println("*** regenerate failed for node missing a parse node: " + this.getClass());
            return false;
         }
      }
      else {
         NestedParselet parselet = (NestedParselet) parseNode.getParselet();

         if (parselet.regenerate((ParentParseNode) parseNode, finalGen))
            parseNodeInvalid = false;
         else {
            System.err.println("*** regenerate failed for: " + this.getClass());
            return false;
         }
      }
      return true;
   }

   public void styleNode(IStyleAdapter adapter) {
      styleNode(adapter, null);
   }

   public void styleNode(IStyleAdapter adapter, Parselet parselet) {
      if (parseNode != null) {
         if (parseNodeInvalid)
            validateParseNode(false);
         parseNode.styleNode(adapter, null, null, -1);
      }
      // For this case unless it is a rootless node, we could walk up to the root node, use the start parselet from that language and generate it.
      // then presumably we'd have a parse node we could use.
      else if (parselet == null)
         throw new IllegalArgumentException("No parse tree for semantic node");
      else {
         Object result = parselet.generate(newGenerateContext(parselet), this);
         if (result instanceof ParseError) {
            System.err.println("*** Error styling generated model: " + this);
            adapter.styleString("Error translating", false, null, null);
         }
         adapter.styleString(result.toString(), false, null, null);
      }
   }

   public void changeLanguage(Language l) {
      DynType type = TypeUtil.getPropertyCache(getClass());
      IBeanMapper[] semanticProps = type.getSemanticPropertyList();

      if (parseNode != null)
         parseNode.changeLanguage(l);

      for (int i = 0; i < semanticProps.length; i++) {
         IBeanMapper mapper = semanticProps[i];
         Object val = PTypeUtil.getProperty(this, mapper.getField());
         if (val instanceof ISemanticNode)
            ((ISemanticNode) val).changeLanguage(l);
      }
   }

   public boolean isParseNodeValid() {
      return !parseNodeInvalid;
   }

   public String toSafeLanguageString() {
      if (parseNode == null || parseNodeInvalid)
         return getUserVisibleName();
      return toLanguageString();
   }

   /** Override to provide per-node error support */
   public String getNodeErrorText() {
      return null;
   }

   public boolean getNotFoundError() {
      return false;
   }

   public boolean hasErrors() {
      return getNodeErrorText() != null;
   }

   /** Override for per-node warning support */
   public String getNodeWarningText() {
      return null;
   }

   public String getDependencyDisabledText() {
      return null;
   }

   /** Returns the statement which is aligned to the source for debugging purposes that contains this node. */
   public ISrcStatement getEnclosingSrcStatement() {
      for (ISemanticNode pnode = parentNode; pnode != null; pnode = pnode.getParentNode())
         if (pnode instanceof ISrcStatement)
            return (ISrcStatement) pnode;
      return null;
   }

   public ISemanticNode refreshNode() {
      return this;
   }

   public boolean sameSrcLocation(ISemanticNode st) {
      if (st == null)
         return false;
      if (st.getClass() != this.getClass())
         return false;

      IParseNode myNode = getParseNode();
      IParseNode stNode = st.getParseNode();

      int myIx = myNode == null ? -1 : myNode.getStartIndex();

      if (myNode != null && stNode != null && isParseNodeValid() && st.isParseNodeValid() && myIx == stNode.getStartIndex() && myIx != -1) {
         if (deepEquals(st))
            return true;
      }
      return false;
   }

   public boolean isTrailingSrcStatement() {
      return false;
   }
}
