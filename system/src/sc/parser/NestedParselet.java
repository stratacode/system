/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.dyn.DynUtil;
import sc.dyn.IDynObject;
import sc.lang.ISemanticNode;
import sc.lang.SemanticNodeList;
import sc.lang.java.ITypeDeclaration;
import sc.type.RTypeUtil;
import sc.type.BeanMapper;
import sc.util.FileUtil;
import sc.type.TypeUtil;
import sc.util.PerfMon;

import java.util.*;

public abstract class NestedParselet extends Parselet implements IParserConstants {
   public boolean suppressSpacing = false;
   public boolean enableSpacing = false;
   public boolean disableTagMode = false;
   public boolean enableTagMode = false;
   public boolean suppressNewlines = false;
   public boolean pushIndent = false;
   public boolean popIndent = false;

   public ArrayList<Parselet> parselets = new ArrayList<Parselet>();
   public boolean allowNullElements = false;
   public boolean allowEmptyPartialElements = false;

   /** From the IDE's perspective, by default a tree element  */
   public boolean complexStringType = false;

   enum ParameterMapping {
      SKIP,      //  empty string - return this slot as the parentNode's value
      PROPAGATE, // "." in the param name slot
      ARRAY,     // []
      STRING,    // ''
      // "*" in the param name slot... implies the child is skipped - use the child's property mappings
      // to populate the properties on the parentNode during the merge of the semantic value.
      INHERIT,
      NAMED_SLOT // Just a name for a property to assign
   }


   /** Args in incremental update methods */
   public enum ChangeType {
      ADD,
      REPLACE,
      REMOVE
   }

   /** Specifies how we handle each parsed child of this parselet when building the semantic value */
   ParameterMapping[] parameterMapping = null;

   /** When building the semantic value, this contains the property selectors for each child of the parselet */
   Object [] slotMapping = null;

   String [] genMapping = null;

   int propagatedValueIndex = -1;

   /** For results which are arrays, this stores the type of elements in the array */
   Class resultComponentClass;

   /**
    * Specifies the setter for the property when we have a repeating sequence which we chain together
    * on a certain field.
    */
   Object[] chainedPropertyMappings = null;

   enum ParameterType { DEFAULT, ARRAY, STRING, PROPAGATE /* slot has a '.' */, INHERIT /* slot has a '*' */ };

   ParameterType parameterType = ParameterType.DEFAULT;

   /** The indexes used for any "*" (aka INHERIT) slots */
   List<Integer> inheritSlots = null;

   /** Set this to a parselet to use when we want to try and reparse an element after skipping over error text.  This
    * parselet is applied whenever we encounter an error.  As long as it matches */
   public Parselet skipOnErrorParselet = null;

   public NestedParselet() {
      super();
   }

   public NestedParselet(String name, int options)
   {
      super(name, options);
   }

   public NestedParselet(int options) {
      this(null, options);
   }

   public NestedParselet(String name, Parselet... toAdd) {
      this(name, 0);
      add(toAdd);
   }

   public NestedParselet(Parselet... toAdd) {
      this(null, 0);
      add(toAdd);
   }

   public NestedParselet(String name, int options, Parselet... toAdd) {
      this(name, options);
      add(toAdd);
   }

   public NestedParselet(int options, Parselet... toAdd)  {
      this(null, options);
      add(toAdd);
   }

   public void set(Parselet... toAdd) {
      clear();
      add(toAdd);
   }

   public void set(String name, Parselet... toAdd) {
      clear();
      setName(name);
      add(toAdd);
   }

   public void set(int ix, Parselet toSet) {
      parselets.set(ix, toSet);
      if (initialized) {
         initialized = false;
         started = false;
         System.out.println("*** warning: setting parselets after initialization");
      }
   }

   public void add(int ix, Parselet toSet) {
      parselets.add(ix, toSet);
      if (initialized) {
         initialized = false;
         started = false;
         System.out.println("*** warning: adding parselets after initialization");
      }
   }

   public void remove(int ix) {
      parselets.remove(ix);
      if (initialized) {
         initialized = false;
         started = false;
         System.out.println("*** warning: removing parselet after initialization");
      }
   }

   protected void clear() {
      parselets.clear();
   }

   public void add(Parselet... toAdd) {
      if (initialized)
      {
         initialized = false;
         started = false;
         System.out.println("*** warning: adding parselets after initialization");
      }
      for (Parselet p: toAdd)
      {
         if (p == null)
            throw new IllegalArgumentException("Null value encountered during initialized");
         parselets.add(p);
      }
   }

   public void init() {
      if (initialized)
         return;
      
      super.init();

      if (trace)
         System.out.println("Initializing: " + this);

      // Make sure we know these are not defined until we're finished intializing
      if (resultComponentClass == null)
          resultComponentClass = UNDEFINED_CLASS;
      if (resultClass == null)
         resultClass = UNDEFINED_CLASS;

      for (Parselet p: parselets) {
         Language l = getLanguage();
         if (l != null)
            p.setLanguage(l); // Propagate the language property down to the child
      }

      initParameterMapping();

      if (genMapping != null && genMapping.length != parselets.size())
         throw new IllegalArgumentException("Number of parselet parameters for the generation info (after the colon) does not match number of child parselets: " + this + " " + name);

      for (Parselet p: parselets)
         p.init();

      // These can no longer be set so if no type is defined, that is ok.
      if (resultClass == UNDEFINED_CLASS)
         resultClass = null;
      if (resultComponentClass == UNDEFINED_CLASS)
         resultComponentClass = null;

      if (skipOnErrorParselet != null) {
         if (!repeat) {
            System.err.println("*** Error in parselet configuration for: " + this + " skipOnErrorParselet only valid when REPEAT flag is used.");
            skipOnErrorParselet = null;
         }

         skipOnErrorParselet.setLanguage(getLanguage());
         skipOnErrorParselet.init();
      }

      if (trace) {
         if (parameterMapping == null)
            System.out.println("Trace: declared result class: " + resultClassName + " - No parameter mapping for: " + this);
         else {
            System.out.println("Trace: declared result class: " + resultClassName + " - Parameter mapping for: " + this);
            for (int i = 0; i < parameterMapping.length; i++) {
               if (i != 0)
                  System.out.print(",");
               else
                  System.out.print("   ");
               if (slotMapping != null && slotMapping[i] != null)
                  System.out.print(slotMapping[i] + "=");
               System.out.print(parameterMapping[i]);
            }
            System.out.println();
         }
      }
   }

   public void setResultClass(Class c) {
      super.setResultClass(c);
      if (inheritSlots != null) {
         for (int i:inheritSlots)
            parselets.get(i).setSemanticValueClass(c);
      }
   }

   public void setSemanticValueClass(Class c) {
      if (c == null)
         throw new IllegalArgumentException("Null class for set semantic value");

      if (resultClassName != null) {
         System.err.println("Parselet has conflicting definition for type: " + resultClassName + " set explicitly and: " + c + " is inherited from another parselet");
         return;
      }

      // We could be inheriting two different types - we need to use the common type between them.
      if (resultClass != null) {
         c = findCommonSuperClass(c, resultClass, -1);
      }

      super.setSemanticValueClass(c);

      // If this guy is inheriting property settings from children, their class needs to be set as well.
      if (inheritSlots != null) {
         for (int i:inheritSlots)
            parselets.get(i).setSemanticValueClass(c);
      }
   }

   protected void initParameterMapping() {
      if (name == null)
         return;

      String genDescriptor = null;
      String paramDescriptor;
      int colonIx = name.indexOf(":");
      if (colonIx != -1) {
         genDescriptor = name.substring(colonIx);
         paramDescriptor = name.substring(0,colonIx);

         int gopenIx = genDescriptor.indexOf("(");
         int gcloseIx = genDescriptor.lastIndexOf(")");
         if (gopenIx == -1 || gcloseIx == -1)
            throw new IllegalArgumentException("Malformed generate descriptor: " + name);
         genDescriptor = genDescriptor.substring(gopenIx+1,gcloseIx);

         boolean sub = false;
         if (genDescriptor.endsWith(",")) {
            genDescriptor = genDescriptor + " ";
            sub = true;
         }
         genMapping = genDescriptor.split(",");
         // trim off char we added to get split to work right
         if (sub) {
            int last = genMapping.length - 1;
            genMapping[last] = genMapping[last].substring(0,genMapping[last].length()-1);
         }
      }
      else
         paramDescriptor = name;

      int openIx = paramDescriptor.indexOf("(");
      int closeIx = paramDescriptor.lastIndexOf(")");
      if (openIx != -1) {
         if (closeIx != -1) {
            String paramStr = paramDescriptor.substring(openIx+1, closeIx);
            String nameStr = paramDescriptor.substring(0,openIx);
            if ((openIx = nameStr.indexOf("[")) != -1) {
               closeIx = nameStr.indexOf("]");
               if (closeIx != -1) {
                  String [] mps = nameStr.substring(openIx+1,closeIx).split(",");
                  chainedPropertyMappings = new Object[2];
                  chainedPropertyMappings[0] = mps[0];
                  chainedPropertyMappings[1] = mps[1];
               }
               if (chainedPropertyMappings.length != 2 || closeIx == -1)
                  throw new IllegalArgumentException("Malformed expression for property mappings: " + name);
            }

            if (paramStr.endsWith(","))
               paramStr = paramStr + " ";

            String [] params = paramStr.split(",");
            parameterMapping = new ParameterMapping[params.length];

            if (parameterMapping.length != parselets.size())
               throw new IllegalArgumentException("Number of parselet parameters does not match number of child parselets: " + this + " " + name);

            for (int i = 0; i < params.length; i++) {
               String p = params[i].trim();
               if (p.equals("")) {
                  parameterMapping[i] = ParameterMapping.SKIP;
               }
               else if (p.equals(".")) {
                  if (propagatedValueIndex == -1)
                     propagatedValueIndex = i;
                  parameterMapping[i] = ParameterMapping.PROPAGATE;
                  setParameterType(ParameterType.PROPAGATE);
               }
               else if (p.equals("''")) {
                  parameterMapping[i] = ParameterMapping.STRING;
                  if (resultClass != null && resultClass != IString.class && resultClass != UNDEFINED_CLASS)
                     throw new IllegalArgumentException("Illegal use of '\'\'' when you have a class or [] specified: " + this);
                  resultClass = IString.class;
                  resultComponentClass = null;
                  setParameterType(ParameterType.STRING);
               }
               else if (p.equals("[]")) {
                  parameterMapping[i] = ParameterMapping.ARRAY;
                  if (resultClass != null && resultClass != ARRAY_LIST_CLASS && resultClass != UNDEFINED_CLASS)
                     throw new IllegalArgumentException("Illegal use of '[]' when you have a class or '' specified: " + this);
                  resultClass = ARRAY_LIST_CLASS;

                  setParameterType(ParameterType.ARRAY);
               }
               else if (p.equals("*")) {
                  if (resultClass == null)
                     throw new IllegalArgumentException("Illegal use of * when you have no result class" + this);

                  parameterMapping[i] = ParameterMapping.INHERIT;
                  if (inheritSlots == null)
                     inheritSlots = new ArrayList<Integer>(1);
                  inheritSlots.add(i);

                  // This is the case where we inherit the type from our child - no type is assigned on the parent
                  if (resultClass == UNDEFINED_CLASS) {
                     System.err.println("Parselet configured with '*' for slot: " + i + " but no class defined");
                  }
                  else if (resultClass != null)
                     parselets.get(i).setSemanticValueClass(resultClass);
               }
               else {
                  if (slotMapping == null)
                     slotMapping = new Object[params.length];
                  slotMapping[i] = p;
                  parameterMapping[i] = ParameterMapping.NAMED_SLOT;
               }
            }

            // If we have no result class ourselves, do have a parameter mapping and
            // do not propagate a child's value or define an array or string, this mapping
            // can be processed by the parentNode definition through the "*" operator.  We
            // don't process anything at this level.
            if (resultClassName == null && parameterType == ParameterType.DEFAULT)
               setParameterType(ParameterType.INHERIT);
         }
         else throw new IllegalArgumentException("Mismatching parens in parameter mapping: " + this);
      }
   }

   protected void resolveParameterMapping() {
      // Make sure these get cached here so we avoid the recursive lookups and flush out
      // any errors that might arise in trying to compute them.
      if (resultClass == null)
         resultClass = getSemanticValueClass();
      if (resultComponentClass == null)
         resultComponentClass = getSemanticValueComponentClass();

      if (slotMapping != null)
      {
         if (parameterType != ParameterType.INHERIT)
         {
            if (resultClass == null)
               System.out.println("*** No result type defined for: " + this);

            if (resultClass == ARRAY_LIST_CLASS && resultComponentClass == null)
               System.out.println("*** resultComponentClass is null for: " + this);
         }

         Class slotClass = getSlotClass();

         if (slotClass != null)
         {
            for (int i = 0; i < slotMapping.length; i++)
            {
               assignSlotMapping(i, slotClass);
            }
         }
      }
      if (chainedPropertyMappings != null)
      {
         if (!repeat)
            throw new IllegalArgumentException("Use of Name[chainedProperty](..) syntax on non-repeating sequence: " + this);
         Class rc = getSemanticValueClass();
         String chainedPropertyName = null;
         // TODO: need to type check this guy
         try
         {
            for (int i = 0; i < 2; i++)
            {
               chainedPropertyName = (String) chainedPropertyMappings[i];
               chainedPropertyMappings[i] = TypeUtil.getPropertyMapping(rc, chainedPropertyName, null, null);
               if (chainedPropertyMappings[i] == null)
                  System.err.println("*** Error: Cannot find set method or public field for slot " + chainedPropertyName + " on class: " + rc + " for definition: " + this);
            }
         }
         catch (IllegalArgumentException iae)
         {
            System.err.println("*** Error: " + iae + " for slot " + chainedPropertyName + " on class: " + rc + " for definition: " + this);
         }
      }
   }


   Class getSlotClass() {
      return getSemanticValueIsArray() ? getSemanticValueComponentClass() : getSemanticValueSlotClass();
   }

   // TODO: replace with TypeUtil version
   Class findCommonSuperClass(Class c1, Class c2, int childIx) {
      Class o1 = c1;
      Class o2 = c2;

      if (o1 == null && o2 != null)
         return o2;
      if (o2 == null && o1 != null)
         return o1;

      boolean isInterface = o1.isInterface();

      while (o1 != null && o2 != null && !o1.isAssignableFrom(o2)) {
         o1 = o1.getSuperclass();
         if (isInterface && o1 == null) {
            o1 = Object.class;
            isInterface = false;
         }
      }

      isInterface = o2.isInterface();

      while (c1 != null && o2 != null && !o2.isAssignableFrom(c1)) {
         o2 = o2.getSuperclass();
         if (isInterface && o2 == null) {
            o2 = Object.class;
            isInterface = false;
         }
      }


      Class res =  o1 != null && o2 != null && o1.isAssignableFrom(o2) ? o2 : o1;

      if (trace && o1 != res)
         System.out.println("Trace: changing parselet type to: " + res + " from: " + o1 +
               (childIx == -1 ? " set by parent to: " : " child[" + childIx + "] = " + o2));


      // Grammars can use "Object" such as when we have a String or a strongly typed class
      if (res == UNDEFINED_CLASS /* || res == Object.class */)
         System.err.println("*** Error: Invalid result type for parselet: " + this + " types: " + c1 + " and: " + c2 + " have no superclass in common.");

      return res;
   }

   protected void setParameterType(ParameterType type) {
      if (type != parameterType && parameterType != ParameterType.DEFAULT)
         throw new IllegalArgumentException("Parselet node: " + this + " has a conflicting definition of " + type + " and " + parameterType);
      parameterType = type;
   }


   public void start() {
      if (started)
         return;

      super.start();

      for (Parselet p: parselets)
         p.start();

      resolveParameterMapping();

      if (skipOnErrorParselet != null)
         skipOnErrorParselet.start();
   }

   public void stop() {
      if (!started)
         return;
      started = false;
      initialized = false;
      for (Parselet p: parselets)
         p.stop();
   }


   private void assignSlotMapping(int i, Class slotClass) {
      if (slotMapping[i] == null || !(slotMapping[i] instanceof String))
          return;

      String slotName = (String) slotMapping[i];
      Class valueClass, componentClass;
      Parselet childParselet = parselets.get(i);
      valueClass = childParselet.getSemanticValueClass();
      componentClass = childParselet.getSemanticValueComponentClass();

      // Value not defined yet - we'll have to map this column later
      if (valueClass == null) {
         System.err.println("*** No name to compute semantic value type for child parselet: " + childParselet + " the slot with position: " + i + " for: " + this);
         return; // Cannot assign
      }

      if (componentClass == ARRAY_LIST_CLASS)
           System.err.println("*** Error: invalid component class of type list");
      try {
         slotMapping[i] = TypeUtil.getPropertyMapping(slotClass, slotName, valueClass, componentClass);
         if (slotMapping[i] == null)
            System.err.println("*** Error: Cannot find set method or public field for property '" + slotName + "' on class: " + slotClass + " for definition: " + this);
      }
      catch (IllegalArgumentException iae)
      {
         System.err.println("*** Error: " + iae.getMessage() + " for property '" + slotName + "' on class: " + slotClass + " for definition: " + this + " Child parselet[" + i + "]:"  + childParselet);
      }
   }

   private int getSlotIndex(Object propertySelector) {
      if (slotMapping == null)
         return -1;

      String propertyName = RTypeUtil.getPropertyNameFromSelector(propertySelector);
      for (int i = 0; i < slotMapping.length; i++) {
         String selectorProperty = RTypeUtil.getPropertyNameFromSelector(slotMapping[i]);
         if (selectorProperty != null && propertyName.equals(selectorProperty))
            return i;
      }

      return -1;
   }

   /**
    * During generation, to perform an incremental update, we want to find the node which handles a property
    * change - working down the parse tree.  If there is a "*" in a slot, we send that change down that path.
    */
   protected boolean handlesProperty(Object selector) {
      int ix = getSlotIndex(selector);
      if (ix != -1)
         return true;
      if (inheritSlots != null) {
         int sz = inheritSlots.size();
         for (int i = 0; i < sz; i++) {
            NestedParselet child = (NestedParselet) parselets.get(inheritSlots.get(i));
            if (child.handlesProperty(selector))
               return true;
         }
      }
      return false;
   }

   /**
    * For a given parse node which we generated (or null).  Returns false if the parselet is unable to handle
    * the change.  For a choice, this might be a signal to try another alternative.
    */
   public boolean semanticPropertyChanged(Object parentParseNode, Object semanticValue, Object selector, Object value) {
      int slotIx = getSlotIndex(selector);

      // Not our immediate property - we propagate it down
      if (slotIx == -1) {
         int chainIx;
         if (parameterType == ParameterType.PROPAGATE && propagatedValueIndex != -1) {
            ParentParseNode ppnode = (ParentParseNode) parentParseNode;

            // Already invalidated
            if (ppnode.children == null)
               return true;

            /*
             * In some cases, the type might either be propagated or inherited.  In these cases, we cannot regen
             * incrementally.
             */
            if (ppnode.children.size() <= propagatedValueIndex)
               return regenerateIfTracking(ppnode, false);
            Parselet childParselet = parselets.get(propagatedValueIndex);
            if (childParselet instanceof NestedParselet) {
               Object childParseNode = ppnode.children.get(propagatedValueIndex);
               // At least one case where this happens is for IdentifierExpression(identifiers,.) when we
               // add "arguments" to the expression. 
               if (childParseNode == null)
                   return regenerateIfTracking(ppnode, false);

               // Some parselets do not generate a parse node - rather they just propagate up the nested parselets
               // value.  We'll skip passed these parselets during the generation.
               if (childParseNode instanceof IParseNode) {
                  childParselet = ((IParseNode) childParseNode).getParselet();
               }
               // This parsenode was optimized into a String so we can't do any incremental updates
               else if (childParseNode instanceof String)
                  return regenerateIfTracking(ppnode, false);
               return ((NestedParselet) childParselet).semanticPropertyChanged(childParseNode, semanticValue, selector, value);
            }
            else
               throw new IllegalArgumentException("Unable to update semantic property value");
         }
         // If we have an "*" operators, see if we can propagate the change to a child.
         else if (inheritSlots != null)  {
            ParentParseNode ppnode = (ParentParseNode) parentParseNode;
            for (int i = 0; i < inheritSlots.size(); i++) {
               int slot = inheritSlots.get(i);
               NestedParselet child = (NestedParselet) parselets.get(slot);
               // This checks the "*"'s recursively down the tree to see which parselet produces this property
               if (child.handlesProperty(selector)) {
                  Object childParseNode = ppnode.children == null || slot > ppnode.children.size() ? null : ppnode.children.get(slot);
                  if (childParseNode == null)
                     return regenerateIfTracking(ppnode, false);
                  return child.semanticPropertyChanged(childParseNode, semanticValue, selector, value);
               }
            }
            return false;
         }
         else if ((chainIx = getChainedPropertyIndex(selector)) != -1) {
            ParentParseNode ppnode = (ParentParseNode) parentParseNode;
            if (chainIx == 0) {
               ISemanticNode isn = (ISemanticNode) semanticValue;
               ISemanticNode parent = isn.getParentNode();
               ParentParseNode parentParentParseNode = (ParentParseNode) parent.getParseNode();
               return parentParentParseNode.parselet.regenerateIfTracking(parentParentParseNode, false);
            }
            else
               return regenerateIfTracking(ppnode, false);
         }
         else // We are given a property change for a node we don't know about - return an error so we try the next choice
            return false;
      }
      else {
         Object newChildParseNode;
         Parselet childParselet = parselets.get(slotIx);

         /*
         Object oldValue = TypeUtil.getPropertyValue(this, selector);
         if (oldValue == value || (oldValue != null && oldValue.equals(value)))
            return;

         if (oldValue instanceof ISemanticNode)
         {
            ISemanticNode oldSemanticNode = (ISemanticNode) oldValue;
            oldChildParseNode = oldSemanticNode.getParseNode();
         }
         else
            oldChildParseNode = oldValue;
         */

         if (value instanceof ISemanticNode) {
            if (language.trackChanges || !(parentParseNode instanceof ParentParseNode)) {
               Object obj = childParselet.generate(getLanguage().newGenerateContext(this, value), value);
               if (obj instanceof IParseNode)
                  newChildParseNode = (IParseNode) obj;
               else // GenerateError...
                  return false;
            }
            else {
               ParentParseNode ppnode = (ParentParseNode) parentParseNode;
               // If the parent node has already been invalidated, we should not need to create the children just to maintain
               // a ref point, since the parent will get entirely regenerated anyway.
               if (ppnode.children == null) {
                  return true;
               }
               invalidateChildParseNode(ppnode, childParselet, value, slotIx, slotIx <= ppnode.children.size() ? ChangeType.REPLACE : ChangeType.ADD);
               return true;
            }
         }
         else {
            // Used to just use "value" here for the child parse node but that omits spacing
            newChildParseNode = value == null ? null : childParselet.generate(getLanguage().newGenerateContext(this, value), value);
            if (newChildParseNode instanceof GenerateError) {
               System.err.println("*** value could not generate");
               return false;
            }
         }

         ParentParseNode ppnode = (ParentParseNode) parentParseNode;

         // Right now, we are just replacing the value.  We could potentially try to merge the values to
         // preserve comments in the whitespace or something like that.  Possibly this should be a hook
         // on the parent or child parselet so it gets to merge updated parse nodes together?
         if (ppnode.children == null || slotIx >= ppnode.children.size())
            ppnode.addGeneratedNode(newChildParseNode, childParselet);
         else
            ppnode.children.set(slotIx, newChildParseNode);
         //parentParseNode.replace(oldChildParseNode, newChildParseNode, childParselet, slotIx);

         return true;
      }
   }

   private int getChainedPropertyIndex(Object selector) {
      if (chainedPropertyMappings != null) {
         for (int i = 0; i < chainedPropertyMappings.length; i++)
            if (TypeUtil.equalPropertySelectors(chainedPropertyMappings[i], selector))
               return i;
      }
      return -1;
   }

   public boolean arrayElementChanged(Object parseNode, List semanticValue, int index, Object element, ChangeType changeType) {
      return arrayElementChanged(parseNode, semanticValue, 0, index, element, changeType);
   }

   /**
    * We are propagating an array value change through the hierarchy.  If we can handle and update the parse tree
    * we return true otherwise, we return false.  We could regenerate the entire tree at this point but that would
    * mean it was impossible to insert into a list incrementally.
    */
   public boolean arrayElementChanged(Object parseNode, List semanticValue, int startIndex, int index, Object element, ChangeType changeType) {
      if (!(parseNode instanceof ParentParseNode)) {
         System.err.println("Error: array element changed on non parent parse node!");
         return false;
      }
      ParentParseNode parentParseNode = (ParentParseNode) parseNode;
      if (parameterType == ParameterType.PROPAGATE && propagatedValueIndex != -1) {
         Parselet childParselet = parselets.get(propagatedValueIndex);
         if (parentParseNode == null)
             return false;
         // Already invalidated
         if (parentParseNode.children == null)
            return !language.trackChanges;
         if (childParselet instanceof NestedParselet)
            // TODO: will this parse node be null?  If so we can just re-generate right?
            return ((NestedParselet) childParselet).arrayElementChanged(
                    parentParseNode.children.get(propagatedValueIndex), semanticValue, startIndex, index, element, changeType);
      }
      else if (parameterType == ParameterType.ARRAY) {
         if (!generateIncremental()) {
            if (changeType == ChangeType.REMOVE)
               return false;
            if (language.trackChanges)
               return regenerate(parentParseNode, false);
            else {
               SemanticNodeList snl = (SemanticNodeList) semanticValue;
               snl.parseNodeInvalid = true;
               return true;
            }
         }

         int currentValueIndex = startIndex;
         boolean processedAllElements = false;
         boolean elementProcessed = false;
         GenerateError lastErr = null;
         do {
            // If we processed this guy on the last iteration, make sure to look for the next one on this
            if (elementProcessed) {
               currentValueIndex++;
               elementProcessed = false;
            }

            int numParselets = parselets.size();
            for (int i = 0; !elementProcessed && i < numParselets; i++) {
               Parselet childParselet = parselets.get(i);

               switch (parameterMapping[i]) {
                  case ARRAY:
                     boolean childIsArray = childParselet.getSemanticValueIsArray();

                     if (!getSemanticValueIsArray()) {
                        // Child is a scalar...
                        if (!childParselet.getSemanticValueIsArray() && !List.class.isAssignableFrom(childParselet.getSemanticValueClass())) {
                           if (currentValueIndex == index) {
                              if (language.trackChanges || !(element instanceof ISemanticNode)) {
                                 GenerateError err = regenerateChild(parentParseNode, childParselet, element, i, changeType);
                                 if (err != null) {
                                    System.err.println(err);
                                    return false;
                                 }
                              }
                              else {
                                 invalidateChildParseNode(parentParseNode, childParselet, element, i, changeType);
                              }
                              return true;
                           }
                           else
                              currentValueIndex++;
                        }
                        // Assume the remainder of the list is for this guy
                        else {
                           List l = currentValueIndex == 0 ? semanticValue : semanticValue.subList(currentValueIndex,semanticValue.size());

                           Object childParseNode = parentParseNode.children == null ? null : parentParseNode.children.get(i);
                           if (childParseNode == null) {
                              // Once we are already invalid, no need to keep updating... do we need to do more validation here though to make sure this node matches?
                              if (semanticValue instanceof SemanticNodeList && ((SemanticNodeList) semanticValue).parseNodeInvalid)
                                 return true;
                              if (language.trackChanges || !(element instanceof ISemanticNode)) {
                                 GenerateError err = regenerateChild(parentParseNode, childParselet, l, i, changeType);
                                 if (err != null) {
                                    System.err.println(err);
                                    return false;
                                 }
                                 return true;
                              }
                              else {
                                 childParseNode = childParselet.newGeneratedParseNode(semanticValue);
                                 updateElement(parentParseNode, childParseNode, semanticValue, i, changeType);
                              }
                           }
                           if (((NestedParselet) childParselet).arrayElementChanged(
                                 childParseNode, l, index-currentValueIndex, element, changeType))
                              return true;

                           processedAllElements = true;
                        }
                     }
                     else {
                        if (childParselet instanceof NestedParselet) {
                           // We are an array and our value is an array.  If there is more than one child array
                           // we can't figure out how to split the parent's array into two child arrays.  Right now,
                           // we just deal with the case where there is a single array.
                           if (childIsArray) {
                              int numElementsConsumed = startIndex;
                              processedAllElements = true;

                              ArrayList<Object> children = parentParseNode.children;
                              int childSz = children.size();
                              /** Skip over the semantic value elements leading up to the one that was changed */
                              int skippedParseNodes = 0;
                              int v;
                              for (v = numElementsConsumed; v < index; v++) {
                                 Object currentValue = semanticValue.get(v);

                                 while (children != null && childSz < skippedParseNodes) {
                                    Object childNode = children == null ? null : children.get(skippedParseNodes);
                                    if (childNode instanceof IParseNode && ((IParseNode) childNode).refersToSemanticValue(currentValue)) {
                                       skippedParseNodes++;
                                    }
                                    else
                                       break;
                                 }
                              }
                              numElementsConsumed = v;

                              /** Now find the next parse node which corresponds to this parselet and send the change to that guy */
                              int currentParseNode;

                              int numChildren = children == null ? 0 : childSz;
                              Object currentValue = semanticValue.get(index);
                              boolean wrongParselet = false;
                              for (currentParseNode = skippedParseNodes; currentParseNode < numChildren; currentParseNode++) {
                                 Object childNode = children.get(currentParseNode);
                                 if (childNode instanceof IParseNode) {
                                    IParseNode childParseNode = (IParseNode) childNode;
                                    if (childParseNode.getParselet() == childParselet)
                                       break;
                                    /* Found the replaced value before we found a parselet that produced it so need to
                                     * bail */
                                    if (childParseNode.refersToSemanticValue(currentValue)) {
                                       wrongParselet = true;
                                       break;
                                    }
                                 }
                              }
                              if (currentParseNode == numChildren)
                                 System.err.println("**** did not find child to parse");

                              if (numElementsConsumed <= index && !wrongParselet) {
                                 if (((NestedParselet) childParselet).arrayElementChanged(children.get(currentParseNode), semanticValue, numElementsConsumed, index, element, changeType))
                                    return true;
                              }
                           }
                           // The child is a scalar
                           else {
                              if (currentValueIndex == index) {
                                 Object elemValue = semanticValue.get(currentValueIndex);
                                 if (childParselet.dataTypeMatches(elemValue)) {
                                    if (language.trackChanges || !(elemValue instanceof ISemanticNode)) {
                                       lastErr = regenerateElement(parentParseNode, childParselet, elemValue, currentValueIndex-startIndex, changeType);
                                       if (lastErr == null)
                                          elementProcessed = true;
                                    }
                                    else {
                                       invalidateChildElement(parentParseNode, childParselet, semanticValue, elemValue, currentValueIndex-startIndex, changeType);
                                       elementProcessed = true;
                                    }
                                 }

                                 /*
                                  * Need to regenerate this whole sequence, not just a child parselet (i.e. imports)
                                 if (regenerateChild(parentParseNode, childParselet, semanticValue.get(currentValueIndex), currentValueIndex, changeType)) {
                                    elementProcessed = true;
                                 }
                                 */
                              }
                              else {
                                 elementProcessed = true;
                              }
                           }
                        }
                     }
                     break;
               }

            }
         } while (!processedAllElements && currentValueIndex < index);

         boolean result = processedAllElements || (elementProcessed && currentValueIndex == index);
         // Returning false here is usually an error but from some array cases, we have to see which parselet in a list
         // of children can handle the change.  In that case, it is not an error.
         //if (!result)
         //   System.out.println(lastErr);
         return result;
      }
      // A regular repeat node - we produced an array - one element per parselet definition.
      else if (getSemanticValueIsArray()) {
         int nodeIndex = index * parselets.size();
         if (language.trackChanges || !(element instanceof ISemanticNode)) {

            if (changeType == ChangeType.ADD) {
               // If this happens, it is because the other elements in the array were not generated properly... maybe just
               // re-generate the entire array if this case?
               if (parentParseNode.children.size() < nodeIndex)
                   throw new IllegalArgumentException("Old parse nodes missing required elements to do incremental array regeneration");

               Object genResult = generateElement(getLanguage().newGenerateContext(this, element), element, true);

               if (genResult instanceof GenerateError)
                  return false;

               // Insert these parse nodes into the list at the position.
               parentParseNode.copyGeneratedAt(nodeIndex, (ParentParseNode) genResult);
               return true;
            }
            else {
               System.err.println("*** Unimplemented change type: " + changeType + " for repeat node parselet change: " + this);
            }
         }
         else {
            invalidateElement(parentParseNode, element, nodeIndex, changeType);
            return true;
         }
      }
      else
         throw new IllegalArgumentException("arrayElementChanged for node which is not an array");

      return false;
   }

   public boolean getSemanticValueIsArray() {
      return repeat && chainedPropertyMappings == null;
   }

   /**
    * Returns true if we must regenerate the entire parse node for this parselet.  Specifically, if
    * we have one scalar and one array element, when we insert a new array element we have to start from scratch.
    */
   private boolean generateIncremental() {
      int numParselets = parselets.size();
      boolean first = true;
      boolean isArray = false;

      for (int i = 0; i < numParselets; i++) {
         if (parameterMapping[i] == ParameterMapping.SKIP)
            continue;

         if (first) {
            isArray = parselets.get(i).getSemanticValueIsArray();
            first = false;
         }
         else if (isArray != parselets.get(i).getSemanticValueIsArray())
            return false;
      }
      return true;
   }

   public boolean regenerateIfTracking(ParentParseNode pnode, boolean finalGen) {
      if (language.trackChanges) {
         return regenerate(pnode, finalGen);
      }
      else {
         Object sv = pnode.getSemanticValue();
         if (sv instanceof ISemanticNode) {
            ((ISemanticNode) sv).invalidateParseNode();

            // Should always have a parse node right?
            if (((ISemanticNode) sv).getParseNode() == null)
               System.err.println("*** Error: should have a parse node to invalidate!");
         }
         return true;
      }
   }

   public boolean regenerate(ParentParseNode pnode, boolean finalGen) {
      Object sv = pnode.getSemanticValue();
      Object genRes;
      GenerateContext ctx = getLanguage().newGenerateContext(this, sv);
      ctx.finalGeneration = finalGen;
      int ix;
      PerfMon.start("regenerate");
      try {
         IParseNode resNode = pnode;

         if (getSemanticValueIsArray() && !(sv instanceof List)) {
            genRes = generateElement(ctx, sv, false);
            Object origParseNode = sv instanceof ISemanticNode ? ((ISemanticNode) sv).getParseNode() : sv;
            ix = pnode.children == null ? -1 : pnode.children.indexOf(origParseNode);
            if (genRes instanceof GenerateError || genRes instanceof PartialArrayResult)
               return false;
            ParentParseNode newParseNode = (ParentParseNode) genRes;
            // With a repeat Sequence like ImportDeclaration, the sequence will be stored both with the list and the elements.  We need to differentiate which one kind of brute force.
            if (pnode.children == null)
               pnode.children = new ArrayList<Object>(newParseNode.children.size());

            if (ix == -1)
               pnode.children.add(genRes);
            else
               pnode.children.set(ix, genRes);
         }
         else {
            genRes = generate(ctx, sv);
            if (genRes instanceof GenerateError || genRes instanceof PartialArrayResult)
                return false;

            // TODO: pnode.children here may have non-semantic parse-nodes like spacing, comments which should be merged
            // back into the genRes children.  So instead of just clearing the children, we could walk down the old and new
            // tree's and take from the old anything which did not have semantic content.

            if (pnode.children != null)
               pnode.children.clear();

            // The parselet optimized it's generation so it returns a String.  We need to reuse this parse node to hold
            // the string because it may be wired into all of the parent parse nodes.
            if (genRes instanceof String) {
               if (pnode.children == null)
                  pnode.children = new ArrayList<Object>(1);
               pnode.children.add(genRes);
            }
            else {
               ParentParseNode newParseNode = (ParentParseNode) genRes;
               if (newParseNode != null && newParseNode.children != null) {
                  // With a repeat Sequence like ImportDeclaration, the sequence will be stored both with the list and the elements.  We need to differentiate which one kind of brute force.
                  if (pnode.children == null)
                     pnode.children = new ArrayList<Object>(newParseNode.children.size());
                  pnode.children.addAll(newParseNode.children);
               }
            }
         }

         // Make sure semantic value points to the original parse node
         if (sv instanceof ISemanticNode)
            ((ISemanticNode) sv).setParseNode(resNode);
      }
      finally {
         PerfMon.end("regenerate");
      }

      return true;
   }

   protected void invalidateElement(ParentParseNode parentParseNode, Object element, int childParseNodeIx, ChangeType changeType) {
      if (!(element instanceof ISemanticNode))
         return;
      ISemanticNode node = (ISemanticNode) element;
      node.invalidateParseNode();
      Object currentParseNode = node.getParseNode();
      if (currentParseNode != null) {
         // This node could have been moved from one place to another.  In that case, we can't use the old node as the parselet is wrong and that will
         // mess up the regeneration.
         if (currentParseNode instanceof IParseNode) {
            if (((IParseNode) currentParseNode).getParselet() != this)
               currentParseNode = null;
         }
      }
      if (currentParseNode == null) {
         currentParseNode = newGeneratedParseNode(node);
      }
      updateElement(parentParseNode, currentParseNode, element, childParseNodeIx, changeType);
   }

   protected void invalidateChildParseNode(ParentParseNode parentParseNode, Parselet childParselet, Object element, int childParseNodeIx, ChangeType changeType) {
      if (!(element instanceof ISemanticNode))
         return;
      ISemanticNode node = (ISemanticNode) element;
      node.invalidateParseNode();
      Object childParseNode = node.getParseNode();
      if (childParseNode != null) {
         // This node could have been moved from one place to another.  In that case, we can't use the old node as the parselet is wrong and that will
         // mess up the regeneration.
         if (childParseNode instanceof IParseNode) {
            //if (!childParselet.producesParselet(((IParseNode) childParseNode).getParselet()))
            if (((IParseNode) childParseNode).getParselet() != childParselet)
               childParseNode = null;
         }
      }
      if (childParseNode == null) {
         childParseNode = childParselet.newGeneratedParseNode(node);
      }
      updateElement(parentParseNode, childParseNode,  element, childParseNodeIx, changeType);
   }

   protected void updateElement(ParentParseNode pnode, Object childParseNode, Object element, int childParseNodeIx, ChangeType changeType) {
      if (changeType == ChangeType.REPLACE) {
         if (pnode.children == null)
            pnode.children = new ArrayList<Object>();

         while (childParseNodeIx >= pnode.children.size())
            pnode.children.add(null);

         // TODO: do we need to worry about dangling ; colons here as in the remove case below?  We might replace the wrong guy?
         pnode.children.set(childParseNodeIx, childParseNode);
      }
      else if (changeType == ChangeType.ADD) {
         if (pnode.children == null)
            pnode.children = new ArrayList<Object>();
         pnode.children.add(Math.min(childParseNodeIx,pnode.children.size()), childParseNode);
      }
      else if (changeType == ChangeType.REMOVE) {
         if (pnode.children == null) { // Parsenode is already invalid
            return;
         }
         /*
         * For <classBodyDeclarations> if there is an empty ; it gets put into the children array here but has no semantic value
         * For removes, we need to be careful to replace the right guy - i.e. skip any of these guys.  also validate the semantic value
         * matches.
         */
         if (childParseNodeIx < pnode.children.size()) {
            boolean found = false;
            do {
               Object currValue = pnode.children.get(childParseNodeIx);
               Object sv = null;
               if (!(currValue instanceof IParseNode) ||
                       (sv = ((IParseNode) currValue).getSemanticValue()) == element ||
                       // Covers the List<String> case where we do this conversion automatically (I think) any others?
                       ((sv instanceof PString) && DynUtil.equalObjects(sv, element))) {
                  found = true;
                  break;
               }
               childParseNodeIx++;
            }
            while (childParseNodeIx < pnode.children.size());

            if (found)
               pnode.children.remove(childParseNodeIx);
            else {
               // This may happen when we are updating a parse node in a remove that's already not valid.
               //System.err.println("*** Did not find a node to remove in updateElement");
            }
         }
         else {
            //System.err.println("*** Child parse nodes too small in updateElement");
         }
      }
   }

   protected GenerateError regenerateChild(ParentParseNode pnode, Parselet childParselet, Object element, int childParseNodeIx,
                                           ChangeType changeType) {

      Object childResult = changeType == ChangeType.REMOVE ? null :
                           childParselet.generate(getLanguage().newGenerateContext(this, element), element);
      if (!(childResult instanceof GenerateError)) {
         if (changeType == ChangeType.REPLACE) {
            if (childParseNodeIx >= pnode.children.size())
               System.out.println("*** Error incorrect parse node index for regenerate child!");
            // TODO: do we need to worry about dangling ; colons here as in the remove case below?  We might replace the wrong guy?
            pnode.children.set(childParseNodeIx, childResult);
         }
         else if (changeType == ChangeType.ADD) {
            if (pnode.children == null)
               pnode.children = new ArrayList<Object>();
            pnode.children.add(Math.min(childParseNodeIx,pnode.children.size()), childResult);
         }
         else if (changeType == ChangeType.REMOVE) {
            /*
             * For <classBodyDeclarations> if there is an empty ; it gets put into the children array here but has no semantic value
             * For removes, we need to be careful to replace the right guy - i.e. skip any of these guys.  also validate the semantic value
             * matches.
             */
            if (pnode.children != null && childParseNodeIx < pnode.children.size()) {
               boolean found = false;
               do {
                  Object currValue = pnode.children.get(childParseNodeIx);
                  Object sv = null;
                  if (!(currValue instanceof IParseNode) ||
                      (sv = ((IParseNode) currValue).getSemanticValue()) == element ||
                       // Covers the List<String> case where we do this conversion automatically (I think) any others?
                      ((sv instanceof PString) && DynUtil.equalObjects(sv, element))) {
                     found = true;
                     break;
                  }
                  childParseNodeIx++;
               }
               while (childParseNodeIx < pnode.children.size());

               if (found)
                  pnode.children.remove(childParseNodeIx);
               else {
                  return new GenerateError("No parse node to remove for remove event");
               }
            }
            else {
               return new GenerateError("Parse node is out of whack with child index!");
            }
         }
         return null;
      }
      return (GenerateError) childResult;
   }

   protected abstract GenerateError regenerateElement(ParentParseNode pnode, Parselet childParselet, Object element, int parseNodeIx, ChangeType changeType);

   /*
   protected void invalidateChildElements(ParentParseNode pnode, Parselet childParselet, List parentValue, List elements, int parseNodeIx, ChangeType changeType) {
      for (int i = 0; i < elements.size(); i++) {
         // And since we are regenerating the entire element, we'll invalidate the entire sequence
         // TODO: change this so it actually invalidates the child, inserting it into the parent's parsenode if necessary.
         ISemanticNode sv = (ISemanticNode) elements.get(i);
         sv.invalidateParseNode();
         Object childParseNode = sv.getParseNode();
         if (childParseNode != null) {
            // This node could have been moved from one place to another.  In that case, we can't use the old node as the parselet is wrong and that will
            // mess up the regeneration.
            if (childParseNode instanceof IParseNode) {
               if (((IParseNode) childParseNode).getParselet() != this)
                  childParseNode = null;
            }
         }
         if (childParseNode == null) {
            childParseNode = childParselet.newGeneratedParseNode(parentValue);
         }
         updateElement(pnode, childParseNode, sv, parseNodeIx, changeType);
      }
   }
   */

   protected void invalidateChildElement(ParentParseNode pnode, Parselet childParselet, Object semanticValue, Object element, int parseNodeIx, ChangeType changeType) {
      ISemanticNode sv = (ISemanticNode) element;
      Object childParseNode = sv.getParseNode();

      if (childParseNode != null) {
         // This node could have been moved from one place to another.  In that case, we can't use the old node as the parselet is wrong and that will
         // mess up the regeneration.
         if (childParseNode instanceof IParseNode) {
            if (!childParselet.producesParselet(((IParseNode) childParseNode).getParselet()))
            //if (((IParseNode) childParseNode).getParselet() != childParselet)
               childParseNode = null;
         }
      }
      if (childParseNode == null) {
         childParseNode = childParselet.newGeneratedParseNode(sv);
         sv.invalidateParseNode();
      }
      updateElement(pnode, childParseNode,  element, parseNodeIx, changeType);
   }

   public abstract String getSeparatorSymbol();

   public String toHeaderString(Map<Parselet,Integer> visited) {
      if (isNamed())
         return super.toHeaderString(visited);

      return toStringInternal(visited);
   }

   boolean isNamed() {
      if (name == null)
         return false;
      if (name.indexOf("(") == 0)
         return false;
      return true;
   }

   public String toString() {
      if (isNamed())
         return getDebugName();
      
      String body = toStringInternal(new IdentityHashMap<Parselet,Integer>());
         //return name + " = " + body;
      return body;
   }

   protected String toStringInternal(Map<Parselet,Integer> visited) {
      Integer id = visited.get(this); 
      if (id != null) {
         if (name != null)
            return getDebugName();
         return "recursiveRef" + "#" + id.intValue();
      }

      visited.put(this, new Integer(visited.size() + 1));

      StringBuffer sb = new StringBuffer();

      if (isNamed()) {
         sb.append(getDebugName());
         sb.append("=");
      }
      
      sb.append(getPrefixSymbol());
      /*
       * Displays the parameters to the parselet in a header format which can sometimes be useful.  But since
       * this method gets used to display syntax errors for users, we are taking this out for now.
       */
      sb.append("(");
      boolean first = true;
      for (Parselet p: parselets) {
         if (!first)
            sb.append(getSeparatorSymbol());
         first = false;
         sb.append(p.toHeaderString(visited));
      }
      sb.append(")");
      sb.append(getSuffixSymbol());

      return sb.toString();
   }

   public String getDebugName() {
      // Use this flag as a signal as to whether or not to print the parameters.   These are confusing in user visible syntax errors
      if (!GenerateContext.debugError) {
         int ix = name.indexOf("(");
         if (ix != -1)
            return name.substring(0,ix);
      }
      return name;
   }

   public Object createInstance(Parser parser, Class theClass) {
      // When instructed, just populate an existing instance instead of creating a new one.  We do the type check here in case there
      // are nested types that need to be created on the same parser.  Perhaps the test should be turned off once we've passed the top
      // level value?   Right now this is just used for very simple grammars - the pattern type things.
      Object populateInst = parser.populateInst;
      if (populateInst != null) {
         if (resultDynType != null) {
            if (populateInst instanceof IDynObject && resultDynType.isAssignableFrom((ITypeDeclaration) ((IDynObject) populateInst).getDynType(), false))
               return populateInst;
         }
         else if (theClass.isAssignableFrom(populateInst.getClass()))
            return parser.populateInst;
      }
      return RTypeUtil.createInstance(theClass);
   }

   public void setSemanticValue(ParentParseNode parent, Object node, int index, boolean skipSemanticValue, Parser parser, boolean replaceValue) {
      if (trace && parser.enablePartialValues)
         System.out.println("*** setting semantic value of traced element");

      if (!skipSemanticValue && !parser.matchOnly) {
         if (resultClass != null && !getSkip()) {
            if (parent.value == null) {
               // If we are propagating a value and have a result class specified, we wait to create this until
               // we see if the propagated value is null or not.  It only gets created if it is null.
               if (propagatedValueIndex == -1)
                  parent.setSemanticValue(createInstance(parser, getSemanticValueSlotClass()));
            }

            if (index == 0 && getSemanticValueIsArray()) {
               Object newElement = createInstance(parser, resultClass);
               ((SemanticNodeList) parent.value).add(newElement);
            }
         }

         if (parameterMapping != null) {
            switch (parameterMapping[index]) {
               case SKIP:
                   break;

               case PROPAGATE:
                  Object sval = ParseUtil.nodeToSemanticValue(node);

                  // This is the case where we have both a resultClass and are propagating a child value up.
                  // If the child value is null, we create an instance of the class specified by the parent.
                  if (sval == null && resultClassName != null)
                     sval = createInstance(parser, getSemanticValueClass());
                  
                  parent.setSemanticValue(sval);

                  // We are propagating the node's value - override the node we store for 
                  // this semantic node's parse node.
                  if (parent.value instanceof ISemanticNode && node instanceof ISemanticNode) {
                     ((ISemanticNode) node).setParentNode((ISemanticNode)parent.value);
                  }
                  break;

               case STRING:
                  /* Concatenate the node to the current string - if we have StringTokens, try to keep
                     the string tokens and just increment the length to avoid string copying.
                   */
                  if (node instanceof IParseNode) {
                     Object sv = ((IParseNode) node).getSemanticValue();
                     if (sv != null) {
                        if (parent.value == null)
                           parent.value = sv;
                        else if (parent.value instanceof StringToken && sv instanceof StringToken)
                           parent.value = StringToken.concatTokens((StringToken) parent.value, (StringToken) sv);
                        else
                           parent.value = parent.value.toString() + sv.toString();
                     }
                  }
                  else if (parent.value == null)
                     parent.value = node;
                  else if (node instanceof StringToken) {
                     if (parent.value instanceof StringToken) {
                        parent.value = StringToken.concatTokens((StringToken) parent.value, (StringToken) node);
                        //((StringToken)parentNode.value).len += ((StringToken) node).len;
                     }
                     else {
                        parent.value = parent.value.toString() + node;
                     }
                  }
                  else if (node instanceof String) {
                     parent.value = parent.value.toString() + node;
                  }
                  break;

                case ARRAY:
                   if (node instanceof IParseNode) {
                      Object sv = ((IParseNode) node).getSemanticValue();
                      if (sv instanceof List) {
                         if (parent.value == null)
                             parent.setSemanticValue(sv);
                         else if (replaceValue)
                            parent.value = sv;
                         else
                            ((List)parent.value).addAll((List) sv);
                      }
                      else if (sv != null) {
                         SemanticNodeList snl;
                         if (parent.value == null)
                            parent.setSemanticValue(snl = new SemanticNodeList());
                         else
                            snl = (SemanticNodeList) parent.value;
                         snl.add(sv);
                      }
                   }
                   else if (node != null) {
                      SemanticNodeList snl;
                      if (parent.value == null)
                         parent.setSemanticValue(snl = new SemanticNodeList());
                      else
                         snl = (SemanticNodeList) parent.value;
                      snl.add(node);
                   }
                   // TODO: should this be optional?  We need some empty list to hold the semantic value to differentiate
                   // between foo() and foo.
                   else {
                      List values = (List) parent.getSemanticValue();
                      if (values == null) {
                         values = new SemanticNodeList(0);
                         parent.setSemanticValue(values);
                      }
                      // This is false for typical identifier expression "a." but for partial values we need
                      // to preserve that null
                      if (allowNullElements)
                         values.add(null);
                      else if (allowEmptyPartialElements && parser.enablePartialValues)
                         values.add(PString.toIString(""));
                   }

                   break;
                /*
                 * This is the case where we want to set properties on the parentNode node from the child node
                 * as we walk up the tree.  The parentNode node specifies "*" in the slot for the child node.
                 * The child node just lists the properties it wants to set on the parentNode.
                 */
                case INHERIT:
                   if (node instanceof ParentParseNode) {
                      ParentParseNode pnode = (ParentParseNode) node;
                      // TODO: need to make sure these get processed
                      if (pnode.parselet.slotMapping != null) {
                         if (getSemanticValueIsArray() && !pnode.parselet.getSemanticValueIsArray()) {
                            List semValList = (List) parent.getSemanticValue();
                            if (semValList.size() == 0)
                               System.err.println("**** Warning: not processing slot mappings for value: " + pnode);
                            else
                               pnode.parselet.processSlotMappings(0, pnode, semValList.get(semValList.size()-1), true);
                         }
                         else
                            pnode.parselet.processSlotMappings(0, pnode, parent.getSemanticValue(), true);
                      }
                   }
                   else if (node != null)
                      System.err.println("*** The '*' operator was used on a slot which produced an invalid result: " + node);
                   break;
            }
         }
      }

      int sequenceSize = parselets.size();

      if (index == sequenceSize - 1) {
         int childrenSize = parent.children.size();

         int startIx = childrenSize - sequenceSize;
         if (startIx < 0)
            startIx = 0;

         Object toProcess = null;

         if (chainedPropertyMappings != null) {

            /* The last element of the second and subsequent sequences starts this processing */
            if (childrenSize > sequenceSize) {
               // Create a new parent parse node - pull off the last three children from "parent"
               // set new value as the semantic value for the new parse node
               ParentParseNode newParent = (ParentParseNode) newParseNode();

               for (int tm = startIx; tm < parent.children.size(); )
                  newParent.addGeneratedNode(parent.children.remove(startIx));

               /* Create the new parse node which will hold this result */
               Object newValue = createInstance(parser, getSemanticValueClass());
               newParent.setSemanticValue(newValue);

               Object lastParent = parent.getSemanticValue();
               Object lastNode = TypeUtil.getPropertyValue(lastParent, chainedPropertyMappings[1]);
               ParentParseNode lastParseNode = parent;
               while (lastParseNode.children.size() == 3) {
                  lastParseNode = (ParentParseNode) lastParseNode.children.get(2);
                  lastParent = lastNode;
                  lastNode = TypeUtil.getPropertyValue(lastNode, chainedPropertyMappings[1]);
                  if (lastNode == null)
                     throw new IllegalArgumentException("Unable to follow chained property for: " + this);
               }
               /*
               for (int ix = sequenceSize-1; ix < parent.children.size(); ix++) {
                  lastParent = lastNode;
                  lastNode = TypeUtil.getPropertyValue(lastNode, chainedPropertyMappings[1]);
                  if (lastNode == null)
                     throw new IllegalArgumentException("Unable to follow chained property for: " + this);
               }
               */

               /* Set the chained property in the old node to point to this new one */
               setSemanticProperty(newValue, chainedPropertyMappings[0], lastNode);
               setSemanticProperty(lastParent, chainedPropertyMappings[1], newValue);

               // We always set the properties on the new guy
               toProcess = newValue;

               //startIx = childrenSize - sequenceSize;
               lastParseNode.children.add(newParent);
               parent = newParent;
               startIx = 0;
            }
            else
               startIx = 0;
         }

         if (slotMapping != null && parameterType != ParameterType.INHERIT) {
            // Unless the chainedProperty mapping overrode this, we set this on the top level object
            if (toProcess == null)
               toProcess = parent.getSemanticValue();

            // Process the mappings when we are on the last item in the sequence.
            // Two reasons to wait: 1) for chained sequences and 2) for propagated values
            // so that we don't try to set the value before the propagated slot has been
            // processed.
            processSlotMappings(startIx, parent, toProcess, false);

            if (trace && parser.enablePartialValues)
               System.out.println("*** Semantic value of: " + this + FileUtil.LINE_SEPARATOR +
                       "    " + parent + " => " + parent.getSemanticValue());
         }
      }
   }

   public void processSlotMappings(int startIx, ParentParseNode srcNode, Object dstNode, boolean recurse) {
      if (dstNode == null)
         return;

      if (getSemanticValueIsArray()) {
         List dstList = (List) dstNode;
         // We are populating the last node in the list
         dstNode = dstList.get(dstList.size()-1);
      }

      if (slotMapping == null)
          return;

      for (int i = 0; i < slotMapping.length; i++) {
         if (slotMapping[i] != null) {
            // TODO: is this needed anymore?
            if (slotMapping[i] instanceof String) {
               System.out.println("**** Warning: binding mapping at runtime for: " + slotMapping[i]);

               Class slotClass = getSemanticValueIsArray() ? getSemanticValueComponentClass() : getSemanticValueClass();
               if (slotClass == null)
                  throw new IllegalArgumentException("No defined class for slot mapping on parselet: " + this);
               assignSlotMapping(i, slotClass);
               if (slotMapping[i] instanceof String)
                  System.err.println("*** Unable to lazily resolve slot mapping: " + this + "[" + i + "]");
            }

            Object value;
            if (startIx+i >= srcNode.children.size())
                System.out.println("*** ack");

            Object oldResult = srcNode.children.get(startIx + i);
            value = ParseUtil.nodeToSemanticValue(oldResult);
            setSemanticProperty(dstNode, slotMapping[i], value);
         }
      }

      /* The first level of the "*" operator is processed when we process that slot.  But if the value is
         defined by a second level parent, we will get a null at that point.  So when the parent processes
         its slot, we then have to go down multiple levels.
       */
      if (recurse && parameterType == ParameterType.INHERIT && inheritSlots != null) {
         for (int inheritSlot:inheritSlots)
            if (srcNode.parselet.slotMapping != null)
               ((NestedParselet)parselets.get(inheritSlot)).processSlotMappings(0,
                              (ParentParseNode)srcNode.children.get(inheritSlot), dstNode, true);
      }

      if (language.debug)
         System.out.println(this + " -> " + dstNode);
   }

   private void setSemanticProperty(Object semNode, Object mapping, Object value) {
      BeanMapper beanMapper;
      if (mapping instanceof BeanMapper && (beanMapper = (BeanMapper) mapping).isScalarToList() && value instanceof ISemanticNode) {
         ISemanticNode childNode = (ISemanticNode) value;
         SemanticNodeList snl = new SemanticNodeList(1);
         snl.setParentNode(childNode.getParentNode());
         snl.add(value);
         TypeUtil.setProperty(semNode, beanMapper.getSetSelector(), snl);
         snl.setParentNode((ISemanticNode) semNode);
      }
      else {
         // First set the value
         TypeUtil.setProperty(semNode, mapping, value);
         // For semantic nodes, we'll populate the parentNode pointer.
         if (value instanceof ISemanticNode && semNode instanceof ISemanticNode)
            ((ISemanticNode) value).setParentNode((ISemanticNode) semNode);
      }
   }

   public boolean skipSemanticValue(int index) {
      if (parameterMapping != null) {
         if (index >= parameterMapping.length)
             return false;
         return parameterMapping[index] == ParameterMapping.SKIP;
      }
      return false;
   }

   /**
    * Adds the supplied result node produced by this parselet to the parent parse node.
    * We are also provided the index of this parselet in the parent's sequence.
    * Basically, this handles parselet specific options which handle how the result's value
    * is produced.  You might override this method if you want to determine how a particular
    * parselet's result is merged into the parent pareselet's result.
    * <p>
    * If you are skipping "node" in the results, you instead directly add children nodes
    * to the parent.  When you add a child node, it is important to specificy the value of
    * the "skipsemanticValue" flag.  This determines whether or not this element's value is
    * included in the semantic value produced by the parent node.
    * </p>
    *
    * @param node the result value for this parselet - usually a ParseNode or a ParentParseNode
    * or some subclass of those.
    * @param parent the parent parse node we are added this result into.
    * @param index the position of this parselet in the parent's sequence
    * @return true if we are to add "node" directly to the parent and false if this method took
    * care already of propgating node to the parent.
    */
   public boolean addResultToParent(Object node, ParentParseNode parent, int index, Parser parser) {
      // Exclude this entirely from the result
      if (getDiscard() || getLookahead())
         return false;

      NestedParselet parentParselet = parent.parselet;

      if (getSkip() && node instanceof ParentParseNode) {
         ParentParseNode ppnode = (ParentParseNode) node;
         Object propagatedValue = null;

         // We have no information to preserve in this level of the hierarchy so we merge
         // it into the parentNode.
         if (parentParselet.parameterMapping == null) {
            ArrayList<Object> children = ppnode.children;
            int childSz = children.size();
            for (int i = 0; i < childSz; i++) {
               Object c = children.get(i);

               // We are adding a nested child which is marked as being skipped.  At this
               // point we add its children directly to the parentNode node - we'll throw away
               // this temporary parentNode node.  At this point, the value is the semantic value
               parent.add(c, this, index, false, parser);
            }
            return false; // Do not add to the parentNode node
         }
         /* We are skipping this value and propagating one slot up.  If there are any slot
         * names, we set the corresponding property on the value we are propagating up.
         * This corresponds to a pattern like (foobar,.) which means set the foobar property
         * on the "." slot which becomes the value for this node.
         else if (parentParselet.propagatedValueIndex != -1)
         {
            Object[] mapping = parentParselet.slotMapping;
            if (ppnode.children.size() != mapping.length)
                System.err.println("*** - wrong number of children for nested parselet");

            if (parentNode.value == null)
               System.err.println("*** Error: No semantic value class defined on parentNode with slot mapping: " + this);
            else
            {
               for (int i = 0; i < mapping.length; i++)
                  if (mapping[i] != null)
                     TypeUtil.setProperty(parentNode.value, mapping[i], ParseNode.nodeToSemanticValue(ppnode.children.get(i)));
            }
         }
         */
      }

      return true;
   }

   public boolean setResultOnParent(Object node, ParentParseNode parent, int index, Parser parser) {
      // Exclude this entirely from the result
      if (getDiscard() || getLookahead())
         return false;

      NestedParselet parentParselet = parent.parselet;

      if (getSkip() && node instanceof ParentParseNode) {
         ParentParseNode ppnode = (ParentParseNode) node;

         // We have no information to preserve in this level of the hierarchy so we merge
         // it into the parentNode.
         if (parentParselet.parameterMapping == null) {
            ArrayList<Object> children = ppnode.children;
            int childSz = children.size();
            for (int i = 0; i < childSz; i++) {
               Object c = children.get(i);

               // We are adding a nested child which is marked as being skipped.  At this
               // point we add its children directly to the parentNode node - we'll throw away
               // this temporary parentNode node.  At this point, the value is the semantic value
               parent.set(c, this, index, false, parser);
            }
            return false; // Do not add to the parentNode node
         }
      }
      return true;
   }

   public Class getSemanticValueClass() {
      if (!initialized)
         init();

      if (getSemanticValueIsArray()) {
         if (parameterType == ParameterType.STRING)
            return resultClass; // IString.
         return ARRAY_LIST_CLASS;
      }

      // Sentinel... computation is in process so must be dealing with a recursive type
      if (resultClass == UNDEFINED_CLASS)
          return null;

      if (resultClass != null) {
         return resultClass;
      }

      if (propagatedValueIndex != -1)
         return parselets.get(propagatedValueIndex).getSemanticValueClass();

      return null;
   }

   public Class getSemanticValueComponentClass() {
      if (!initialized)
         init();

      if (getSemanticValueIsArray()) {
         if (resultClass == UNDEFINED_CLASS)
            return null;
         if (resultClass != null)
         {
            if (resultClass == ARRAY_LIST_CLASS)
               return resultComponentClass;
            return resultClass;
         }

         if (propagatedValueIndex != -1)
         {
            return parselets.get(propagatedValueIndex).getSemanticValueClass();
         }
      }
      else
      {
         if (resultComponentClass == UNDEFINED_CLASS)
            return null;

         if (resultComponentClass != null)
            return resultComponentClass;

         resultComponentClass = UNDEFINED_CLASS;

         try
         {
            if (propagatedValueIndex != -1)
               return parselets.get(propagatedValueIndex).getSemanticValueComponentClass();

            if (parameterType == ParameterType.ARRAY)
            {
               Class componentClass = null;
               boolean unresolved = false;
               int numParselets = parselets.size();
               for (int i = 0; i < numParselets; i++)
               {
                  Parselet parselet = parselets.get(i);
                  if (parameterMapping[i] == ParameterMapping.ARRAY)
                  {
                     Class newClass = parselet.getSemanticValueClass();

                     if (newClass == ARRAY_LIST_CLASS)
                        newClass = parselet.getSemanticValueComponentClass();
                     else if (newClass != null)
                     {
                        if (componentClass == null)
                           componentClass = newClass;
                        else
                           componentClass = findCommonSuperClass(componentClass, newClass, i);
                     }
                     else if (newClass == null)
                        unresolved = true;
                  }
               }
               if (!unresolved)
                  resultComponentClass = componentClass;
            }
         }
         finally
         {
            if (resultComponentClass == UNDEFINED_CLASS)
               resultComponentClass = null;
         }
      }
      return null;
   }


   public Class getParseNodeClass()
   {
      return ParentParseNode.class;
   }

   public boolean needsChildren()
   {
      return parameterMapping != null;
   }

   static final Class UNDEFINED_CLASS = UNDEFINED.class;
   private static class UNDEFINED
   {
   }

   /**
    * Taking a short cut to the generation process.  When we have a string for a distinct generated value, we can
    * use the string as the generated output.  We do have to account for any formatting parse nodes, i.e. spacing,
    * newlines, etc. inserted automatically.   We gather those up so that they are applied during the format method
    * just as they would ordinarily be if we generated individual parse nodes for each child parselet.
    */
   protected ParseNode newGeneratedSimpleParseNode(Object value, Parselet childParselet) {
      FormattedParseNode pnode = new FormattedParseNode(childParselet);
      pnode.value = value;
      pnode.generated = true;
      pnode.formattingParseNodes = childParselet.getFormattingParseNodes(new HashSet<Parselet>(16));
      // TODO? pnode.generated = true;
      return pnode;
   }

   protected ParseNode newGeneratedSimpleParseNodeNoFormatting(Object value, Parselet childParselet) {
      FormattedParseNode pnode = new FormattedParseNode(childParselet);
      pnode.value = value;
      pnode.generated = true;
      // TODO? pnode.generated = true;
      return pnode;
   }

   public ParentParseNode newGeneratedParseNode(Object value) {
      IParseNode pnode = newParseNode();

      // associate the new parse node in both directions with the semantic value.
      pnode.setSemanticValue(value);
      //if (value instanceof ISemanticNode)
      //   ((ISemanticNode) value).setParseNode(pnode);

      ParentParseNode ppnode = (ParentParseNode) pnode;
      ppnode.generated = true;
      return ppnode;
   }

   /**
    * Determines if there is any semantic information left in the value supplied as
    * determined by the parselet.  If the value is null, it is empty.  If the parselet
    * sets properties on its parent object during the parse phase, we view that parent
    * as empty if all of the slots are null.
    *
    * This method lets us determine when an optional slot has failed... if it is empty
    * a failure is not reported.
    *
    * @param ctx
    * @param value
    * @return true if there's no real value.
    */
   public boolean emptyValue(GenerateContext ctx, Object value) {
      if (super.emptyValue(ctx, value))
         return true;

      if (parameterType == ParameterType.INHERIT || parameterType == ParameterType.DEFAULT) {
         // Not doing this for arrays.
         if (!getSemanticValueIsArray() && getSemanticValueClass() != null && getSemanticValueClass().isInstance(value)) {
            int numParselets = parselets.size();
            for (int i = 0; i < numParselets; i++) {
               Parselet childParselet = parselets.get(i);
               if (parameterMapping == null || parameterMapping[i] == ParameterMapping.INHERIT) {
                  if (!childParselet.emptyValue(ctx, value))
                     return false;
               }
               else if (parameterMapping[i] == ParameterMapping.NAMED_SLOT) {
                  Object slotValue = ctx != null ? ctx.getPropertyValue(value, slotMapping[i]) : TypeUtil.getPropertyValue(value, slotMapping[i]);

                  // Special case: when we are populating a slot, we'll set it as null
                  // if there is no entries in the list.  An empty list means something
                  // different.  So in this case only, we consider an empty list as
                  // a non-empty value.
                  if (slotValue instanceof List && ((List) slotValue).size() == 0)
                     return false;
                  if (!childParselet.emptyValue(ctx, slotValue))
                     return false;
               }
            }
            return true;
         }
      }
      return false;
   }

   public abstract Object generateElement(GenerateContext ctx, Object value, boolean isArray);

   /** Return the remaining items in the list */
   SemanticNodeList getRemainingValue(SemanticNodeList l, int arrayIndex) {
      if (arrayIndex == 0)
         return l;

      if (arrayIndex == l.size())
         return null;

      SemanticNodeList sub = new SemanticNodeList(l.size() - arrayIndex);
      sub.parentNode = l.parentNode;
      // Add nodes to a semantic list here but do not change the parent node
      // those values need to stay part of their original graph
      for (int i = arrayIndex; i < l.size(); i++)
         sub.add(l.get(i), false);
      return sub;
   }

   public abstract Parselet getChildParselet(Object parseNode, int index);

   static int _x = 0;

   public IParseNode[] getFormattingParseNodes(Set<Parselet> visited) {
      ArrayList<IParseNode> res = null;
      if (visited.contains(this))
         return null;
      for (int i = 0; i < parselets.size(); i++) {
         Parselet p = parselets.get(i);
         if (p.generateParseNode != null) {
            if (res == null)
               res = new ArrayList<IParseNode>();
            res.add(p.generateParseNode);
         }
         visited.add(this);
         IParseNode[] childFormatters = p.getFormattingParseNodes(visited);
         if (childFormatters != null) {
            if (res == null)
               res = new ArrayList<IParseNode>();
            res.addAll(Arrays.asList(childFormatters));
         }
      }
      return res == null ? null : res.toArray(new IParseNode[res.size()]);
   }

   int updateParseNodesForElement(Object semanticValue, IParseNode parentNode, boolean processList) {
      ParentParseNode parent = (ParentParseNode) parentNode;

      if (parent.children == null)
         return MATCH;

      int numParseNodes = parent.children.size();
      int ix = 0;
      int sz = parselets.size();
      int pix = 0;
      boolean isList = false;
      List list;
      if (processList) {
         list = (List) semanticValue;
         if (list.size() == 0)
            return optional ? MATCH : NO_MATCH;
         semanticValue = list.get(0);
      }
      else
         list = null;
      int listSize = -1;
      int restartedIx = -1;
      for (int i = 0; i < numParseNodes; i++, pix++) {
         Object childParseRes = parent.children.get(i);

         // Have we wrapped around to the next iteration of this sequence?
         if (pix == sz) {
            if (restartedIx == 0)
               return NO_MATCH;
            pix = 0;

            // When unwrapping the outer list value, this means moving to the next parselet
            if (processList) {
               ix++;
               semanticValue = list.get(ix);
            }
         }


         Parselet p = parselets.get(pix);
         if (!(childParseRes instanceof IParseNode))
            continue;
         if (parameterMapping != null) {
            IParseNode childParseNode = (IParseNode) childParseRes;
            switch (parameterMapping[pix]) {
               case SKIP:
                  break;

               case PROPAGATE:
                  if (!p.parseNodeMatches(semanticValue, childParseNode))
                     return NO_MATCH;
                  int num = p.updateParseNodes(semanticValue, childParseNode);
                  if (num != MATCH)
                     return num;

                  restartedIx = -1;

                  break;

               case STRING:
                  break;

               case NAMED_SLOT:
                  Object slotValue = TypeUtil.getPropertyValue(semanticValue, slotMapping[pix]);
                  if (slotValue != null) { // optional check?
                     Object parseNodeValue = childParseNode.getSemanticValue();

                     // For the ChainedResultSequence, we end up setting properties after the match which conflict with the match itself.
                     // These properties will appear to have changed from when the parseNode was generated.   For the purposes of the match
                     // we want to pretend they are null.  During Generate, we mask off properties during the generation process to simulate
                     // them being null.  For just updating the parse nodes however, we have the parse node and have its semantic value.
                     // when these do not match, it is always a case we can reject.
                     if (!ParseUtil.equalSemanticValues(parseNodeValue, slotValue)) {
                        // In this case, we use the parse node's value to match
                        if (this instanceof ChainedResultSequence) {
                           slotValue = parseNodeValue;
                           if (slotValue == null)  // optional check?
                              break;
                        }
                        else
                           return NO_MATCH;
                     }

                     slotValue = ParseUtil.convertSemanticValue(p.getSemanticValueClass(), slotValue);
                     if (slotValue != ParseUtil.CONVERT_MISMATCH) {
                        int numMatched = p.updateParseNodes(slotValue, childParseNode);
                        if (numMatched != MATCH) {
                           if (slotValue instanceof List)
                              return numMatched == ((List) slotValue).size() ? MATCH : NO_MATCH;
                           return NO_MATCH;
                        }
                        else if (numMatched != MATCH)
                           System.err.println("*** match child method returned number of matched elmeents : not supported for slot values");
                        restartedIx = -1;
                     }
                     else // Abort before we do more damage to the object.  Sometimes we try to update the wrong rule in the parent node.
                        return NO_MATCH;
                  }

                  break;

               case ARRAY:
                  if (semanticValue instanceof SemanticNodeList) {
                     isList = true;
                     SemanticNodeList snl = (SemanticNodeList) semanticValue;
                     listSize = snl.size();
                     while (ix < snl.size()) {
                        if (ParseUtil.isArrayParselet(p)) {
                           List childList = getRemainingValue(snl, ix);
                           // processing one at a time to avoid the whole partial array stuff... will that work?
                           int numMatched = p.updateParseNodes(childList, childParseNode);

                           if (numMatched == MATCH)
                              numMatched = childList.size();
                           if (numMatched <= 0) {
                              restartedIx = i;
                              i--;// did not process this parse node so back it up
                              break;
                           }
                           else
                              ix += numMatched - 1;
                        }
                        else {
                           int nr = p.updateParseNodes(snl.get(ix), childParseNode);
                           if (nr != MATCH) {
                              restartedIx = i;
                              i--; // did not process this parse node so back it up
                              break;
                           }
                        }
                        restartedIx = -1;

                        // Advance the array if we matched
                        ix++;

                        // If we are re-iterating in this loop, advance the parse node
                        // otherwise, it will advance in the next one
                        if (ix < snl.size()) {
                           i++;
                           if (i < numParseNodes) {
                              childParseRes = parent.children.get(i);
                              if (!(childParseRes instanceof IParseNode)) {
                                 // We're consuming a NULL here which may correspond to a skipped parselet.  In that case, need to skip that parselet here.  In other cases, it might be an optional array parselet and for some reason skipping the parselet breaks IdentifierExpressions.
                                 if (pix < parselets.size() - 1 && parameterMapping[pix+1] == ParameterMapping.SKIP)
                                    pix++;
                                 break;   // TODO: NEED TO ADVANCE the parselet if
                              }
                              else
                                 childParseNode = (IParseNode) childParseRes;
                           }
                        }
                     }
                  }
                  else if (semanticValue instanceof ISemanticNode) {
                     ISemanticNode node = (ISemanticNode) semanticValue;
                     if (p.updateParseNodes(node, childParseNode) != MATCH)
                        return NO_MATCH;
                  }
                  else if (PString.isString(semanticValue)) {
                     if (isList)
                        ix++;
                  }
                  else {
                     System.err.println("*** Unrecognized semantic value type for array node.");
                  }

                  break;
               case INHERIT:
                  if (p.updateParseNodes(semanticValue, childParseNode) != MATCH)
                     return NO_MATCH;
                  restartedIx = -1;
                  break;

               default:
                  break;
            }
         }
      }
      if (processList && pix == sz)
         ix++;
      if (!isList)
         return MATCH;
      return ix;
   }

   public int updateParseNodes(Object semanticValue, IParseNode parentNode) {
      // Can get here with empty spacing nodes or formatted nodes
      if (!(parentNode instanceof ParentParseNode))
         return NO_MATCH;

      // Because ordered choice objects may not pick the right structure the first time, this is a bit hit or miss, for example
      // a cast expression where the type can either match a primitive or a class type.
      if (!parseNodeMatches(semanticValue, parentNode)) {
         if (optional && emptyValue(null, semanticValue))
            return MATCH;

         return NO_MATCH;
      }

      if (getSemanticValueIsArray()) {
         if (!(semanticValue instanceof SemanticNodeList))
            return MATCH;

         // If we are not aggregating array values from our children, process the elements one-by-one
         if (parameterType != ParameterType.ARRAY) {
            int sz;
            if ((sz = updateParseNodesForElement(semanticValue, parentNode, true)) != MATCH) {
               if (sz != NO_MATCH)
                  super.updateParseNodes(semanticValue, parentNode);
               return sz;
            }
         }
         else {
            SemanticNodeList remaining = (SemanticNodeList) semanticValue;
            int ret = NO_MATCH;
            do {
               int num = updateParseNodesForElement(remaining, parentNode, false);
               if (num == NO_MATCH) {
                  if (ret != NO_MATCH)
                     super.updateParseNodes(semanticValue, parentNode);
                  return ret;
               }
               if (num == MATCH)
                  num = remaining.size();
               if (num == remaining.size())
                  break;
               else if (num == 0)
                  return ret;
               if (ret == NO_MATCH)
                  ret = num;
               else
                  ret += num;
               remaining = getRemainingValue(remaining, num);
            } while (remaining.size() > 0);
         }
      }
      else {
         int num = updateParseNodesForElement(semanticValue, parentNode, false);
         if (num != MATCH) {
            if (semanticValue instanceof List) {
               if (num == ((List) semanticValue).size()) {
                  super.updateParseNodes(semanticValue, parentNode);
                  return MATCH;
               }
               return NO_MATCH;
            }
            return NO_MATCH;
         }
      }

      // By doing this after we update the children, we ensure that the highest node for the same semantic value
      // gets mapped.
      return super.updateParseNodes(semanticValue,  parentNode);
   }

   public boolean producesParselet(Parselet other) {
      if (super.producesParselet(other))
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
               return child.producesParselet(other);
         }
      }
      return false;
   }

   public Object clone() {
      NestedParselet newP = (NestedParselet) super.clone();
      newP.parselets = (ArrayList<Parselet>) newP.parselets.clone();
      return newP;
   }


   public void format(FormatContext ctx, IParseNode node) {
      boolean old = false;
      boolean oldTagMode = false;
      if (suppressSpacing) {
         old = ctx.suppressSpacing;
         ctx.suppressSpacing = true;
      }
      else if (enableSpacing) {
         old = ctx.suppressSpacing;
         ctx.suppressSpacing = false;
      }
      if (suppressNewlines) {
         old = ctx.suppressNewlines;
         ctx.suppressNewlines = true;
      }
      if (enableTagMode || disableTagMode) {
         oldTagMode = ctx.tagMode;
         ctx.tagMode = enableTagMode;
      }
      if (pushIndent)
         ctx.pushIndent();
      try {
         super.format(ctx, node);
      }
      finally {
         if (suppressSpacing || enableSpacing)
            ctx.suppressSpacing = old;
         if (enableTagMode || disableTagMode)
            ctx.tagMode = oldTagMode;
         if (suppressNewlines)
            ctx.suppressNewlines = old;
         if (popIndent) {
            ctx.autoPopIndent();
         }
      }
   }

   public boolean isStringParameterMapping() {
      if (parameterMapping == null)
         return false;
      boolean result = false;
      for (ParameterMapping type:parameterMapping) {
         if (type == ParameterMapping.STRING)
            result = true;
         else if (type != ParameterMapping.SKIP)
            return false;
      }
      return result;
   }

   public boolean isComplexStringType() {
      return complexStringType;
   }
}
