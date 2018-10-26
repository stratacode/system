/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.bind.Bind;
import sc.bind.Bindable;
import sc.bind.BindingListener;
import sc.bind.IListener;
import sc.dyn.DynUtil;
import sc.dyn.IObjChildren;
import sc.js.ServerTag;
import sc.js.URLPath;
import sc.lang.*;
import sc.lang.java.*;
import sc.lang.js.JSRuntimeProcessor;
import sc.lang.sc.ModifyDeclaration;
import sc.lang.sc.PropertyAssignment;
import sc.lang.sc.ScopeModifier;
import sc.lang.template.*;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.lifecycle.ILifecycle;
import sc.obj.*;
import sc.parser.*;
import sc.obj.IChildInit;
import sc.sync.SyncManager;
import sc.sync.SyncProperties;
import sc.type.CTypeUtil;
import sc.type.IBeanMapper;
import sc.type.PTypeUtil;
import sc.util.FileUtil;
import sc.util.IdentityHashSet;
import sc.util.StringUtil;
import sc.util.URLUtil;

import java.util.*;

/**
 * This class, along with HTMLElement, serve as the server-side implementations for tags in the system.  The class itself inherits from Node which extends JavaSemanticNode.
 * While this makes the javadoc hard to read because of all of the methods, it gives you a very simple API for manipulating HTML documents.  After the HTML file is parsed, you
 * can edit the attributeList and children properties using the SemanticNodeList interface.  As you change those properties, the HTML definition can be updated on the fly or
 * invalidated and refreshed as needed.
 * <p>Most of the time though, your Element instances will not result from being parsed.  Instead, this class is also used as the base class for tag objects instances on the
 * server when you compiled your schtml files.  In this case, the Element instances don't have parse nodes.  Rendering HTML occurs in the generated code in the outputStartTag and
 * outputBody methods.  If you ever did need the HTML though, you could regenerate it.  Or if you needed to parse HTML, validate it, manipulate it, then save it, you can do so
 * all with this one flexible Java api.
 * </p>
 * <p>If this were not enough functionality, Element's also implement the IDynObject interface.  This lets us use Element instances directly from interpreted schtml code, avoiding
 * the need for generating wrapper classes.  This makes the dynamic mode on the server faster, more flexible and efficient.</p>
 * <p>Currently, the api doc for this class is hard to wade through given all of these concerns implemented in one class.  When we implement SC in SC, we can fix this with layered doc :)</p>
 */
// Turn off synchronization by default for all of the tag types etc.  In general, you want to synchronize the view-model layers, not the view layers themselves.
@Sync(syncMode=SyncMode.Disabled, includeSuper=true)
// Using the js_ prefix for the tags.  Because tags.js uses the SyncManager in the JS file, we need to add this dependency explicitly.
@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
@CompilerSettings(dynChildManager="sc.lang.html.TagDynChildManager")
@ResultSuffix("html")
public class Element<RE> extends Node implements IChildInit, IStatefulPage, IObjChildren, ITypeUpdateHandler, ISrcStatement, IStoppable {
   // Set to true for trace and/or verbose messages - great for debugging problems with schtml
   public static boolean trace = false, verbose = false;
   private final static sc.type.IBeanMapper _startTagTxtProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Element.class, "startTagTxt");
   private final static sc.type.IBeanMapper _innerHTMLProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Element.class, "innerHTML");

   /** When a tag is invisible, instead of rendering the tag, we render the 'alt' child if there is one */
   private final static String ALT_ID = "alt";
   private final static Element[] EMPTY_ELEMENT_ARRAY = new Element[]{};

   // These are the properties of the Element which are populated from the HTMLLanguage - i.e. the 'non-transient' properties
   public String tagName;
   public SemanticNodeList<Attr> attributeList;
   public SemanticNodeList<Object> children;
  // Set to true from the parser if this tag has a close symbol in the open tag
   public Boolean selfClose;
   // Should always match tagName via toLowerCase.  TODO: For the grammar, we should only need to store a boolean as to whether there's a close tag or not.  But we also need a read-only "closeTagName" property for generation and not sure how to specify that.  I suppose using different rules for generation than for parsing?
   public String closeTagName;

   public transient SemanticNodeList<Object> hiddenChildren;
   public transient SemanticNodeList<Attr> inheritedAttributes;

   private transient String id;

   private transient boolean specifiedId = false;

   public transient TypeDeclaration tagObject;

   private transient TypeDeclaration repeatWrapper;

   private transient Object modifyType;

   private transient TreeMap<String,Element[]> childrenById = null;

   private transient Object extendsTypeDecl;

   /**
    * Tags are rendered in two different phases - 1) the start tag, which includes the attributes and 2) the body.
    * These flags indicate if the current rendered version is known to be stale for each of these two phases
    */
   public transient boolean startTagValid = false, bodyValid = false;

   /**
    * Like bodyValid but applies only to changes made to the static text chunks.  When a child tag's bodyTxt changes,
    * we set both this and bodyValid = false.  For the parent nodes, we only make bodyValid = false.  This way, we can
    * walk down the tree to find any child nodes which have actually changed.
    */
   public transient boolean bodyTxtValid = false;

   /** A separate flag to handle the state where we know repeat has changed but don't know if the bodyTxt of the list has changed or just an element */
   public transient boolean repeatTagsValid = true;

   public transient String startTagCache, bodyCache;
   public transient CacheMode cache;

   private transient boolean needsSuper = false;
   private transient boolean needsBody = true;

   private transient boolean convertingToObject = false;

   private transient Object defaultExtendsType = null;

   /** When processing a template once we hit a parent node whose type is not available in this mode, we insert a dummy node and only process static portsion of the template until some tag sets exec="process" to turn back on processing */
   public transient boolean staticContentOnly = false;

   private transient Object repeat;

   /** When we have a repeat value, this listener */
   private transient RepeatListener repeatListener = null;

   private transient boolean visible = true;

   private transient Element[] invisTags = null;

   // This property is set for the client version of the tag objects on tags whose content is defined on the server, not dynamically by running the Javascript code on the client.
   // This property is not the same as setting exec="server" which indicates that the tag object itself should not be included in the client version at all.
   // It makes sense to set serverContent=true when you want that content to be included in the static HTML file for the client-only version.
   // For example, you'd set serverContent on tags which generate the script tags to include javascript.
   public transient boolean serverContent = false;

   /** Set to true in the runtime version of the tag object for tags which are to be run on the server only */
   public transient boolean serverTag = false;

   public transient boolean needsRefresh = false;

   private transient String cachedObjectName = null;

   public final static boolean nestedTagsInStatements = false;
   private static final boolean oldExecTag = false;

   public Element() {
   }
   public Element(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
   }

   @Bindable(manual=true)
   public void setVisible(boolean vis) {
      visible = vis;
      invalidateStartTag();
      Bind.sendChangedEvent(this, "visible");
   }

   /** Is this tag visible.  Note: using isVisible here to match swing's isVisible */
   public boolean isVisible() {
      return visible;
   }

   /**
    * Typed either as a List<RE> or an RE[]
    *
    * This instance can be used in two modes - one as the controller of the array in which case repeat is set.  The other when it represents the element of the array and repeatVar is set to the element of the array.
    */
    public void setRepeat(Object rv) {
      repeat = rv;
      if (rv != null) {
         if (repeatListener == null) {
            repeatListener = new RepeatListener(this);
            // TODO: need to use VALUE_CHANGED rather than just INVALIDATED because we do not propagate the 'invalidate' event across bindings otherwise.  I'm not sure this is right!  An example is when we try to add a 'todo' to the TodoList sample with the repeat := todos binding.
            Bind.addListener(this, "repeat", repeatListener, IListener.VALUE_CHANGED);
         }
      }
      Bind.sendChangedEvent(this, "repeat");
   }

   public Object getRepeat() {
      return repeat;
   }

   private RE repeatVar;

   @Bindable(manual=true)
   public void setRepeatVar(RE rv) {
      if (rv != repeatVar) {
         repeatVar = rv;
         Bind.sendChangedEvent(this, "repeatVar");
      }
   }

   public RE getRepeatVar() {
      return repeatVar;
   }

   private int repeatIndex = -1;

   @Bindable(manual=true)
   public void setRepeatIndex(int ix) {
      if (ix != repeatIndex) {
         repeatIndex = ix;
         Bind.sendChangedEvent(this, "repeatIndex");
      }
   }

   public int getRepeatIndex() {
      return repeatIndex;
   }

   private ArrayList<Element> repeatTags = null;

   private String repeatVarName;

   public String getRepeatVarName() {
      if (repeatVarName != null)
         return repeatVarName;
      return repeatVarName = getFixedAttribute("repeatVarName");
   }

   public void setRepeatVarName(String rvn) {
      repeatVarName = rvn;
   }

   public boolean isRepeatElement() {
      return getAttribute("repeat") != null;
   }

   private Element createRepeatElement(Object val, int ix, Element oldTag) {
      Element res;
      boolean flush = false;
      flush = SyncManager.beginSyncQueue();
      // When this instance was created from a tag, create the element from the generated code.
      if (this instanceof IRepeatWrapper) {
         IRepeatWrapper wrapper = (IRepeatWrapper) this;
         res = wrapper.createElement(val, ix, oldTag);
      }
      else if (dynObj != null) {
         res = (Element) dynObj.invokeFromWrapper(this, "createElement","Ljava/lang/Object;ILsc/lang/html/Element;", val, ix, oldTag);
      }
      // If this Element instance was created by parsing an schtml file, we can implement the repeat using the parsed semantic node tree.
      else {
         Element repeatElem = (Element) this.deepCopy(ISemanticNode.CopyNormal, null);
         repeatElem.removeAttribute("repeat");
         repeatElem.removeAttribute("repeatVar");
         repeatElem.repeatVar = val;
         repeatElem.parentNode = this;
         repeatElem.repeatIndex = ix;
         res = repeatElem;
      }
      if (res != null) {
         res.parentNode = this;
         registerSyncInstAndChildren(res);
      }
      if (flush)
         SyncManager.flushSyncQueue();
      return res;
   }

   // This method is used to register an instance and it's children with the sync system so the changes are not
   // synchronized to the remote side. It's used for example for the repeat element's identity, which is automatically
   // kept in sync.   We mark these instances here as "not new" so changes are not sent.
   private void registerSyncInstAndChildren(Object res) {
      SyncManager.registerSyncInst(res);
      // Need to register this entire tree with the sync system, at least as far as it is sync'd.
      Object[] children = DynUtil.getObjChildren(res, null, true);
      if (children != null) {
         for (Object child:children) {
            registerSyncInstAndChildren(child);
         }
      }
   }

   public final static int ExecProcess = 1;
   public final static int ExecServer = 2;
   public final static int ExecClient = 4;
   public final static int ExecAll = ExecProcess | ExecServer | ExecClient;

   public transient int execFlags = 0;

   private HashMap<String,Integer> idSpaces;

   public String allocUniqueId(String name) {
      return allocUniqueId(name, false);
   }

   /** If this only runs on one side or the other, it's a client/server specific node and gets named differently */
   public boolean isClientServerSpecific() {
      int flags = getComputedExecFlags();
      if ((flags & ExecServer) == 0 || (flags & ExecClient) == 0)
         return true;
      Element enclTag = getEnclosingTag();
      // Not checking serverContent on the tag itself because we do create the tag with this attribute set on both the client and server.  The child
      // tags for that tag though are not created and so should not be using symmetric ids.
      if (enclTag != null && (enclTag.isClientServerSpecific() || enclTag.getBooleanAttribute("serverContent")))
         return true;
      return false;
   }

   public boolean getNeedsClientServerSpecificId() {
      if (isSingletonTag())
         return false;
      // Don't do client/server specific tags for head or body but we need to do them for the children
      return isClientServerSpecific();
   }

   public boolean isSingletonTag() {
      // TODO: there are cases where we have no sub-classes of this tag and it's a child of a singleton tag where we could use the normal id.
      // It's too bad because it's a pain to support id CSS mappings when they keep changing.
      return (tagName != null && singletonTagNames.contains(tagName));
   }

   /*
    * Do we need a way to procedurally tell if a tag is a singleton in the page?  If so, it would look like this
   public boolean isSingletonChild() {
      if (tagName != null && singletonTagNames.contains(tagName))
         return true;
      Element enclTag = getEnclosingTag();
      if (enclTag.isSimpleParentTag())
         return enclTag.isSingletonChild();
      return false;
   }

   public boolean isSimpleParentTag() {
      return !isRepeatElement() && (tagName == null || !isRepeatingTagName(tagName));
   }
   */

   // These ids should be consistent for a given page because we traverse the object graph in the same order on client/server.
   // It uses the idSpaces map stored on the root tag.
   public String allocUniqueId(String name, boolean clientServerSpecific) {
      String suffix = clientServerSpecific ? "_s" : null;
      if (suffix != null)
         name = name + suffix;
      if (idSpaces == null) {
         Element rootElement = getRootTag();
         if (rootElement.idSpaces == null)
            rootElement.idSpaces = new HashMap<String,Integer>();
         idSpaces = rootElement.idSpaces;
      }
      Integer cur = idSpaces.get(name);
      if (cur == null) {
         idSpaces.put(name, cur = Integer.valueOf(1));
         return name;
      }
      else {
         idSpaces.put(name, cur + 1);
         return name + (suffix == null ? "_" : "") + cur;
      }
   }

   public void init() {
      super.init();
      if (tagObject != null && !tagObject.isInitialized()) {
         tagObject.init();
      }
   }

   public void start() {
      try {
         super.start();
         if (tagObject != null && !tagObject.isStarted())
            tagObject.start();
      }
      catch (RuntimeException exc) {
         clearStarted();
         throw exc;
      }
   }

   private static int parseExecFlags(String execStr) {
      String[] execVals = execStr.toLowerCase().split(",");
      int flags = 0;
      for (String execVal:execVals) {
         if (execVal.equals("client"))
            flags |= ExecClient;
         else if (execVal.equals("server"))
            flags |= ExecServer;
         // TODO: remove this one?
         else if (execVal.equals("process"))
            flags |= ExecProcess;
      }
      return flags;
   }

   public int getDefinedExecFlags() {
      String execStr = getFixedAttribute("exec");
      int execFlags = 0;
      if (execStr != null) {
         execFlags = parseExecFlags(execStr);
      }
      else {
         Element elem = getDerivedElement();
         if (elem != null)
            execFlags = elem.getDefinedExecFlags();
         if (execFlags == 0) {
            elem = getExtendsElement();
            if (elem != null)
               execFlags = elem.getDefinedExecFlags();
         }
         //execFlags = lowerTagName().equals("head") ? ExecServer : 0;
      }
      return execFlags;
   }

   /** Returns the derived execFlags for this tag - i.e. the value of the exec=".." attribute or the inherited value if it's not set, defaulting to "ExecAll" */
   public int getComputedExecFlags() {
      if (execFlags == 0) {
         execFlags = getDefinedExecFlags();
         if (execFlags == 0) {
            Element el = getEnclosingTag();
            if (el != null)
               return el.getComputedExecFlags();
            return ExecAll;
         }
      }
      return execFlags;
   }

   /** Block statement itself does not increase the indent */
   public int getChildNestingDepth() {
      if (!isIndented())
         return super.getChildNestingDepth();
      if (parentNode != null) {
         return parentNode.getChildNestingDepth() + 1;
      }
      return 0;
   }

   boolean isIndented() {
      return HTMLLanguage.getHTMLLanguage().INDENTED_SET.contains(tagName);
   }

   public boolean isAbstract() {
      return getBooleanAttribute("abstract");
   }

   Object getEnclosingContext() {
      ISemanticNode parent = parentNode;
      while (parent != null) {
         if (parent instanceof GlueExpression || parent instanceof GlueStatement || parent instanceof Element || parent instanceof Template)
            return parent;
         parent = parent.getParentNode();
      }
      return null;
   }

   boolean getContextSupportsObjects() {
      Object ctx = getEnclosingContext();
      while (ctx instanceof Element) {
         ctx = ((Element) ctx).getEnclosingContext();
      }
      if (ctx instanceof GlueExpression || ctx instanceof GlueStatement)
         return false;
      if (ctx == null)
         System.err.println("*** No context for element?");
      return true;
   }

   private boolean childNeedsObject() {
      // TODO: should be checking these children (and their children for explicit exec=".." lines which override the parent's exec=".." line (e.g. an exec="server" inside of an exec="client" or vice versa)
      // in that case, it seems like the parents in the chain above the  should not have isRemoteContent... it's more like setting exec=".." on each of the other children individually down to th
      if (needsChildList(hiddenChildren))
         return true;
      if (needsChildList(children))
         return true;
      return false;
   }

   private boolean needsChildList(SemanticNodeList<Object> childList) {
      if (childList == null)
         return false;
      for (Object o:childList)
         if (o instanceof Element && ((Element) o).needsObject())
            return true;
      return false;
   }

   // When generating the object we need to indicate in the tag object this is remote content.
   private boolean isRemoteContent() {
      Template template = getEnclosingTemplate();
      if (template == null)
         return false;

      int genFlags = template.getGenerateExecFlags();
      return (genFlags & getComputedExecFlags()) == 0;
   }

   /** At runtime, we are a server tag if we are explicitly marked, or our parent tag is marked.  Eventually serverTag
    * gets set on all server tags to avoid the walk up the tree. */
   private boolean isServerTag() {
      if (serverTag)
         return true;
      Element enclTag = getEnclosingTag();
      if (enclTag != null)
         return enclTag.isServerTag();
      return false;
   }

   /** For compile time - use this to determine if the schtml file marks this as a server tag based on the tree */
   private boolean isMarkedAsServerTag() {
      Template template = getEnclosingTemplate();
      if (template == null)
         return false;

      int execFlags = getComputedExecFlags();
      if ((execFlags & ExecServer) != 0 && (execFlags & ExecClient) == 0)
         return true;
      return false;
   }

   public boolean execOmitObject() {
      Template template = getEnclosingTemplate();
      if (template == null)
         return false;
      int genFlags = template.getGenerateExecFlags();
      return (((genFlags & getComputedExecFlags()) == 0 && !childNeedsObject()) && genFlags == ExecServer);
   }

   /** Returns true if this tag needs an object definition */
   public boolean needsObject() {
      if (tagObject != null)
         return true;

      Template template = getEnclosingTemplate();
      if (template == null || tagName == null)
         return false;

      // When we are generating the server, if this says only put this tag on the client, we just omit the object.
      // If we are doing the client though, we need the object to tell us this is a server tag (so we include it properly when we render the parent)
      if (execOmitObject())
         return false;

      // Annotation layers do not generate types - they can be used in extends though
      Layer templLayer = template.getLayer();
      if (templLayer != null && templLayer.annotationLayer)
         return false;

      boolean needsObject = getElementId() != null || getAttribute("extends") != null || getAttribute("tagMerge") != null || getDynamicAttribute() != null || isRepeatElement() || getAttribute("cache") != null;

      // When not part of a top-level tag, we currently can't define the object structure.  We'll have to treat this case as elements having no state - just do normal string processing on the templates and print an error
      // if any features are used that are not compatible with that behavior.  To support normal tag objects here, we could use inner types which are created, output and then destroyed
      // TODO: Do we need to do anything to tags nested inside of TemplateStatements?  I.e. those that would be theoretically created/destroyed in the outputBody method.  We could do it perhaps
      // with inner types and an inline-destroy method?
      if (!getContextSupportsObjects()) {
         if (!nestedTagsInStatements) {
            if (needsObject) {
               displayError("Tag object is inside of a statement or expression - this case is not yet supported: ");
            }
            return false;
         }
      }

      // TODO: should visible be here?  We also can handle it if there's no object right?
      return needsObject || getAttribute("visible") != null || getSpecifiedExtendsTypeDeclaration() != null;
   }

   public boolean selfClosed() {
      return selfClose != null && selfClose;
   }

   public Element[] getAllChildTagsWithName(String name) {
      Element parentTag = getEnclosingTag();
      Element[] parentsChildren;
      Template template = getEnclosingTemplate();
      if (parentTag != null) {
         parentsChildren = parentTag.getAllChildTagsWithName(name);
      }
      else
         parentsChildren = template.getChildTagsWithName(name);

      Element[] thisChildren = getInheritedChildTagsWithName(name);

      if (parentsChildren == null)
         return thisChildren;

      if (thisChildren == null)
         return parentsChildren;

      ArrayList<Element> res = new ArrayList<Element>();
      res.addAll(Arrays.asList(parentsChildren));
      res.addAll(Arrays.asList(thisChildren));
      return res.toArray(new Element[res.size()]);
   }

   public Element[] getInheritedChildTagsWithName(String name) {
      Element[] thisTags = getChildTagsWithName(name);

      MergeMode bodyMerge = getBodyMergeMode();
      if (bodyMerge == MergeMode.Replace) {
         return thisTags;
      }

      Element prevElement = getDerivedElement();
      Element extElement = getExtendsElement();
      Element[] implElements = getImplementsElements();
      ArrayList<Element> res;
      if (prevElement != null || extElement != null || implElements != null) {
         Element[] prevTags = prevElement == null ? null : prevElement.getInheritedChildTagsWithName(name);
         Element[] extTags = extElement == null ? null : extElement.getInheritedChildTagsWithName(name);

         if (thisTags == null) {
            if (extTags == null)
               return prevTags;
            else if (prevTags == null)
               return extTags;
            else {
               res = new ArrayList<Element>();
               res.addAll(Arrays.asList(extTags));
               res.addAll(Arrays.asList(prevTags));
            }
            return res.toArray(new Element[res.size()]);
         }

         if (bodyMerge == MergeMode.Merge) {
            // TODO: any situation in which we need to merge these?
            return thisTags; // Return the most specific version for this name - this tags
         }
         else {
            res = new ArrayList<Element>();
            if (bodyMerge == MergeMode.Append) {
               if (implElements != null) {
                  for (Element implElem:implElements) {
                     Element[] implTags = implElem.getInheritedChildTagsWithName(name);
                     if (implTags != null)
                        res.addAll(Arrays.asList(implTags));
                  }
               }
               if (extTags != null)
                  res.addAll(Arrays.asList(extTags));
               if (prevTags != null)
                  res.addAll(Arrays.asList(prevTags));
               res.addAll(Arrays.asList(thisTags));
            }
            else { // Prepend
               res.addAll(Arrays.asList(thisTags));
               if (prevTags != null)
                  res.addAll(Arrays.asList(prevTags));
               if (extTags != null)
                  res.addAll(Arrays.asList(extTags));
               if (implElements != null) {
                  for (Element implElem:implElements) {
                     Element[] implTags = implElem.getInheritedChildTagsWithName(name);
                     if (implTags != null)
                        res.addAll(Arrays.asList(implTags));
                  }
               }
            }
         }
         return res.toArray(new Element[res.size()]);
      }
      return thisTags;
   }

   public Element[] getChildTagsWithName(String name) {
      ArrayList<Element> res = null;

      res = addChildTagsWithName(res, hiddenChildren, name);
      res = addChildTagsWithName(res, children, name);

      if (res == null)
         return null;
      return res.toArray(new Element[res.size()]);
   }

   public Element[] getChildTags() {
      ArrayList<Element> res = new ArrayList<Element>();
      addAllTags(res, hiddenChildren);
      addAllTags(res, children);
      return res.toArray(new Element[res.size()]);
   }

   private void addAllTags(List<Element> res, List<Object> childList) {
      if (childList != null) {
         for (Object child:childList)
            if (child instanceof Element)
               res.add((Element) child);
      }
   }

   private static ArrayList<Element> addElementChild(Element child, ArrayList<Element> res, String name) {
      if (child.getRawObjectName().equals(name)) {
         if (res == null) {
            res = new ArrayList<Element>();
         }
         res.add(child);
      }
      return res;
   }

   public static ArrayList<Element> addChildTagsWithName(ArrayList<Element> res, SemanticNodeList<Object> childList, String name) {
      if (childList != null) {
         for (Object obj:childList) {
            if (obj instanceof Element) {
               Element child = (Element) obj;
               res = addElementChild(child, res, name);
            }
            else if (nestedTagsInStatements && obj instanceof TemplateStatement) {
               TemplateStatement ts = (TemplateStatement) obj;
               ArrayList<Object> subTags = new ArrayList<Object>();
               ts.addChildBodyStatements(subTags);
               for (Object st:subTags) {
                  if (st instanceof Element) {
                     res = addElementChild((Element) st, res, name);
                  }
               }
            }
         }
      }
      return res;
   }

   public String getDefaultObjectName() {
      String lowerTagName = lowerTagName();
      /** There's a property "style" on the Element so using "style" as the default object name causes problems. */
      if (lowerTagName.equals("style"))
         return "styleTag";
      return lowerTagName;
   }

   public String getRawObjectName() {
      String elementId = getElementId();
      if (elementId == null) {
         String defaultName = getDefaultObjectName();
         // Special case - we want to use HtmlPage as the base class for the html page when it is a root level element.  This lets us customize the root element type differently than an inner type - e.g. put a URL on it.
         //if (lowerTagName.equals("html") && getEnclosingTag() == null)
         //   lowerTagName = "htmlPage";
         return defaultName;
      }
      return  elementId;
   }

   /** If needsObject is true, the object name to use for this tag.  The rules are: name attribute (if set).  if id is set - either id or id0, id1, etc. if this id is not unique at this tag level.  If neither name nor id, then tagName or tagName0, tagName1  If the tag is a child of a tag which does not need the object, that tag's object name is prepended onto this one. */
   public String getObjectName() {
      if (cachedObjectName != null)
         return cachedObjectName;

      String rawObjName = getRawObjectName();

      Element parentTag = getEnclosingTag();
      String parentPath = "";
      Element curParent = parentTag;

      if (curParent != null && !curParent.needsObject()) {
         String parentName = curParent.getObjectName();
         // For p and a tags, do not capitalize.  Bean naming conventions get confused with a one letter camel cased abbreviation
         // and also do not capitalize for other tags.  Object names should always be lower case by convention.  If we have it upper
         // case, we refer to the property as Div.outputTag but it needs to be div.outputTag.
         //if (parentName.length() > 1)
         //   parentName = CTypeUtil.capitalizePropertyName(curParent.getObjectName());
         parentPath = parentName + parentPath;
         //curParent = curParent.getEnclosingTag();
      }
      //if (parentPath.length() > 0)
      //   parentPath += "_";

      Element[] peers;
      if (parentTag == null) {
         Template parentTemplate = getEnclosingTemplate();
         peers = parentTemplate.getChildTagsWithName(rawObjName);
      }
      else {
         peers = parentTag.getAllChildTagsWithName(rawObjName);
      }

      MergeMode m = getTagMergeMode();

      if (m == MergeMode.Replace)
         return cachedObjectName = (parentPath + rawObjName);

      if (peers != null) {
         if (peers.length == 1 && m == MergeMode.Merge)
            return cachedObjectName = (parentPath + rawObjName);

         for (int i = 0; i < peers.length; i++) {
            Element peer = peers[i];
            if (peer == this)
               return cachedObjectName = (parentPath + rawObjName + i);
         }
         System.err.println("*** Did not find tag in peer list");
      }
      else {
         System.out.println("*** Error: can't find tag under parent");
      }
      // This at least happens when editing an invalid schtml model
      cachedObjectName = rawObjName;
      return rawObjName;
   }

   public void removeAttribute(String name) {
      if (attributeList == null)
         return;

      for (int i = 0; i < attributeList.size(); i++) {
         Attr att = attributeList.get(i);
         if (att.name.equals(name)) {
            attributeList.remove(i);
            break;
         }
      }
   }

   public Attr getAttribute(String name) {
      if (attributeList == null)
         return null;

      for (Attr att:attributeList) {
         if (att.name.equals(name))
            return att;
      }
      return null;
   }

   public boolean getBooleanAttribute(String name) {
      String res = getFixedAttribute(name);
      if (res == null)
         return false;
      return res.equalsIgnoreCase("true");
   }

   public String getElementId() {
      Attr idAttr = getAttribute("id");
      Attr nameAttr = getAttribute("name");
      if (id != null) { // Explicitly named id - for example, the template file will rename the root element to match the file name if there's only one element in some cases
         if (idAttr != null || nameAttr != null) {
            Attr attr = idAttr != null ? idAttr : nameAttr;
            if (PString.isString(attr.value)) {
               if (!attr.value.toString().equals(id))
                  attr.displayError("Tag " + tagName + " has id/name: " + attr.value + " but a value of: " + id + " is expected for: ");
            }
         }
         return id;
      }
      if (idAttr != null && nameAttr != null)
         nameAttr.displayError("Invalid tag definition: use only one of id or name: " + idAttr.value + " and " + nameAttr.value);
      else if (idAttr != null || nameAttr != null) {
         Attr useAttr = idAttr != null ? idAttr : nameAttr;
         Object val = useAttr.value;
         if (PString.isString(val)) {
            String idStr = val.toString();
            if (idStr.equals(ALT_ID)) { // For the 'alt' element which is a child of another tag, use _alt as the suffix
               return getAltId();
            }
            return CTypeUtil.escapeIdentifierString(idStr);
         }
         else
            useAttr.displayError("Expression for name/id attributes not supported: " + val);
      }
      return null;
   }

   private String getAltId() {
      Element par = getEnclosingTag();
      if (par != null) {
         String parId = par.getElementId();
         return parId + "_" + ALT_ID;
      }
      return getFixedAttribute("id");
   }

   public String lowerTagName() {
      if (tagName == null)
         System.out.println("*** Missing tag name!");
      return tagName.toLowerCase();
   }

   public boolean needsId() {
      // If there's no manually assigned id and we are not extending another tag (which would already supply the id)
      return tagObject != null && getElementId() == null;
   }

   public boolean needsAutoId(String id) {
      // If there's no manually assigned id and we are not extending another tag (which would already supply the id)
      return needsId() && !inheritsId(id);
   }

   public boolean inheritsId(String id) {
      Element cur = this;
      String elemId;
      if (id == null)
         id = getObjectName();
      do {
         cur = cur.getExtendsElement();
         if (cur != null && !cur.isInitialized()) {
            Template templ = cur.getEnclosingTemplate();
            ParseUtil.initComponent(templ);
         }
         // Eliminate redundant setId calls in the generated code.  Look for the first tag in our hierarchy which specified the id
         // If it matches, return it.  This is particularly important now because unique elements like head, body need to have a fixed id so if you
         // allocate two of them with the same tag name, you end up with the wrong id.
         if (cur != null && cur.specifiedId) {
            elemId = cur.tagObject.typeName;
            if (elemId != null)
               return elemId.equals(id);
         }
      } while (cur != null);
      cur = this;
      do {
         cur = cur.getDerivedElement();
         if (cur != null && !cur.isInitialized())
            ParseUtil.initComponent(cur.getEnclosingTemplate());
         if (cur != null && cur.specifiedId) {
            elemId = cur.tagObject.typeName;
            if (elemId != null)
               return elemId.equals(id);
         }
      } while (cur != null);
      return false;
   }

   public static final int doOutputStart = 1;
   public static final int doOutputBody = 2;
   public static final int doOutputEnd = 4;
   public static final int doOutputAll = doOutputStart | doOutputBody | doOutputEnd;


   String getDynamicAttribute() {
      if (tagName != null) {
         String tagSpecial = null;
         // Convert simple <a clickEvent="..."> to href="#" onclick="return false;" unless those attributes are overridden.  This provides better default semantics - suppressing
         // the page navigation and changing cursor for this being a link.
         if (tagName.equalsIgnoreCase("a") && getAttribute("href") == null && (getAttribute("clickCount") != null || getAttribute("clickEvent") != null))
            tagSpecial = " href=\"#\"";
         // Convenience - if you are handling events don't do the default submission.  TODO: It may be better here to call event.preventDefault rather than return false.  We don't want to navigate but should not disable all other event handlers.
         // TODO: this is done for submitEvent below
         else if (tagName.equalsIgnoreCase("form") && getAttribute("submitCount") != null && getAttribute("onsubmit") == null)
            tagSpecial = " onsubmit=\"return false;\"";
         // A convenient for the programmer - if they omit the type, default it to css
         else if (tagName.equalsIgnoreCase("style") && getAttribute("type") == null)
            tagSpecial = " type=\"text/css\"";

         // This installs the rule that if you use clickEvent handler that you by default prevent the default handler of that event on the underlying DOM node (unless the
         // tag explicitly declares a handler for that attribute of course).   I'm not sure this is 100% needed for the general case but helps with forms, and anchors.
         String res = tagSpecial;
         for (HTMLElement.EventType eventType:HTMLElement.EventType.values()) {
            if (eventType.getPreventDefault()) {
               String attName = eventType.getAttributeName();
               if (getAttribute(eventType.getEventName()) != null && getAttribute(attName) == null) {
                  if (res == null)
                     res = "";
                  // TODO: should we do event.preventDefault here?
                  res += " " + attName + "=\"return false;\"";
               }
            }
         }
         return res;
      }
      return null;
   }

   public StringBuilder addExtraAttributes(StringBuilder str, SemanticNodeList<Expression> strExprs) {
      // This logic cannot be in the A and Form subclasses because when we parse an HTML file, the specific classes are not
      // created.  We need this during the code generation phase.
      String toAdd = getDynamicAttribute();
      if (toAdd != null) {
         str.append(toAdd);
      }
      return str;
   }

    /*
     * This method is called when you are in the middle of an expression context i.e. String x = %> and then run into an html tag in that context.
     * It processes the tag and returns its contents as an expression to evaluate those contexts.  for a tag object, this means calling the output method.
     * For a non-object tag, we append it as a bunch of expressions.
     */
   public Expression getOutputExpression() {
      // Not processing this element for this template e.g. a server-only object which is being generated for the client
      boolean inactive = execOmitObject();

      boolean needsObject = needsObject();
      if (needsObject) {
         // TODO: Note: this case is not reached because the getEnclosingContext method here returns GlueExpression.  We do not have a way to dig into the glue expressions and glue statements that are stored under a tag to create these sub-objects.  So these get treated like strings in their parent.
         if (!isAbstract()) {
            String objName = isRepeatElement() ? getRepeatObjectName() : getObjectName();
            return IdentifierExpression.createMethodCall(null, objName, "output");
         }
      }
      else {
         StringBuilder str = new StringBuilder();
         SemanticNodeList<Expression> strExprs = new SemanticNodeList<Expression>();

         if (!inactive) {
            str.append("<");
            str.append(lowerTagName());
         }

         ArrayList<Attr> attList = getInheritedAttributes();
         if (attList != null) {
            for (Attr att:attList) {
               if (isHtmlAttribute(att.name)) { // Do we need to draw thie attribute
                  Expression outputExpr = att.getOutputExpr();
                  boolean isConstant = outputExpr == null || outputExpr instanceof StringLiteral;
                  if (!inactive && (!staticContentOnly || isConstant)) {
                     if (isBooleanAttribute(att.name) && outputExpr != null && !(outputExpr instanceof StringLiteral)) {
                        if (str.length() > 0) {
                           strExprs.add(StringLiteral.create(str.toString()));
                           str = new StringBuilder();
                        }
                        Expression boolExpr = ParenExpression.create(QuestionMarkExpression.create((Expression) outputExpr.deepCopy(ISemanticNode.CopyNormal, null), StringLiteral.create(" " + mapAttributeToProperty(att.name)), StringLiteral.create("")));
                        strExprs.add(boolExpr);
                     }
                     else {
                        str.append(" ");
                        str.append(att.name);
                        str.append("=");
                        if (outputExpr == null || outputExpr instanceof StringLiteral) {
                           if (att.isString()) {
                              str.append("\'");
                              str.append(att.getOutputString());
                              str.append("\'");
                           }
                           else {
                              System.out.println("*** unrecognized type in attribute list");
                           }
                        }
                        else {
                           str.append("\"");
                           strExprs.add(StringLiteral.create(str.toString()));
                           str = new StringBuilder();
                           str.append("\"");
                           strExprs.add((Expression) outputExpr.deepCopy(ISemanticNode.CopyNormal, null));
                        }
                     }
                  }
                  else if (att.value instanceof Expression) // this expression has been disabled due to being out of scope
                     ((Expression) att.value).inactive = true;
               }
            }
         }
         str = addExtraAttributes(str, strExprs);

         if (!inactive) {
            if (needsId() && !isSingletonTag()) {
               str.append(" id='");
               strExprs.add(StringLiteral.create(str.toString()));
               strExprs.add(IdentifierExpression.createMethodCall(new SemanticNodeList(), "getId"));
               str = new StringBuilder();
               str.append("'");
            }
            if (!needsBody && (selfClose != null && selfClose))
               str.append("/>");
            else {
               str.append(">");
            }
            if (strExprs.size() > 0) {
               strExprs.add(StringLiteral.create(str.toString()));
               str = new StringBuilder();
            }
         }

         if (children != null) {
            if (str.length() > 0 && !inactive) {
               strExprs.add(StringLiteral.create(str.toString()));
               str = new StringBuilder();
            }

            for (Object child:children) {
               if ((staticContentOnly || inactive) && child instanceof Expression) {
                  Expression childExpr = (Expression) child;
                  childExpr.inactive = true;
               }
               else if (child instanceof Expression) {
                  if (str.length() > 0)
                     strExprs.add(StringLiteral.create(str.toString()));
                  strExprs.add((Expression) child);
               }
               else if (PString.isString(child)) {
                  str.append(child.toString());
               }
               else if (child instanceof Element) {
                  Element childElem = (Element) child;
                  Expression expr = childElem.getOutputExpression();
                  if (expr != null) {
                     if (str.length() > 0)
                        strExprs.add(StringLiteral.create(str.toString()));
                     strExprs.add(expr);
                  }
               }
               else {
                  // TODO: what other cases show up here?  We are inside of an expression context and run into something other than an element, expression or string?
               }
            }
         }
         if ((selfClose == null || !selfClose || needsBody) && closeTagName != null && !inactive) {
            str.append("</");
            str.append(closeTagName);
            str.append(">");
         }

         if (str.length() > 0)
            strExprs.add(StringLiteral.create(str.toString()));

         if (strExprs.size() == 1) {
            return strExprs.get(0);
         }
         else if (strExprs.size() > 1) {
            return BinaryExpression.createMultiExpression(strExprs.toArray(new Expression[strExprs.size()]), "+");
         }
      }
      return null;
   }

   /** This method gets called from two different contexts and for tags which are both dynamic and static so it's
    * a little confusing.
    *
    * For "OutputAll" when this tag needs an object, it is added to the parent's outputTag method
    *      objectName.outputTag(sb);
    *
    * For static tags (deprecated), we add the expressions needed to render this tag as content in the parent method.
    *
    * For needsObject tags, we also call this method to generate the outputStart and outputBody methods in separate passes.  The content for the object
    * then gets added to the outputStart and outputBody methods of the generated object for this tag.
    * */
   public int addToOutputMethod(TypeDeclaration parentType, BlockStatement block, Template template, int doFlags, SemanticNodeList<Object> uniqueChildren, int initCt, boolean statefulContext) {
      // Not processing this element for this template e.g. a server-only object which is being generated for the client
      boolean inactive = execOmitObject();

      int ix = initCt;

      boolean needsObject = needsObject();
      // Here we process a reference to the object
      if (doFlags == doOutputAll && needsObject) {
         if (!isAbstract() && !StringUtil.equalStrings(getFixedAttribute("id"), ALT_ID)) {
            String objName = isRepeatElement() ? getRepeatObjectName() : getObjectName();
            Statement st = IdentifierExpression.createMethodCall(getOutputArgs(template), this == template.rootType ? null : objName, "outputTag");
            // The original source statement for the outputTag call in the parent should be the element itself for breakpoints in the debugger.
            st.fromStatement = this;
            template.addToOutputMethod(block, st);
         }
      }
      else {
         StringBuilder str = new StringBuilder();
         SemanticNodeList<Expression> strExprs = new SemanticNodeList<Expression>();
         String methSuffix = doFlags != doOutputStart ? "Body" : "StartTag";

         if ((doFlags & doOutputStart) != 0) {
            if (!inactive) {
               str.append("<");
               str.append(lowerTagName());
            }

            ArrayList<Attr> attList = getInheritedAttributes();
            if (attList != null) {
               for (Attr att:attList) {
                  if ((isHtmlAttribute(att.name))) { // Do we need to draw this attribute?
                     if (att.isReverseOnly())
                        continue; // draw nothing for =: bindings that happen to be on HTML attributes
                     Expression outputExpr = att.getOutputExpr();
                     boolean isConstant = outputExpr == null || outputExpr instanceof StringLiteral;
                     if (!inactive && (!staticContentOnly || isConstant)) {
                        if (isBooleanAttribute(att.name) && outputExpr != null && !(outputExpr instanceof StringLiteral)) {
                           if (str.length() > 0) {
                              strExprs.add(StringLiteral.create(str.toString()));
                              str = new StringBuilder();
                           }
                           Expression boolExpr = ParenExpression.create(QuestionMarkExpression.create((Expression) outputExpr.deepCopy(ISemanticNode.CopyNormal, null), StringLiteral.create(" " + mapAttributeToProperty(att.name)), StringLiteral.create("")));
                           boolExpr.fromStatement = att;
                           strExprs.add(boolExpr);
                        }
                        else if (att.value != null) {
                           str.append(" ");
                           str.append(att.name);
                           str.append("=");
                           if (outputExpr == null || (outputExpr instanceof StringLiteral && StringUtil.equalStrings(att.op, "="))) {
                              if (att.isString()) {
                                 str.append("\'");

                                 if (att.name.equals("id") && needsObject) {
                                    Expression expr = StringLiteral.create(str.toString());
                                    expr.fromStatement = att;
                                    strExprs.add(expr);
                                    expr = IdentifierExpression.createMethodCall(new SemanticNodeList(), "getId");
                                    strExprs.add(expr);
                                    expr.fromStatement = att;
                                    str = new StringBuilder();
                                 }
                                 else {
                                    str.append(att.getOutputString());
                                 }
                                 str.append("\'");
                              }
                              else {
                                 System.err.println("*** unrecognized type in attribute list");
                              }
                           }
                           else {
                              str.append("\"");
                              Expression strExpr = StringLiteral.create(str.toString());
                              strExpr.fromStatement = att;
                              strExprs.add(strExpr);
                              str = new StringBuilder();
                              str.append("\"");
                              Expression outputExprCopy = (Expression) outputExpr.deepCopy(ISemanticNode.CopyNormal, null);
                              // If this is a comparison operator like foo != bar it needs to be wrapped to be combined with a + b + ...
                              if (outputExpr.needsParenWrapper())
                                 outputExprCopy = ParenExpression.create(outputExprCopy);
                              if (outputExprCopy.parentNode == null)
                                 outputExprCopy.parentNode = parentType;

                              Object exprType = outputExpr.getTypeDeclaration();
                              if (exprType != null && ModelUtil.isString(exprType)) {
                                 SemanticNodeList<Expression> escArgs = new SemanticNodeList<Expression>();
                                 escArgs.add(outputExprCopy);
                                 Expression escExpr = IdentifierExpression.createMethodCall(escArgs, "escAtt");
                                 escExpr.fromStatement = att;
                                 strExprs.add(escExpr);
                              }
                              else
                                 strExprs.add(outputExprCopy);
                           }
                        }
                     }
                     else if (att.value instanceof Expression) // this expression has been disabled due to being out of scope
                        ((Expression) att.value).inactive = true;
                  }
                  else if (att.valueProp == null && !isBehaviorAttribute(att.name)) {
                     att.unknown = true;
                     displayWarning("Unknown attribute: ", att.name, " for tag: ");
                  }
               }
            }
            str = addExtraAttributes(str, strExprs);

            if (!inactive) {
               Expression texpr;
               if (needsId() && !isSingletonTag()) {
                  str.append(" id='");
                  texpr = StringLiteral.create(str.toString());
                  texpr.fromStatement = this;
                  strExprs.add(texpr);
                  texpr = IdentifierExpression.createMethodCall(new SemanticNodeList(), "getId");
                  strExprs.add(texpr);
                  str = new StringBuilder();
                  str.append("'");
               }
               if (!needsBody && (selfClose != null && selfClose))
                  str.append("/>");
               else {
                  str.append(">");
               }
               if (strExprs.size() > 0) {
                  texpr = StringLiteral.create(str.toString());
                  texpr.fromStatement = this;
                  strExprs.add(texpr);
                  str = new StringBuilder();
               }
            }
         }

         if (uniqueChildren != null && (doFlags & doOutputBody) != 0) {
            Expression texpr;
            if (str.length() > 0 && !inactive) {
               texpr = StringLiteral.create(str.toString());
               texpr.fromStatement = this;
               strExprs.add(texpr);
               str = new StringBuilder();
            }
            if (strExprs.size() > 0) {
               for (Expression strExpr:strExprs) {
                  ix = template.addTemplateDeclToOutputMethod(parentType, block, strExpr, false, "Body", ix, this, this, statefulContext, false);
               }
               strExprs = new SemanticNodeList<Expression>();
            }

            for (Object child:uniqueChildren) {
               if ((staticContentOnly || inactive) && child instanceof Expression) {
                  Expression childExpr = (Expression) child;
                  childExpr.inactive = true;
               }
               else
                 ix = template.addTemplateDeclToOutputMethod(parentType, block, child, true, "Body", ix, this, this, statefulContext, true);
            }
         }
         if ((selfClose == null || !selfClose || needsBody) && closeTagName != null && (doFlags & doOutputEnd) != 0 && !inactive) {
            str.append("</");
            str.append(closeTagName);
            str.append(">");
         }

         if (str.length() > 0) {
            Expression texpr = StringLiteral.create(str.toString());
            texpr.fromStatement = this;
            strExprs.add(texpr);
         }

         Expression tagExpr = null;
         if (strExprs.size() == 1) {
            tagExpr = strExprs.get(0);
         }
         else if (strExprs.size() > 1) {
            tagExpr = BinaryExpression.createMultiExpression(strExprs.toArray(new Expression[strExprs.size()]), "+");
         }

         /** When there's no object for a tag that has visible="=..." we wrap the tag's content in a ternary expression */
         Attr visAtt;
         if (!needsObject && (visAtt = getAttribute("visible")) != null) {
            Expression outExpr = visAtt.getOutputExpr();
            if (outExpr == null) {
               visAtt.displayError("Tag visible attribute must be a valid expression: ");
            }
            else
               tagExpr = QuestionMarkExpression.create(outExpr, tagExpr, StringLiteral.create(""));
         }

         if (tagExpr != null)
            ix = template.addTemplateDeclToOutputMethod(parentType, block, tagExpr, false, methSuffix, ix, this, this, statefulContext, false);
      }
      return ix;
   }

   public SemanticNodeList<Expression> getOutputArgs(Template template) {
      return template.getDefaultOutputArgs();
   }

   public JavaType getExtendsType() {
      Object tagType = getExtendsTypeDeclaration();
      if (tagType == null)
         return null;
      return convertExtendsTypeToJavaType(tagType, true);
   }

   public JavaType convertExtendsTypeToJavaType(Object tagType, boolean addTypeVar) {
      List<?> tps = ModelUtil.getTypeParameters(tagType);

      ClassType ct = (ClassType) ClassType.create(ModelUtil.getTypeName(tagType));

      // We will optionally supply a parameter type to the extends type we create.  Only if the
      // base class has one.
      if (addTypeVar && tps != null && tps.size() == 1 && ModelUtil.isTypeVariable(tps.get(0))) {
         Object repeatElemType = getRepeatElementType();
         if (repeatElemType != null && repeatElemType != Object.class) {
            SemanticNodeList typeArgs = new SemanticNodeList(1);
            typeArgs.add(ClassType.create(ModelUtil.getTypeName(repeatElemType)));
            ct.setResolvedTypeArguments(typeArgs);
         }
         else {
            if (repeatWrapper == null) {
               SemanticNodeList typeArgs = new SemanticNodeList(1);
               typeArgs.add(ClassType.create("RE_" + getObjectName()));
               ct.setResolvedTypeArguments(typeArgs);
            }
         }
      }
      return ct;
   }

   public Object getDeclaredExtendsTypeDeclaration() {
      String extStr = getFixedAttribute("extends");
      if (extStr != null) {
         Object res = findType(extStr, this, null);
         if (res == null) {
            JavaModel model = getJavaModel();
            if (model != null) {
               res = model.findTypeDeclaration(extStr, true, false);
               if (res == null) {
                  Attr attr = getAttribute("extends");
                  attr.displayError("No extends type: ", extStr, " for tag: ");
                  res = findType(extStr, this, null);
                  res = model.findTypeDeclaration(extStr, true, false);
               }
            }
         }
         return res;
      }
      return null;
   }

   private Object getSpecifiedExtendsTypeDeclaration() {
      Object res = getDeclaredExtendsTypeDeclaration();
      if (res != null)
         return res;
      return getDefaultExtendsTypeDeclaration(false);
   }

   private static HashSet<String> verboseBaseTypeNames = null;

   private static HashMap<String,String> classNameToTagNameIndex = new HashMap<String,String>();

   public static Class getSystemTagClassForTagName(String tagName) {
       Class res = systemTagClassMap.get(tagName);
       if (res == null)
          res = HTMLElement.class;
       return res;
   }

   public Object getDefaultExtendsTypeDeclaration(boolean processable) {
      if (defaultExtendsType != null)
         return defaultExtendsType;
      LayeredSystem sys = getLayeredSystem();
      ArrayList<LayeredSystem.TagPackageListEntry> tagPackageList = sys == null ? new ArrayList<LayeredSystem.TagPackageListEntry>() : sys.tagPackageList;
      JavaModel model = getJavaModel();
      String thisPackage = model == null ? null : model.getPackagePrefix();
      int tagPackageStart = 0;
      Layer modelLayer = model != null ? model.getLayer() : null;

      // If we are in a tag-class that's already a top-level class and looking up from a package that's already in the tag package list, look starting from where this guy is.
      // If we are looking up an inner type that happens to be in the same package, we still need to pick up the most specific version of that type or layered inheritance does nto work.
      if (thisPackage != null && getEnclosingTag() == null) {
         int ct = 0;
         for (LayeredSystem.TagPackageListEntry tagPackage:tagPackageList) {
            if (thisPackage.equals(tagPackage.name)) {
               tagPackageStart = ct+1;
               break;
            }
            ct++;
         }
      }
      for (int i = tagPackageStart; i < tagPackageList.size(); i++) {
         LayeredSystem.TagPackageListEntry tagPackage = tagPackageList.get(i);

         Layer tagPackageLayer = tagPackage.layer;

         // Right now, we add to this tagPackageList for both activated and inactive layers.  So make sure that this reference lines up
         // with the right one
         if (tagPackageLayer != null && modelLayer != null && tagPackageLayer.activated != modelLayer.activated)
            continue;

         // Only match against tag packages which we directly extend
         // TODO: for activated layers, should we inherit any tag package?  It can be confusing when you see html.schtml in the stack below you, and the layer
         // is really extended by through an excluded layer.   Maybe we should take the 'allBaseLayers' and use that in the layer so that we pick up even excluded
         // dependencies here?
         if (tagPackageLayer == null || modelLayer == null || modelLayer.extendsLayer(tagPackageLayer) || modelLayer == tagPackageLayer) {
            String tagName = lowerTagName();
            String origTagName = tagName;
            Template enclTemplate = getEnclosingTemplate();
            if (tagName.equals("html") && enclTemplate.singleElementType)
               tagName = "htmlPage";
            // No longer capitalizing.  Object names should be lower case
            String typeName = CTypeUtil.prefixPath(tagPackage.name, CTypeUtil.capitalizePropertyName(tagName));
            Template templ = getEnclosingTemplate();
            // There's the odd case where the template itself is defining the default for this tag.  Skipping this match because it does not make sense.
            if (templ != null && typeName.equals(templ.getModelTypeName()))
               continue;
            // When we are compiling, need to pick up the src type declaration here so that we can get at the corresponding element to inherit tags.
            Object res = sys == null ? null : sys.getTypeDeclaration(typeName, true, modelLayer, model != null && model.isLayerModel);
            if (res == null) // But since the default classes are compiled classes, if we can't find a src one look for the compiled type
               res = sys.getTypeDeclaration(typeName, false, modelLayer, model != null && model.isLayerModel);

            if (res != null && (!processable || ModelUtil.isProcessableType(res))) {
               String oldTagName = classNameToTagNameIndex.put(typeName, origTagName);
               if (oldTagName != null && !oldTagName.equals(origTagName))
                  System.err.println("*** Error - registered same typeName: " + typeName + " for two different tag names: " + tagName + " and " + oldTagName);
               if (res instanceof TypeDeclaration) {
                  TypeDeclaration resTD = (TypeDeclaration) res;
                  if (modelLayer == null || resTD.getLayer() == null || (resTD.getLayer() != null && resTD.getLayer().activated != modelLayer.activated))
                     System.out.println("*** Activated layer mismatch");
               }
               defaultExtendsType = res;
               if (sys.options.verbose) {
                  if (verboseBaseTypeNames == null)
                     verboseBaseTypeNames = new HashSet<String>();
                  if (!verboseBaseTypeNames.contains(typeName)) {
                     Layer tagLayer = ModelUtil.getLayerForType(sys, res);
                     System.out.println("Using: " + typeName + " for HTML tag: " + tagName + (tagLayer == null ? " (system default)" : " defined in layer: " + tagLayer));
                     verboseBaseTypeNames.add(typeName);
                  }
               }
               return res;
            }
         }
      }
      return null;
   }

   public Object getExtendsTypeDeclaration() {
      if (extendsTypeDecl != null)
         return extendsTypeDecl;
      Object specType = getSpecifiedExtendsTypeDeclaration();
      if (specType != null)
         return extendsTypeDecl = specType;
      // Content tags default to HTMLElements
      return extendsTypeDecl = HTMLElement.class;
   }

   public TypeDeclaration getEnclosingType() {
      Element tag = getEnclosingTag();
      if (tag != null && tag.tagObject != null)
         return tag.tagObject;

      Template templ = getEnclosingTemplate();
      if (templ == null) {
         return null;
      }
      return (TypeDeclaration) templ.rootType;
   }

   private void initAttrExprs() {
      SemanticNodeList<Attr> attList = getInheritedAttributes();
      if (attList != null) {
         for (Attr att:attList) {
            att.initAttribute(this);
         }
      }
   }

   void invalidateParent() {
      Element element = getEnclosingTag();
      if (element != null)
         element.childInvalidated();
   }

   public void invalidateStartTag() {
      if (startTagValid) {
         startTagValid = false;
         invalidateParent();
      }
   }

   public void invalidateBody() {
      if (bodyValid) {
         markBodyValid(false);
         invalidateParent();
      }
   }

   public void childInvalidated() {
      if (bodyValid) {
         bodyValid = false; // Our body is no longer valid because it includes the child whose body changed.
         invalidateParent();
      }
   }

   public void invalidateRepeatTags() {
      if (repeatTagsValid) {
         repeatTagsValid = false;
         invalidateParent();
      }
   }

   public Element getDerivedElement() {
      if (modifyType != null && modifyType instanceof TypeDeclaration) {
         TypeDeclaration modifyTD = (TypeDeclaration) modifyType;
         if (modifyTD.element != null)
            return modifyTD.element;
      }
      if (tagObject != null) {
         Object derivedType = tagObject.getDerivedTypeDeclaration();
         if (derivedType instanceof BodyTypeDeclaration) {
            BodyTypeDeclaration derivedTD = (BodyTypeDeclaration) derivedType;
            do {
               Template enclTemplate = derivedTD.getEnclosingTemplate();
               if (enclTemplate != null) {
                  if (!enclTemplate.isInitialized())
                     enclTemplate.init();
                  return ((TypeDeclaration) derivedType).element;
               }
               else {
                  derivedType = derivedTD.getDerivedTypeDeclaration();
                  if (derivedType instanceof BodyTypeDeclaration)
                     derivedTD = (BodyTypeDeclaration) derivedType;
                  else
                     return null;
               }
            } while (true);
         }
      }
      return null;
   }

   public Element getExtendsElement() {
      Object derivedType = getExtendsTypeDeclaration();
      if (derivedType instanceof BodyTypeDeclaration) {
         BodyTypeDeclaration derivedTD = (BodyTypeDeclaration) derivedType;
         do {
            Template enclTemplate = derivedTD.getEnclosingTemplate();
            if (enclTemplate != null) {
               if (!enclTemplate.isInitialized())
                  enclTemplate.init();
               TypeDeclaration dtd = (TypeDeclaration) derivedTD;
               if (dtd.element != null)
                  return dtd.element;
            }

            // In the search for an element we inherit through an extends operator, once we've crossed onto a new type
            // we need to check the derived type first, before we move to the next 'extends'.  It's left up to that
            // type to take into account is 'extends' if there is one.
            Object extendsType = derivedTD.getExtendsTypeDeclaration();
            derivedType = derivedTD.getDerivedTypeDeclaration();
            if (derivedType instanceof BodyTypeDeclaration)
               derivedTD = (BodyTypeDeclaration) derivedType;
            else {
               derivedType = ModelUtil.resolveSrcTypeDeclaration(getLayeredSystem(), derivedType, false, true, derivedTD.getLayer());
               if (derivedType instanceof BodyTypeDeclaration && derivedType != derivedTD) {
                  derivedTD = (BodyTypeDeclaration) derivedType;
               }
               else
                  derivedTD = null;
            }
            if (extendsType != derivedType && extendsType != null && derivedTD != null) {
               extendsType = ModelUtil.resolveSrcTypeDeclaration(getLayeredSystem(), extendsType, false, true, derivedTD.getLayer());
               if (extendsType instanceof BodyTypeDeclaration) {
                  derivedTD = (BodyTypeDeclaration) extendsType;
               }
            }
            if (derivedTD == null)
               return null;
         } while (true);
      }
      return null;
   }

   private ArrayList<Element> addAllImplementsElements(ArrayList<Element> res, Object type, boolean addToThis) {
      boolean processed = false;
      if (type != null) {
         if (type instanceof TypeDeclaration && addToThis) {
            TypeDeclaration td = (TypeDeclaration) type;
            Template enclTemplate = td.getEnclosingTemplate();
            if (enclTemplate != null) {
               if (!enclTemplate.isInitialized())
                  enclTemplate.init();
               if (res == null)
                  res = new ArrayList<Element>();
               res.add(((TypeDeclaration) type).element);
               // The first template will merge any subsequent ones so here we just need to find the first in the type hierarchy.
               processed = true;
            }
         }
         // If there's no template on this type, keep searching the interface tree to see if one of those resolved to an annotation layer and a template
         if (!processed) {
            Object[] innerImplTypes = null;
            if (type instanceof TypeDeclaration) {
               TypeDeclaration td = (TypeDeclaration) type;
               // If there's an element associated with this type we need to inherit the implements attributes, which will be a superset of the interfaces on the type object.
               if (td.element != null) {
                  List<Object> tagImplTypes = td.element.getTagImplementsTypes();
                  if (tagImplTypes != null) {
                     innerImplTypes = tagImplTypes.toArray();
                  }
               }
            }

            if (innerImplTypes == null)
               innerImplTypes = ModelUtil.getImplementsTypeDeclarations(type);
            if (innerImplTypes != null) {
               for (Object innerImpl:innerImplTypes)
                  res = addAllImplementsElements(res, innerImpl, true);
            }
         }
      }
      return res;
   }

   public Element[] getImplementsElements() {
      ArrayList<Element> res = null;

      res = addAllImplementsElements(res, tagObject, false);
      if (res == null)
         return null;
      return res.toArray(new Element[res.size()]);
   }

   private boolean hasInvalidateBinding(String attName) {
      Element derived = getDerivedElement();
      if (derived != null) {
         Attr att = derived.getAttribute(attName);
         if (att != null)
            return true;
      }
      derived = getExtendsElement();
      if (derived != null) {
         Attr att = derived.getAttribute(attName);
         if (att != null)
            return true;
      }
      Element[] implElements = getImplementsElements();
      if (implElements != null) {
         for (Element impl:implElements) {
            Attr att = impl.getAttribute(attName);
            if (att != null)
               return true;
         }
      }
      return false;
   }

   public boolean isModifyingElement() {
      return modifyType != null || tagObject instanceof ModifyDeclaration;
   }

   public SemanticNodeList<Attr> getInheritedAttributes() {
      if (inheritedAttributes == null) {
         SemanticNodeList<Attr> resultAttributes = null;
         Element[] implElements = getImplementsElements();
         if (implElements != null) {
            for (Element impl:implElements) {
               SemanticNodeList<Attr> atts = impl.getInheritedAttributes();
               resultAttributes = mergeAttributeLists(resultAttributes, atts, impl, false);
            }
         }
         Element derived = getDerivedElement();
         if (derived != null) {
            SemanticNodeList<Attr> atts = derived.getInheritedAttributes();
            // If this element is modifying the derived element, we do want to inherit id and abstract.  But if not we do not want to inherit those attributes here.
            resultAttributes = mergeAttributeLists(resultAttributes, atts, derived, isModifyingElement());
         }
         Element extendsElem = getExtendsElement();
         if (extendsElem != null && extendsElem != derived) {
            SemanticNodeList<Attr> atts = extendsElem.getInheritedAttributes();
            resultAttributes = mergeAttributeLists(resultAttributes, atts, extendsElem, false);
         }
         if (attributeList != null) {
            if (resultAttributes == null)
               return attributeList;
            resultAttributes = mergeAttributeLists(resultAttributes, attributeList, this, true);
         }
         inheritedAttributes = resultAttributes == null ? null : resultAttributes;
      }
      return inheritedAttributes;
   }

   private int attributeIndexOf(SemanticNodeList<Attr> list, String name) {
      if (list == null)
         return -1;
      int ix = 0;
      for (Attr item:list) {
         if (item.name.equals(name))
            return ix;
         ix++;
      }
      return -1;
   }

   private SemanticNodeList<Attr> mergeAttributeLists(SemanticNodeList<Attr> resultAttributes, SemanticNodeList<Attr> atts, Element declaringTag, boolean allowInherited) {
      if (atts != null) {
         if (resultAttributes == null) {
            resultAttributes = new SemanticNodeList<Attr>();
            resultAttributes.parentNode = this;
         }

         for (Attr att:atts) {
            if (!allowInherited && !isInheritedAttribute(att.name)) {
               att.declaringTag = declaringTag;
               continue;
            }

            // Need to set the 'op' to see how we merge this guy
            att.initAttribute(declaringTag);

            // Reverse only bindings always get appended since they are additive
            boolean append = (att.op != null && att.op.equals("=:"));
            int ix = attributeIndexOf(resultAttributes, att.name);
            if (append) {
               ix = -1;
            }
            Attr attCopy = att.getDeclaringTag() == this ? att : att.deepCopy(ISemanticNode.CopyNormal, null);
            attCopy.declaringTag = declaringTag;
            if (ix == -1)
               resultAttributes.add(attCopy);
            else
               resultAttributes.set(ix, attCopy);
         }
      }
      return resultAttributes;
   }

   private boolean isInheritedAttribute(String name) {
      return !notInheritedAttributes.contains(name);
   }

   private boolean isInheritedAtt(Attr att) {
      Element derived = getDerivedElement();
      if (derived == null)
         return false;
      if (att.getDeclaringTag() != this) {
         // Reverse only bindings - have to assume they are inherited without matching the values.  Actually this test with
         // the enclosing tag may just be fine by itself?
         if (att.op.equals("=:"))
            return true;
         Attr derivedAtt = derived.getAttribute(att.name);
         if (derivedAtt != null) {
            if (DynUtil.equalObjects(derivedAtt.value, att.value)) {
               return true;
            }
         }
      }
      return false;
   }


   private int findChildInList(SemanticNodeList<Object> derivedChildren, String toFindObjName) {
      if (derivedChildren != null) {
         for (int de = 0; de < derivedChildren.size(); de++) {
            Object childObj = derivedChildren.get(de);
            if (childObj instanceof Element) {
               Element derivedTag = (Element) childObj;
               String derivedTagName = derivedTag.getObjectName();
               if (derivedTagName != null && derivedTagName.equals(toFindObjName)) {
                  return de;
               }
            }
         }
      }
      return -1;
   }

   private StringBuilder getInheritedPreTagContent() {
      Element derivedElem = getDerivedElement();
      StringBuilder res = null;
      if (derivedElem != null && derivedElem.getEnclosingTag() == null) {
         Template derivedTemplate = derivedElem.getEnclosingTemplate();

         res = derivedTemplate.preTagContent;

         if (res == null) {
            StringBuilder nextLevel = derivedElem.getInheritedPreTagContent();
            if (nextLevel != null) {
               res = nextLevel;

               // Do not merge the derived and extends pre-tag content.  Otherwise we get duplicate DOCTYPE's in some situations.
               if (nextLevel.length() > 0)
                  return res;
            }
         }
      }
      Element extElem = getExtendsElement();
      if (extElem != null && extElem != derivedElem && extElem.getEnclosingTag() == null) {
         StringBuilder extRes = extElem.getEnclosingTemplate().preTagContent;
         if (extRes == null) {
            StringBuilder nextLevel = extElem.getInheritedPreTagContent();
            if (nextLevel != null) {
               extRes = nextLevel;
            }
         }
         if (extRes != null) {
            if (res == null)
               return extRes;
            StringBuilder newRes = new StringBuilder();
            newRes.append(res);
            newRes.append(extRes);
            return newRes;
         }
      }
      // TODO: should we inherit this for interfaces too?
      return res;
   }

   private static boolean getNeedsSort(SemanticNodeList<Object> childList) {
      boolean needsSort = false;
      if (childList != null) {
         for (Object child:childList) {
            if (child instanceof Element) {
               Element childTag = (Element) child;
               if (childTag.getFixedAttribute("addBefore") != null || childTag.getFixedAttribute("addAfter") != null) {
                  needsSort = true;
                  break;
               }
               if (childTag.getFixedAttribute("orderValue") != null) {
                  needsSort = true;
                  break;
               }
            }
         }
      }
      return needsSort;
   }

   /** Utility method to add the children from the current element to the list of children from the derivedElement.
    * When unique is true, only the unique items are returned.  This will == children or null if we can inherit the base types outputBody method.
    * When unique is false, all items for the given element are returned, included those that are inherited.
    */
   private SemanticNodeList<Object> addChildren(Element parentElement, Element derivedElement, SemanticNodeList<Object> res, boolean unique, boolean iface, boolean canInherit, AddChildResult childRes) {
      SemanticNodeList<Object> derivedChildren = derivedElement == null ? null : derivedElement.getChildren(false, true); // Get all inherited children - when we have to reorder, it's all or nothing.

      boolean needsSort = false;

      if (canInherit) {
         // TODO: this can be optimized so that we use inheritance unless the sort order of the parent elements changes in this element.
         needsSort = getNeedsSort(children);
         if (!needsSort && derivedChildren != null) {
            needsSort = getNeedsSort(derivedChildren);
         }
         if (needsSort)
            canInherit = false;
      }

      MergeMode bodyMerge = getBodyMergeMode();

      if (bodyMerge == MergeMode.Replace) {
         // When not self-closed, replace with an empty method by creating an empty list rather than null signalling that we do not have any unique children.
         // when self-closed, just replace the attributes, not the body, like an annotation tag, for example to reorder an existing element.
         if (children == null && !selfClosed()) {
            res = new SemanticNodeList<Object>();
            res.parentNode = parentElement;
            childRes.needsSuper = false;
            childRes.needsBody = true;
            return res;
         }
         childRes.needsSuper = false;
         childRes.needsBody = children != null;
         return children;
      }
      else if (bodyMerge == MergeMode.Merge) {
         // look for children which do not match one of those in the parent type
         childRes.needsSuper = derivedElement != null && canInherit;
         childRes.needsBody = childRes.needsBody || children != null || (derivedElement != null && !derivedElement.isEmptyBody());
         if (!canInherit && derivedChildren != null) {
            if (res == null) {
               res = new SemanticNodeList<Object>();
               res.parentNode = parentElement;
            }
            addDerivedChildren(res, derivedChildren);
         }
         if (children != null) {
            for (Object child:children) {
               if (!(child instanceof Element)) {
                  if (res == null) {
                     res = new SemanticNodeList<Object>();
                     res.parentNode = parentElement;
                     if (!unique) {
                        addDerivedChildren(res, derivedChildren);
                     }
                  }
                  res.add(child);
               }
               else {
                  // This element may not be derived in the type hierarchy if it's 'replace=true' so getDerivedElement will not work.  Still that should not count as a unique child since we are replacing a previous object
                  Element childElement = (Element) child;
                  int childElementIx = findChildInList(derivedChildren, childElement.getRawObjectName());
                  // If this is a new piece of static content, non dynamic element, or an element which did not match for whatever reason it's derived element.
                  if (childElementIx == -1) {
                     if (res == null) {
                        res = new SemanticNodeList<Object>();
                        res.parentNode = parentElement;
                        if (!unique) {
                           addDerivedChildren(res, derivedChildren);
                        }
                     }
                     res.add(childElement); // Do not copy because this child is part of children already
                  }
                  else if (!canInherit) {
                     // Since we've already copied the derived elements into res in the non-inherit case, need to replace the derived element's child with the overridding element's child
                     res.set(childElementIx, childElement);
                  }
               }
            }
            if (!unique && res == null) {
               return derivedChildren;
            }
         }
         else {
            if (unique && !iface && canInherit)
               res = null;
            else
               res = derivedChildren;
         }
         if (iface)
            res = cloneChildren(res);
         if (needsSort) {
            sortChildren(res);
         }
         return res;
      }
      if (unique && canInherit) {
         // bodyMerge == Prepend or Append: we are not merging the children, always include all of them.
         return children;  // I don't see any reason to sort a single list right?
      }
      else {
         res = new SemanticNodeList<Object>();
         res.parentNode = parentElement;
         if (bodyMerge == MergeMode.Prepend) {
            if (children != null)
               res.addAll(children);
            addDerivedChildren(res, derivedChildren);
         }
         else if (bodyMerge == MergeMode.Append) {
            addDerivedChildren(res, derivedChildren);
            if (children != null)
               res.addAll(children);
         }
         if (needsSort)
            sortChildren(res);
         return res;
      }
   }

   Double getOrderValue() {
      String curValueStr = getFixedAttribute("orderValue");
      if (curValueStr != null) {
         try {
            return Double.parseDouble(curValueStr);
         }
         catch (NumberFormatException exc) {
            displayError("Invalid orderValue - needs a float point number.  Used to alter the order of tags, higher numbers are after lower numbers.  Default is 0: ");
         }
      }
      return 0.0;
   }

   private static double getChildOrderValue(Object child) {
      if (child instanceof Element) {
         return ((Element) child).getOrderValue();
      }
      return 0.0;
   }

   private void sortChildren(SemanticNodeList<Object> children) {
      sortChildrenByValue(children);
      sortChildrenByBeforeAfter(children);
   }

   private void sortChildrenByValue(SemanticNodeList<Object> children) {
      if (children == null)
         return;
      int size = children.size();
      // good old insertion sort
      for (int i = 1; i < size; i++) {
         Object child = children.get(i);
         double orderValue = getChildOrderValue(child);
         int j = i;
         int prev = j - 1;

         while (j > 0 && orderValue < getChildOrderValue(children.get(prev))) {
            children.set(j, children.get(prev));
            j = prev;
            prev = prev - 1;
         }
         if (j != i)
            children.set(j, child);
      }
   }

   private boolean processAddBeforeAfter(Object child, SemanticNodeList<Object> res, boolean isBefore) {
      if (child instanceof Element) {
         Element childTag = (Element) child;
         String reorderName;

         if ((reorderName = childTag.getFixedAttribute(isBefore ? "addBefore" : "addAfter")) != null) {
            // If the matched object used to be in the old list, remove it from the new list since it is being moved.
            int oldIx = findChildInList(res, childTag.getRawObjectName());

            int foundIndex = findChildInList(res, reorderName);
            if (foundIndex == -1) {
               displayTypeError(isBefore ? "addBefore" : "addAfter", " unable to find existing tag with id: ", reorderName, ": ");
            }
            else {
               if (oldIx != foundIndex) {
                  if (oldIx != -1) {
                     res.remove(oldIx);
                     if (oldIx < foundIndex)
                        foundIndex--;
                  }
                  if (isBefore)
                     res.add(foundIndex, child);
                  else {
                     if (foundIndex >= res.size())
                        res.add(child);
                     else
                        res.add(foundIndex+1, child);
                  }
               }
            }
            return true;
         }
      }
      return false;
   }

   public void sortChildrenByBeforeAfter(SemanticNodeList<Object> children) {
      if (children == null)
         return;
      int size = children.size();
      // Do the addBefore's first, then the addAfters.  Process them so that we preserve the original order if there are multiple attaching to the same id
      // The sorted list prevents us from processing a type twice since we're now moving the item into the same list
      IdentityHashSet<Object> sorted = new IdentityHashSet<Object>();
      for (int i = 0; i < size; i++) {
         Object child = children.get(i);
         if (!sorted.contains(child) && processAddBeforeAfter(child, children, true))
            sorted.add(child);
      }
      sorted = new IdentityHashSet<Object>();
      for (int i = size-1; i >= 0; i--) {
         Object child = children.get(i);
         if (!sorted.contains(child) && processAddBeforeAfter(child, children, false))
            sorted.add(child);
      }
   }

   SemanticNodeList<Object> cloneChildren(SemanticNodeList<Object> children) {
      SemanticNodeList<Object> res = new SemanticNodeList<Object>();
      res.parentNode = this;
      addDerivedChildren(res, children);
      return res;
   }

   private void addDerivedChildren(SemanticNodeList<Object> res, SemanticNodeList<Object> derivedChildren) {
      if (derivedChildren != null) {
         for (Object derivedChild:derivedChildren) {
            if (derivedChild instanceof JavaSemanticNode) {
               JavaSemanticNode copyNode = (JavaSemanticNode) ((JavaSemanticNode) derivedChild).deepCopy(ISemanticNode.CopyNormal, null);
               if (copyNode instanceof Element) {
                  Element origElem = (Element) derivedChild;
                  Element copyElem = (Element) copyNode;
                  // Make sure we copy the name.  It's expensive to recompute it and we may get it wrong because it's hard to find the child in the peers-list after it's been copied.  It does not get associated with the current parent after it's been copied so don't find the exact instance.
                  copyElem.cachedObjectName = origElem.getObjectName();
               }
               res.add(copyNode);
            }
            else {
               res.add(derivedChild);
            }
         }
      }
   }

   /**
    * Returns the set of children that need to be put into this tag's body method, if any.  When canInherit is true, it omits any merged tags from the returned list unless they have addBefore/addAfter attributes to reposition them.
    * This includes those that do not match any in the parent type when merging, or all of them if the mode is set to prepend or append.
    */
   private SemanticNodeList<Object> getUniqueChildren(boolean canInherit) {
      return getChildren(true, canInherit);
   }

   public Object[] getObjChildren(boolean create) {
      if (repeatTags != null)
         return repeatTags.toArray(new Object[repeatTags.size()]);
      if (dynObj != null) {
         return DynUtil.getObjChildren(dynObj, null, create);
      }
      return null;
   }

   public boolean getStateless() {
      return getBooleanAttribute("stateless");
   }

   public void _updateInst() {
      invalidate();
   }


   /** This handles breakpoints at the tag level.  To find the matching source statement, need to check our attributes and sub-tags */
   public boolean getNodeContainsPart(ISrcStatement partNode) {
      if (partNode == this || sameSrcLocation(partNode))
         return true;
      if (children != null) {
         for (Object child:children) {
            if (child instanceof ISrcStatement) {
               ISrcStatement childSt = (ISrcStatement) child;
               if (childSt == partNode || childSt.getNodeContainsPart(partNode))
                  return true;
            }
         }
      }
      return false;
   }

   @Override
   public int getNumStatementLines() {
      return 1;
   }

   public String getSimpleChildValue(String childName) {
      Element[] childTags = getChildTagsWithName(childName);
      if (childTags == null)
         return null;
      if (childTags.length != 1)
         throw new IllegalArgumentException("Multiple child tags: " + childName + " expecting only one");
      return childTags[0].getBodyAsString();
   }

   public Element getSingleChildTag(String name) {
      Element[] childTags = getChildTagsWithName(name);
      if (childTags == null || childTags.length == 0)
         return null;
      if (childTags.length > 1) {
         displayError("Multiple tags with name: " + name + " when only one is expected for: ");
      }
      return childTags[0];
   }

   public String getBodyAsString() {
      if (children == null)
         return null;
      StringBuilder sb = new StringBuilder();
      // TODO: deal with template values here?
      for (Object child:children) {
         sb.append(child);
      }
      return sb.toString();
   }

   // TODO: this method is probably not 100% yet - it mirrors the logic in convertToObject in reverse - so we can clone
   // the Template and template's rootType, then reassign the Elements.  It's probably faster this way?  We could also just
   // reinit the rootType from the cloned template when the init flags are set.
   public void assignChildTagObjects(TypeDeclaration parentType, Element oldElem) {
      if (oldElem.tagObject != null) {
         tagObject = parentType;
         parentType.element = this;
      }
      if (children != null) {
         // We just cloned oldElem to produce this one so these should match
         if (oldElem == null || oldElem.children == null || oldElem.children.size() != children.size())
            System.err.println("*** mismatch in cloned element children!");
         Object oldChild = null;
         int i = 0;
         for (Object child:children) {
            oldChild = oldElem.children.get(i++);
            if (child instanceof Element) {
               Element childElem = (Element) child;
               Element oldChildElem = (Element) oldChild;
               String childName = childElem.getObjectName();
               if (childElem.isRepeatElement()) {
                  String repeatName = childElem.getRepeatObjectName();
                  TypeDeclaration childRepeatType = (TypeDeclaration) parentType.getInnerType(repeatName, null);
                  if (childRepeatType == null) {
                     System.out.println("*** Skipping assignChildTagObjects for: " + parentType + " and: " + repeatName);
                     continue;
                  }
                  else {
                     childElem.repeatWrapper = childRepeatType;
                     childRepeatType.element = childElem;
                  }
               }
               TypeDeclaration childType = oldChildElem.tagObject == null  ? parentType : (TypeDeclaration) parentType.getInnerType(childName, null, false, false, false);
               if (childType != null)
                  childElem.assignChildTagObjects(childType, oldChildElem);
               /*
               else {
                  // TODO: remove this debug code
                  childType = oldChildElem.tagObject == null  ? parentType : (TypeDeclaration) parentType.getInnerType(childName, null, true, false, false);
                  if (childType != null)
                     System.out.println("***");

               }
               */
            }
         }
      }
   }

   /** Adds the id=.. assignment for a stub - the type inserted into the client tag class to represent the server tag object for exec="server"
    * Because the stub type only inherits from the base tag-type, it always needs to set the id property. */
   private void addStubIdAssignment(BodyTypeDeclaration type) {
      String idInitStr = getElementId();
      boolean idSpecified = idInitStr != null;
      if (!idSpecified)
         idInitStr = type.typeName;
      String fixedIdAtt = getFixedAttribute("id");
      if (fixedIdAtt != null) {
         if (fixedIdAtt.equals(ALT_ID)) {
            fixedIdAtt = getAltId();
         }
         fixedIdAtt = CTypeUtil.escapeIdentifierString(fixedIdAtt);
      }
      if (!inheritsId(fixedIdAtt) && !isSingletonTag()) {
         addSetIdAssignment(type, idInitStr, idSpecified);
      }
   }

   private void addSetIdAssignment(BodyTypeDeclaration type, String idInitStr, boolean idSpecified) {
      if (!idSpecified)
         idInitStr = type.typeName;
      if (idInitStr != null && tagName != null && idInitStr.equals(tagName) && isSingletonTag())
         return;
      Expression idInitExpr = createIdInitExpr(null, idInitStr, idSpecified);
      addTagTypeBodyStatement(type, PropertyAssignment.create("id", idInitExpr, "="));
   }

   public TypeDeclaration getExcludedStub() {
      if (tagObject == null)
         return null;
      int myExecFlags = getDefinedExecFlags();

      Element parentTag = getEnclosingTag();
      if (parentTag == null) // We only need the excluded stub for inner types
         return null;
      int parentExecFlags = parentTag.getComputedExecFlags();
      if (myExecFlags != parentExecFlags) {
         Object extType = getTagObjectExtends();
         // TODO: the addTypeVar param here might not be necessary... I think we add it typically for all classes in case they eventually are used as
         // a base class for a repeat tag which uses it for the element type.  Saw an error where the type param could not be resolved: RE_typeName so I
         // think maybe when the ClassType tries to resolve itself, it is not looking at the stub?
         ClassDeclaration decl = ClassDeclaration.create("object", tagObject.typeName, convertExtendsTypeToJavaType(extType, false));
         decl.addModifier("public");
         decl.parentNode = parentNode;
         addSetServerAtt(decl, 0, "serverContent");
         addSetServerAtt(decl, 0, "serverTag");
         addParentNodeAssignment(decl);
         addStubIdAssignment(decl);

         // TODO: currently if you exclude something with exec="server" nested children can't turn it back to exec="client" but we could potentially support that
         // by finding all child tags that become visible with exec="client" and adding them here.

         return decl;
      }
      return null;
   }

   private Object getTagObjectExtends() {
      Object extRes = null;
      if (tagObject instanceof ModifyDeclaration) {
         ModifyDeclaration modTagObj = (ModifyDeclaration) tagObject;
         if (modTagObj.modifyInherited)
            extRes = modTagObj.getModifiedType();
      }
      if (extRes == null)
         extRes = getExtendsTypeDeclaration();
      // Don't return the extends type of an excluded type since we only create excludedStubs for inner classes we might be extending an excluded class.
      // In that case, pick the default extends class for this tag type
      if (extRes instanceof BodyTypeDeclaration && !((BodyTypeDeclaration) extRes).needsCompile()) {
         extRes = getSystemTagClassForTagName(lowerTagName());
      }
      return extRes;
   }

   /** Called during transform to set properties specific to the java version of the StrataCode.
    * Used to modify the generated Java based on the merged version of the type, not know in convertToObject.  Specifically because the
    * exec attribute can affect the tag properties, but is not know until transform type because we create the tagObject during init.
    * TODO: could be implemented as a mixinTemplate but since we have the element in the tagObject this is easier
    */
   public void addMixinProperties(BodyTypeDeclaration tagType) {
      // NOTE: using this method, not isMarkedServerTag which only looks at this element.  Instead, we need to look at the merged-version of the final tag
      // type after the annotations have been merged to determine if it's a server tag in this instance.  Otherwise, we can't modify the exec attribute in
      // another layer.
      boolean serverTag = tagType.isServerTagType();
      if (serverTag) {
         Element parentTag = getEnclosingTag();
         // For now, only setting the serverTag property on the first parent which is a serverTag.  This won't work as is for
         // switching back to a client tag from within a server tag, but not sure that use case makes sense anyway.  We could add 'clientTag'
         // to switch it back in that case rather than having to set this on every inner tag object.
         if (parentTag == null || !parentTag.isMarkedAsServerTag()) {
            addSetServerAtt(tagType, 0, "serverTag");
         }
      }
      else {
         LayeredSystem sys = getLayeredSystem();
         // If we're on the server system and there's no client type for this page, we'll mark it to run using serverTags
         if (sys.serverEnabled && tagType.getEnclosingType() == null) {
            if (!sys.hasSyncPeerTypeDeclaration(tagType)) {
               addSetServerAtt(tagType, 0, "serverTag");
            }
         }
      }
   }

   static class AddChildResult {
      boolean needsSuper = false;
      boolean needsBody = true;
   }

   /**
    * Build the list of children at compile time for the current element.  When called with unique = true, returns
    * just the set of children this element adds to the stream - not
    */
   private SemanticNodeList<Object> getChildren(boolean unique, boolean canInherit) {

      SemanticNodeList<Object> res = null;
      Element lastImplElement = null;

      AddChildResult childRes = new AddChildResult();

      Element[] implementsElements = getImplementsElements();
      if (implementsElements != null) {
         for (Element implElem:implementsElements) {
            if (lastImplElement == null)
               lastImplElement = implElem;
            else
               hiddenChildren = res = lastImplElement.addChildren(this, implElem, res, unique, true, canInherit, childRes);
         }
      }

      Element derivedElement = getDerivedElement();
      Element extendsElement = getExtendsElement();
      // Only pick up the extends element if there's no derived element or it is specified right on this tag.
      if (extendsElement == derivedElement || (derivedElement != null && getFixedAttribute("extends") == null))
         extendsElement = null;
      boolean iface = false;

      if (lastImplElement != null && (extendsElement != null || derivedElement != null)) {
         Element toMerge = extendsElement == null ? derivedElement : extendsElement;
         toMerge.addChildren(this, lastImplElement, res, unique, true, canInherit, childRes);
      }
      if (extendsElement != null && derivedElement != null) {
         res = derivedElement.addChildren(this, extendsElement, res, unique, false, canInherit, childRes);
      }
      else if (extendsElement != null) {
         derivedElement = extendsElement;
         iface = !canInherit;
      }
      else if (lastImplElement != null) {
         derivedElement = lastImplElement;
         iface = true; // Can't extend the implemented guys so we always have to copy them
      }
      res = addChildren(this, derivedElement, res, unique, iface, canInherit, childRes);
      if (iface)
         hiddenChildren = res;
      needsSuper = childRes.needsSuper;
      needsBody = childRes.needsBody;
      return res;
   }

   private Object getRepeatElementType() {
      Attr repeatAtt = getAttribute("repeat");
      Object repeatElementType = null;
      if (repeatAtt != null) {
         if (repeatAtt.getOutputExpr() == null) {
            if (PString.isString(repeatAtt.value))
               repeatElementType = String.class;
         }
         else {
            Expression repeatExpr = repeatAtt.getOutputExpr();
            if (repeatExpr != null) {
               boolean started = repeatExpr.isStarted();
               Object repeatType = repeatExpr.getGenericType();
               repeatElementType = ModelUtil.getArrayOrListComponentType(getJavaModel(), repeatType);
               // if this was not started we need to stop it now so the whole type gets started properly
               if (!started)
                  ParseUtil.stopComponent(repeatExpr);
            }
         }
      }
      return repeatElementType;
   }

   private List<Object> getTagImplementsTypes() {
      String implementsStr = getFixedAttribute("implements");
      if (implementsStr == null)
         return null;
      String[] implNames = StringUtil.split(implementsStr, ',');
      ArrayList<Object> implTypes = new ArrayList<Object>(implNames.length);

      for (String name:implNames) {
         Object type = tagObject.findType(name, this, null);
         if (type == null) {
            JavaModel model = getJavaModel();
            type = model.findTypeDeclaration(name, true, false);
            if (type == null) {
               Attr attr = getAttribute("implements");
               attr.displayError("No implements type: " + name + ": ");
            }
         }
         if (type != null)
            implTypes.add(type);
      }
      return implTypes;
   }

   public String getRepeatObjectName() {
      return getObjectName() + "_Repeat";
   }

   public TypeDeclaration convertToObject(Template template, TypeDeclaration parentType, Object existing, SemanticNodeList<Object> templateModifiers, StringBuilder preTagContent) {
      if (tagObject != null)
         return tagObject;

      if (convertingToObject)
         System.err.println("*** Already converting tag to object!");

      convertingToObject = true;

      String objName = getObjectName();

      MergeMode tagMerge = getTagMergeMode();
      MergeMode bodyMerge = getBodyMergeMode();

      String scopeName = getFixedAttribute("scope");

      if (existing == null) {
         if (parentType != null)
            existing = parentType.getInnerType(objName, null);
         // else - no existing type - this must be passed in from the template
      }

      Object extTypeDecl = getExtendsTypeDeclaration();
      boolean canProcess = true;
      boolean canInherit = true;
      JavaModel javaModel = getJavaModel();
      Layer tagLayer = javaModel.getLayer();

      if (ModelUtil.isCompiledClass(extTypeDecl)) {
         Object newExtTypeDecl = ModelUtil.resolveSrcTypeDeclaration(javaModel.getLayeredSystem(), extTypeDecl, false, true, tagLayer);
         if (newExtTypeDecl instanceof BodyTypeDeclaration)
            extTypeDecl = newExtTypeDecl;
      }

      if (existing != null && ModelUtil.isCompiledClass(existing)) {
         Object newExisting = ModelUtil.resolveSrcTypeDeclaration(javaModel.getLayeredSystem(), existing, false, true, tagLayer);
         if (newExisting instanceof BodyTypeDeclaration) {
            existing = newExisting;
            if (ModelUtil.isCompiledClass(existing)) {
               displayWarning("Ignoring compiled class as previous type for tag: " + tagName + " in: ");
               existing = null;
            }
         }
      }

      // We used to exclude the generation of child tag objects and modified how the parent was generated based
      // on the exec.  Now, we are going to add the @Exec annotation for the exec attribute and let StrataCode
      // do the processing to remove the tag objects using that annotation.  That's because we might be modified
      // by a new layer which resets our 'exec' attribute.  To support that, we cannot be doing this processing
      // during the 'init' phase - we need to do it after start.  The @Exec will be applied during start as
      // setting properties (setting excluded and not starting children) and transform - removing the node
      // from the transformed layer.
      boolean remoteContent = oldExecTag ? isRemoteContent() : false;

      boolean isRepeatElement = isRepeatElement();
      Object repeatWrapperType = null;
      boolean needsWrapperInterface = true;
      if (isRepeatElement && !remoteContent) {
         String repeatWrapperName = getFixedAttribute("repeatWrapper");
         if (repeatWrapperName != null) {
            repeatWrapperType = findType(repeatWrapperName, this, null);
            if (repeatWrapperType == null) {
               JavaModel model = getJavaModel();
               if (model != null) {
                  repeatWrapperType = model.findTypeDeclaration(repeatWrapperName, true, false);
                  if (repeatWrapperType == null) {
                     displayError("No repeatWrapper type: ", repeatWrapperName, " for tag: ");
                  }
               }
            }
            if (repeatWrapperType != null && !ModelUtil.isAssignableFrom(Element.class, repeatWrapperType)) {
               displayError("Element's repeatWrapper type: " + repeatWrapperType + " must be extends be a tag object (i.e. extends sc.lang.html.Element)");
               repeatWrapperType = null;
            }
            if (repeatWrapperType != null)
               needsWrapperInterface = !ModelUtil.isAssignableFrom(IRepeatWrapper.class, repeatWrapperType);
         }
         repeatWrapper = ClassDeclaration.create(isAbstract() ? "class" : "object", getRepeatObjectName(), JavaType.createJavaType(getLayeredSystem(), repeatWrapperType == null ? HTMLElement.class : repeatWrapperType));
         if (needsWrapperInterface)
            repeatWrapper.addImplements(JavaType.createJavaType(getLayeredSystem(), IRepeatWrapper.class));
         repeatWrapper.element = this;
         repeatWrapper.layer = tagLayer;
         repeatWrapper.addModifier("public");

         if (needsWrapperInterface) {
            // TODO: cache this to avoid reparsing it each time?
            SemanticNodeList<Statement> repeatMethList = (SemanticNodeList<Statement>) TransformUtil.parseCodeTemplate(Object.class,
                    "   public sc.lang.html.Element createElement(Object val, int ix, sc.lang.html.Element oldTag) {\n " +
                            "      if (oldTag != null)\n" +
                            "         return oldTag;\n " +
                            "      sc.lang.html.Element elem = new " + objName + "();\n" +
                            "      elem.repeatVar = val;\n" +
                            "      elem.repeatIndex = ix;\n" +
                            "      return elem;\n" +
                            "   }",
                    SCLanguage.INSTANCE.classBodySnippet, false);

            Statement repeatMeth = repeatMethList.get(0);
            repeatMeth.setFromStatement(this);
            // TODO: should the Repeat wrapper implement IObjChildren so that the getObjChildren method is implemented by
            // retrieving the current repeat tags?   This would let a node in the editor that is a repeat display its
            // children in the child form.
            repeatWrapper.addBodyStatement(repeatMeth);
         }

         if (parentType != null)
            parentType.addBodyStatement(repeatWrapper);
         else
            template.addTypeDeclaration(repeatWrapper);

         addParentNodeAssignment(repeatWrapper);
         repeatWrapper.fromStatement = this;
      }

      modifyType = tagMerge == MergeMode.Replace ? null : existing;

      /* TODO: probably should remove this ExecProcess phase altogether.  It was an attempt to generate a minimal .html file from the process phase based on a template by not inheriting any elements, stripping out functionality.  It's a mess.  Now we just added the postBuild phase so that we can use the generated code to generate the initial template */
      /* Also, exec="process" just doesn't work because we only generate client and server versions of the object.  We'd have to go back to an older mode where we did not generate the Java class, and process the template directly (or generate a 3rd class) but that was ugly for it's own reasons. */
      if (template.execMode == ExecProcess) {
         int definedExecFlags = getDefinedExecFlags();
         // If it's not explicitly set to a mode, we'll detect whether we can use this types at the process phase
         if ((definedExecFlags & ExecProcess) == 0) {
            if ((getComputedExecFlags() & ExecProcess) != 0) {
               Element parentTag = getEnclosingTag();
               // If we can't process the parent tag, we may have lost context from the parent type and so can only process the child if it sets the process mode explicitly (i.e. telling us it can process)
               if (parentTag == null || !parentTag.staticContentOnly)
                  canProcess = (existing == null || ModelUtil.isProcessableType(existing)) && (extTypeDecl == null || ModelUtil.isProcessableType(extTypeDecl));
               else
                  canProcess = false;
            }
            else
               canProcess = false;
         }
         else {
            while (extTypeDecl != null && !ModelUtil.isProcessableType(extTypeDecl)) {
               extTypeDecl = ModelUtil.getExtendsClass(extTypeDecl);
               canInherit = false;
            }
            if (extTypeDecl == null) {
               extTypeDecl = HTMLElement.class;
               canInherit = false;
            }
         }

         if (!canProcess) {
            existing = null;
            canInherit = false;
            extTypeDecl = getDefaultExtendsTypeDeclaration(true);
            staticContentOnly = true;
            execFlags = execFlags & ~ExecProcess;
         }
      }

      String typeParamName = "RE_" + objName;

      JavaType extendsType;
      JavaType[] typeParams = new JavaType[1];
      typeParams[0] = ClassType.create(typeParamName);
      if (!canProcess) {
         if (extTypeDecl == null) {
            if (existing == null || !ModelUtil.isAssignableFrom(HTMLElement.class, existing)) {
               extTypeDecl = HTMLElement.class;
               extendsType = JavaType.createTypeFromTypeParams(HTMLElement.class, typeParams);
            }
            else
               extendsType = null;
         }
         else {
            extendsType = JavaType.createTypeFromTypeParams(extTypeDecl, typeParams);
         }
      }
      else {
         if (!canInherit)
            extendsType = JavaType.createTypeFromTypeParams(extTypeDecl, typeParams);
         else {
            extendsType = getExtendsType();
            // If we are modifying a type need to be sure this type is compatible with that type (and that one is a tag type)
            if (modifyType != null) {
               Object declaredExtends = getDeclaredExtendsTypeDeclaration();
               Object modifyExtendsType = ModelUtil.getExtendsClass(modifyType);

               if (modifyExtendsType != null && modifyExtendsType != Object.class) {
                  if (declaredExtends != null && !ModelUtil.isAssignableFrom(modifyExtendsType, declaredExtends)) {
                     // This is the equivalent of redefining the class for schtml - just define an incompatible base class and it breaks the link automatically with the previous type - the sensible default.
                     if (ModelUtil.isAssignableFrom(HTMLElement.class, declaredExtends)) {
                        modifyType = null;
                        modifyExtendsType = null;
                     }
                  }
                  if (modifyExtendsType != null && !ModelUtil.isAssignableFrom(HTMLElement.class, modifyExtendsType)) {
                     // These errors can occur in normal situations in inactive layers - because we are not guaranteeing just one stacking order... for now we are going to ignore them and use the declared
                     // extends and modify types for code navigation purposes.   We do need to null out the 'extends' type or else we can end up creating a 'modifyInherited' type with an extends type which is not allowed.
                     if (javaModel.getLayer().activated) {
                        displayError("tag with id: ", objName, " modifies type: ", ModelUtil.getTypeName(modifyType), " in layer:", ModelUtil.getLayerForType(null, modifyType) + " already extends: ", ModelUtil.getTypeName(modifyExtendsType), " which has no schtml file (and does not extends HTMLElement): ");
                        extendsType = null;
                     }
                  }
                  if (declaredExtends != null) {
                     if (!ModelUtil.isAssignableFrom(modifyExtendsType, declaredExtends)) {
                        if (javaModel.getLayer().activated) {
                           displayError("The extends attribute: ", ModelUtil.getTypeName(declaredExtends), " overrides an incompatible extends type: ", ModelUtil.getTypeName(modifyExtendsType), " for tag: ");
                           extendsType = null;
                        }
                     }
                  }
                  // Do not set an extends type here - we need to inherit it from the modified type
                  else
                     extendsType = null;
               }
            }
         }
      }

      if (extTypeDecl != null && !ModelUtil.isAssignableFrom(HTMLElement.class, extTypeDecl)) {
         if (javaModel.getLayer().activated)
            displayTypeError("extends type for tag must extend HTMLElement or come from an schtml template: ");
      }

      TypeDeclaration tagType;
      boolean isModify = false;
      if (existing != null && tagMerge != MergeMode.Replace) {
         if (!ModelUtil.sameTypes(extTypeDecl, ModelUtil.getExtendsClass(existing)))
            tagType = ModifyDeclaration.create(objName, extendsType);
         else
            tagType = ModifyDeclaration.create(objName);
         isModify = true;
      }
      else {
         tagType = ClassDeclaration.create(isAbstract() || isRepeatElement ? "class" : "object", getObjectName(), extendsType);
         if (tagLayer != null && tagLayer.defaultModifier != null)
            tagType.addModifier(tagLayer.defaultModifier);
         // If we make these classes abstract, it makes it simpler to identify them and omit from type groups, but it means we can't
         // instantiate these classes as base type.  This means more classes in the code.  So instead the type groups stuff needs
         // to check Element.isAbstract.
         if (isAbstract() && tagTypeNeedsAbstract())
            tagType.addModifier("abstract");
      }

      processScope(tagType, scopeName);
      processExecAttr(tagType);

      // Leave a trail for finding where this statement was generated from for debugging purposes
      tagType.fromStatement = this;
      if (repeatWrapper == null && extTypeDecl != null) {
         // Only support the repeat element type parameter if the base class has it set.
         List<?> extTypeParams = ModelUtil.getTypeParameters(extTypeDecl);
         if (extTypeParams != null && extTypeParams.size() == 1) {
            SemanticNodeList typeParamsList = new SemanticNodeList();
            typeParamsList.add(TypeParameter.create(typeParamName));
            tagType.setProperty("typeParameters", typeParamsList);
         }
      }

      String componentStr = getFixedAttribute("component");
      if (componentStr != null && componentStr.equalsIgnoreCase("true")) {
         tagType.addModifier(Annotation.create("sc.obj.Component"));
      }

      if (template.temporary)
         tagType.markAsTemporary();

      tagObject = tagType;
      tagType.element = this;
      tagType.layer = tagLayer;
      if (templateModifiers != null)
         tagType.setProperty("modifiers", templateModifiers);

      if (!isModify && ModelUtil.hasModifier(extTypeDecl, "public") && !tagType.hasModifier("public"))
         tagType.addModifier("public");

      if (repeatWrapper != null)
         repeatWrapper.addSubTypeDeclaration(tagType);
      else if (parentType != null)
         parentType.addSubTypeDeclaration(tagType);
      else
         template.addTypeDeclaration(tagType);

      int idIx = addParentNodeAssignment(tagType);

      // This sets hiddenChildren so must be done up here - before initAttrExprs or we look at the children
      SemanticNodeList<Object> uniqueChildren = remoteContent ? null : getUniqueChildren(canInherit);

      initAttrExprs();

      if (remoteContent) {
         idIx = addSetServerAtt(tagType, idIx, "serverContent");
      }
      serverTag = oldExecTag ? isMarkedAsServerTag() : false;
      if (serverTag) {
         Element parentTag = getEnclosingTag();
         // For now, only setting the serverTag property on the first parent which is a serverTag.  This won't work as is for
         // switching back to a client tag from within a server tag, but not sure that use case makes sense anyway.  We could add 'clientTag'
         // to switch it back in that case rather than having to set this on every inner tag object.
         if (parentTag == null || !parentTag.serverTag) {
            idIx = addSetServerAtt(repeatWrapper != null ? repeatWrapper : tagType, idIx, "serverTag");
         }
      }

      List<Object> implTypes = getTagImplementsTypes();
      if (implTypes != null && !staticContentOnly) {
         // The implements can refer to other tag objects from which we inherit attributes and body children, not the actual class so we strip these out here.
         for (int i = 0; i < implTypes.size(); i++) {
            Object implType = implTypes.get(i);
            if (!ModelUtil.isInterface(implType)) {
               implTypes.remove(i);
               i--;
            }
         }
         if (implTypes.size() != 0) {
            for (Object implType:implTypes)
               tagObject.addImplements(JavaType.createJavaTypeFromName(ModelUtil.getTypeName(implType)));
         }
      }

      String fixedIdAtt = getFixedAttribute("id");
      if (fixedIdAtt != null) {
         if (fixedIdAtt.equals(ALT_ID)) {
            fixedIdAtt = getAltId();
         }
         fixedIdAtt = CTypeUtil.escapeIdentifierString(fixedIdAtt);
      }
      if (needsAutoId(fixedIdAtt)) {
         // Needs to be after the setParent call.
         addSetIdAssignment(tagType, tagType.typeName, false);
         specifiedId = true;
      }
      if (repeatWrapper != null) {
         // Needs to be after the setParent call.
         addSetIdAssignment(repeatWrapper, repeatWrapper.typeName, false);
      }
      if (tagName != null) {
         // If either we are the first class to extend HTMLElement or we are derived indirectly from Page (and so may not have assigned a tag name)
         if (extTypeDecl == HTMLElement.class || ModelUtil.isAssignableFrom(Page.class, extTypeDecl) || !tagName.equals(getExtendsDefaultTagNameForType(tagType))) {
            addTagTypeBodyStatement(tagType, PropertyAssignment.create("tagName", StringLiteral.create(tagName), "="));
         }
      }

      /* done below as part of the normal attribute to code process
      if (getBooleanAttribute("needsRefresh")) {
         tagType.addBodyStatement(PropertyAssignment.create("needsRefresh", BooleanLiteral.create(true), "="));
      }
      */

      // Add property assignments for attributes which are set in the tag that correspond to properties
      ArrayList<Attr> attList = getInheritedAttributes();
      if (attList != null) {
         for (Attr att:attList) {
             if (att.valueExpr != null && att.valueProp != null) {
                if (!staticContentOnly) {
                   boolean isIdProperty = att.name.equalsIgnoreCase("id");
                   Expression attExpr = att.valueExpr;

                   // Need to make a copy of the attribute expressions.  We can regenerate the tag type and update it.  if we share the same expression with the old and new types, the update messes things up.
                   if (attExpr != null) {
                      ISemanticNode parentNode = attExpr.parentNode;
                      att.valueExprClone = attExpr = attExpr.deepCopy(CopyNormal | CopyInitLevels, null);
                      att.valueExprClone.parentNode = parentNode;
                   }

                   if (isIdProperty) {
                      attExpr = getIdInitExpr(attExpr, att.value); // Converts the provided id attribute value to an expression for initializing this tag or returns null if we can inherit it
                      if (attExpr == null)
                         continue;
                   }
                   // For remote content, only look at the id attribute
                   if (!isInheritedAtt(att) && (!remoteContent || isIdProperty)) {
                      PropertyAssignment pa = PropertyAssignment.create(Element.mapAttributeToProperty(att.name), attExpr, att.op);
                      pa.fromAttribute = att;
                      // TODO: enforce a rule about not modifying the value of the ID attribute.  Maybe it should be marked final?
                      // Always want the ID property to be set first
                      if (isIdProperty) {
                         tagType.addBodyStatementAtIndent(idIx, pa);
                         specifiedId = true;
                      }
                      // For the 'repeat' case, we have two classes which might hold the property - the wrapper or the element type.  We give preference
                      // to the element type in case the property is in both.
                      else if (att.name.equals("repeat") || repeatWrapper != null) {
                         Object valuePropType =  ModelUtil.getEnclosingType(att.valueProp);
                         if (att.name.equals("repeat") || (!ModelUtil.isAssignableFrom(valuePropType, tagType) && ModelUtil.isAssignableFrom(valuePropType, repeatWrapper)))
                            repeatWrapper.addBodyStatementIndent(pa);
                         else
                            tagType.addBodyStatementIndent(pa);
                      }
                      else
                         tagType.addBodyStatementIndent(pa);

                      // The serverTag property needs to be set on both the repeat inner tag class and the wrapper
                      if (repeatWrapper != null && att.name.equals("serverTag")) {
                         PropertyAssignment npa = pa.deepCopy(ISemanticNode.CopyParseNode, null);
                         npa.fromAttribute = att;
                         repeatWrapper.addBodyStatementIndent(npa);
                      }

                      // If we're tracking changes for the page content
                      if (template.statefulPage && !isReadOnlyAttribute(att.name) && isHtmlAttribute(att.name) && !hasInvalidateBinding(att.name) && !isRefreshAttribute(att.name)) {
                         IdentifierExpression methCall = IdentifierExpression.createMethodCall(new SemanticNodeList(), "invalidateStartTag");
                         methCall.fromStatement = att; // We will strip off the PropertyAssignment so need to set this up here too
                         PropertyAssignment ba = PropertyAssignment.create(Element.mapAttributeToProperty(att.name), methCall, "=:");
                         ba.fromStatement = att;
                         tagType.addBodyStatementIndent(ba);
                      }
                   }
                }
                else
                   att.valueExpr.inactive = true; // de-activate these expressions as we won't be able to resolve them
            }
            else if (att.value instanceof Expression) {
                if (!staticContentOnly)
                   ((Expression) att.value).inactive = true;
            }
         }
      }


      if (!staticContentOnly && !remoteContent) {
         String repeatVarName = getRepeatVarName();
         if (repeatVarName != null && !repeatVarName.equals("repeatVar")) {
            Object repeatElementType = getRepeatElementType();
            Expression repeatExpr = IdentifierExpression.create("repeatVar");
            if (repeatElementType == null)
               repeatElementType = Object.class;
            else {
               repeatExpr = CastExpression.create(ModelUtil.getTypeName(repeatElementType), repeatExpr);
            }
            FieldDefinition repeatVarField = FieldDefinition.create(getLayeredSystem(), repeatElementType, repeatVarName, ":=:", repeatExpr);
            addTagTypeBodyStatement(tagType, repeatVarField);
         }
      }

      if (!remoteContent) {
         template.addBodyStatementsFromChildren(tagType, hiddenChildren, this, false);
         template.addBodyStatementsFromChildren(tagType, children, this, false);

         SemanticNodeList<Object> mods = new SemanticNodeList<Object>();
         mods.add("public");
         int resCt = 0;

         // TODO: test this and put it back in!  It should work and reduces the code a bit
         // As an optimization, omit the outputStartTag method when it's a really simple case.
         //if (!canInherit || !inheritsId(fixedIdAtt) || specifiedId || (attList != null && attList.size() > 0)) {
            // Generate the outputStartTag method
            MethodDefinition outputStartMethod = new MethodDefinition();
            outputStartMethod.name = "outputStartTag";
            outputStartMethod.parentNode = tagType;
            outputStartMethod.setProperty("modifiers", mods);
            outputStartMethod.setProperty("type", PrimitiveType.create("void"));
            // TODO: make these parameters configurable - e.g. request and response?  Or just as a RequestContext method we can customize for different frameworks
            outputStartMethod.setProperty("parameters", template.getDefaultOutputParameters());
            outputStartMethod.initBody();
            SemanticNodeList<Expression> trueArgs = new SemanticNodeList<Expression>(1);
            trueArgs.add(BooleanLiteral.create(true));
            outputStartMethod.addBodyStatementAt(0, IdentifierExpression.createMethodCall(trueArgs, "markStartTagValid"));
            outputStartMethod.fromStatement = this;
            String preTagStr;
            if (preTagContent == null) {
               StringBuilder parentPreTagContent = getInheritedPreTagContent();

               // If we extend or modify a tag that has the doctype control in it, we need to compile it in here because we do not always inherit the outputStartTag method
               if (parentPreTagContent != null && parentPreTagContent.length() > 0) {
                  preTagContent = parentPreTagContent;
               }
            }
            if (preTagContent != null && (preTagStr = preTagContent.toString().trim()).length() > 0) {
               template.addTemplateDeclToOutputMethod(tagType, outputStartMethod.body, preTagStr, false, "StartTag", resCt, this, this, true, false);
            }
            resCt = addToOutputMethod(tagType, outputStartMethod.body, template, doOutputStart, children, resCt, true);
            tagType.addBodyStatementIndent(outputStartMethod);
         /*
         }
         else {
            System.out.println("---");
         }
         */

         if (uniqueChildren != null) {
            // Generate the outputBody method
            MethodDefinition outputBodyMethod = new MethodDefinition();
            outputBodyMethod.name = "outputBody";
            mods = new SemanticNodeList<Object>();
            mods.add("public");
            outputBodyMethod.parentNode = tagType;
            outputBodyMethod.setProperty("modifiers", mods);
            outputBodyMethod.setProperty("type", PrimitiveType.create("void"));
            outputBodyMethod.setProperty("parameters", template.getDefaultOutputParameters());
            outputBodyMethod.fromStatement = this;
            outputBodyMethod.initBody();
            SemanticNodeList<Expression> outArgs = new SemanticNodeList<Expression>();
            outArgs.add(IdentifierExpression.create("out"));
            // TODO: add Options here to do this inside of the body via a new special case of "super" with no args or anything.  Manually wrap the sub-page's content.  Not sure this is necessary because of the flexibility of merging.
            // ? Optional feature: rename the outputBody method via an attribute on the super-class so that you can do customized add-ons to template types (e.g. "BodyPages" where the html, head, etc. are generated from a common wrapper template) - NOTE - now thinking this is not necessary because of the flexibility of merging
            if (bodyMerge == MergeMode.Append || bodyMerge == MergeMode.Merge) {
               // Use of addBefore or addAfter with append mode?  Not sure this makes sense... maybe an error?
               if (uniqueChildren == children || bodyMerge == MergeMode.Merge) {
                  // Do not do the super if there are any addBefore or addAfter's in our children's list
                  IdentifierExpression superExpr;
                  if (needsSuper) {
                     superExpr = IdentifierExpression.createMethodCall(outArgs, "super.outputBody");
                  }
                  // If we are not calling the super, we still need to set bodyValid = true.  Doing this as a method so it's a hook point tag objects can extend
                  else {
                     SemanticNodeList<Expression> validArgs = new SemanticNodeList<Expression>();
                     validArgs.add(BooleanLiteral.create(true));
                     superExpr = IdentifierExpression.createMethodCall(validArgs, "markBodyValid");
                  }
                  superExpr.fromStatement = this;
                  outputBodyMethod.addStatement(superExpr);
               }
               else
                  displayWarning("Use of addBefore/addAfter with bodyMerge='append' - not appending to eliminate duplicate content");
            }

            addToOutputMethod(tagType, outputBodyMethod.body, template, doOutputBody, uniqueChildren, resCt, true);

            if (bodyMerge == MergeMode.Prepend) {
               if (uniqueChildren == children) {
                  outputBodyMethod.addStatement(IdentifierExpression.createMethodCall(outArgs, "super.outputBody"));
               }
               else
                  displayWarning("Use of addBefore/addAfter with bodyMerge='prepend' - not prepending to eliminate duplicate content");
            }
            addTagTypeBodyStatement(tagType, outputBodyMethod);
         }
      }

      /*
        * Right now, we build JS after we build Java so the JS files are not avaiable at compile time for Java where we need them.
        * Moving to a solution where the JSRuntime's buildInfo can be used to retrieve the list of files.
      if (isPageElement()) {
         List<String> jsFiles = getJSFiles();
         if (jsFiles != null) {
            MethodDefinition getJSFiles = new MethodDefinition();
            getJSFiles.name = "getJSFiles";
            getJSFiles.addModifier("public");
            ClassType retType = (ClassType) ClassType.create("java.util.List");
            SemanticNodeList<JavaType> typeArgs = new SemanticNodeList<JavaType>();
            typeArgs.add(ClassType.create("String"));
            retType.setResolvedTypeArguments(typeArgs);
            getJSFiles.setProperty("type", retType);

            getJSFiles.addBodyStatementAt(0, ReturnStatement.create(Expression.createFromValue(jsFiles, false)));

            tagType.addBodyStatementIndent(getJSFiles);
         }
      }
      */

      // Generate the outputBody method
      return tagType;
   }


   private Expression createIdInitExpr(Expression attExpr, String attStr, boolean idSpecified) {
      if (idSpecified && attStr.equals(ALT_ID))
         attExpr = StringLiteral.create(getAltId());
      SemanticNodeList<Expression> args = new SemanticNodeList<Expression>();
      if (attExpr == null)
         attExpr = StringLiteral.create(attStr);
      args.add(attExpr);
      // Don't add 'true' here if the id has been assigned explicitly as an attribute
      if (!idSpecified && getNeedsClientServerSpecificId()) {
         args.add(BooleanLiteral.create(true));
      }
      // I think the need for the "_s" suffix is just for when we use the tag name because the order in which ids are assigned must match between client/server
                         /*
                         if (getNeedsClientServerSpecificId())
                            args.add(BooleanLiteral.create(true));
                         */
      return IdentifierExpression.createMethodCall(args, "allocUniqueId");
   }

   private Expression getIdInitExpr(Expression defaultAttExpr, Object attVal) {
      // We might be inheriting this same id in which case do not set it - otherwise, we get duplicates of the same unique id.
      // But if we are extending a tag with a different id, we do need to set it even if we are inheriting it.
      if (!PString.isString(attVal) || !inheritsId(attVal.toString())) {
         return createIdInitExpr(defaultAttExpr, attVal.toString(), true);
      }
      // else - no need to initialize the id property - it's inherited
      return null;
   }

   private String getTagNameForExplicitType(Object type) {
      if (ModelUtil.hasTypeParameters(type))
         type = ModelUtil.getParamTypeBaseType(type);
      String typeName = ModelUtil.getTypeName(type);
      String defaultTagName = classNameToTagNameIndex.get(typeName);
      if (type instanceof TypeDeclaration) {
         TypeDeclaration extBodyType = (TypeDeclaration) type;
         if (extBodyType.element != null) {
            String bodyElemTagName = extBodyType.element.tagName;
            if (bodyElemTagName != null && defaultTagName != null && !bodyElemTagName.equals(defaultTagName))
               System.err.println("*** Tag name conflict: " + bodyElemTagName + " and " + defaultTagName + " for: " + typeName);
            return extBodyType.element.tagName;
         }
      }
      if (defaultTagName != null)
         return defaultTagName;
      return null;
   }

   private String getDefaultTagNameForType(Object tagType) {
      String tagName = getTagNameForExplicitType(tagType);
      if (tagName != null)
         return tagName;
      String res = getNextExtendsDefaultTagNameForType(tagType);
      return res;
   }

   private String getExtendsDefaultTagNameForType(Object tagType) {
      String res = getNextExtendsDefaultTagNameForType(tagType);
      if (res == null) {
         res = ""; // Note: this is a valid case for tags like 'li' which map to the default HTMLElement class which has no specific tag name
      }
      return res;
   }

   private String getNextExtendsDefaultTagNameForType(Object tagType) {
      Object nextTypeDecl = tagType;
      Object extTypeDecl = ModelUtil.getExtendsClass(nextTypeDecl);
      if (extTypeDecl != null && extTypeDecl != tagType) {
         String tagName = getTagNameForExplicitType(extTypeDecl);
         if (tagName != null)
            return tagName;
         tagName = getDefaultTagNameForType(extTypeDecl);
         if (tagName != null)
            return tagName;
      }
      Object modTypeDecl = ModelUtil.getSuperclass(nextTypeDecl);
      if (modTypeDecl != null && modTypeDecl != extTypeDecl) {
         String tagName = getTagNameForExplicitType(modTypeDecl);
         if (tagName != null)
            return tagName;
         return getDefaultTagNameForType(modTypeDecl);
      }
      return null;
   }

   private void addTagTypeBodyStatement(BodyTypeDeclaration tagType, Statement st) {
      tagType.addBodyStatementIndent(st);
      st.fromStatement = this;
   }

   private void processScope(TypeDeclaration tagType, String scopeName) {
      if (scopeName != null) {
         if (ScopeModifier.isValidScope(getJavaModel(), scopeName)) {
            ScopeModifier scopeMod = new ScopeModifier();
            scopeMod.scopeName = scopeName;
            tagType.addModifier(scopeMod);
         }
         else {
            Attr attr = getAttribute("scope");
            if (attr != null)
               attr.displayTypeError("No scope " + scopeName + " for tag: ");
         }
      }
   }

   private void processExecAttr(TypeDeclaration tagType) {
      if (oldExecTag)
         return;
      String execStr = getFixedAttribute("exec");
      if (execStr != null) {
         Annotation annot = Annotation.create("sc.obj.Exec");
         annot.addAnnotationValues(AnnotationValue.create("runtimes", execStr));
         int flags = parseExecFlags(execStr);
         if ((flags & ExecServer) != 0 && ((flags & ExecClient) == 0))
            annot.addAnnotationValues(AnnotationValue.create("serverOnly", true));
         if ((flags & ExecClient) != 0 && ((flags & ExecServer) == 0))
            annot.addAnnotationValues(AnnotationValue.create("clientOnly", true));

         tagType.addModifier(annot);
      }
   }

   private boolean tagTypeNeedsAbstract() {
      if (children != null) {
         for (Object child:children) {
            if (child instanceof TemplateDeclaration) {
               if (((TemplateDeclaration) child).needsAbstract())
                  return true;
            }
         }
      }
      return false;
   }

   /** Are we the top-level page element */
   protected boolean isPageElement() {
      return getEnclosingTag() == null;
   }

   private int addParentNodeAssignment(BodyTypeDeclaration type) {
      BodyTypeDeclaration parentType = type.getEnclosingType();
      if (parentType != null) {
         //PropertyAssignment pa = PropertyAssignment.create("parentNode", IdentifierExpression.create(parentType.typeName, "this"), "=");
         PropertyAssignment pa = PropertyAssignment.create("parentNode", SelectorExpression.create(IdentifierExpression.create(parentType.typeName), VariableSelector.create("this", null)), "=");
         addTagTypeBodyStatement(type, pa);
         return type.body.size();
      }
      return 0;
   }

   private int addSetServerAtt(BodyTypeDeclaration type, int idx, String propName) {
      PropertyAssignment pa = PropertyAssignment.create(propName, BooleanLiteral.create(true), "=");
      addTagTypeBodyStatement(type, pa);
      return type.body.size();
   }

   static HashMap<String, Set<String>> htmlAttributeMap = new HashMap<String, Set<String>>();
   static HashMap<String, Set<String>> linkAttributeMap = new HashMap<String, Set<String>>();
   static HashMap<String, String> tagExtendsMap = new HashMap<String, String>();

   static HashMap<String, Class> systemTagClassMap = new HashMap<String, Class>();

   private static HashSet<String> singletonTagNames = new HashSet<String>();

   static void addTagAttributes(String tagName, String extName, String[] htmlAttributes, String[] linkAttributes) {
      htmlAttributeMap.put(tagName, new TreeSet(Arrays.asList(htmlAttributes)));
      if (linkAttributes != null)
         linkAttributeMap.put(tagName, new TreeSet(Arrays.asList(linkAttributes)));
      tagExtendsMap.put(tagName, extName);
      if (systemTagClassMap.get(tagName) == null)
         systemTagClassMap.put(tagName, HTMLElement.class);
   }
   static {
      systemTagClassMap.put("head", Head.class);
      systemTagClassMap.put("body", Body.class);
      systemTagClassMap.put("input", Input.class);
      systemTagClassMap.put("option", Option.class);
      systemTagClassMap.put("select", Select.class);
      systemTagClassMap.put("form", Form.class);
      systemTagClassMap.put("a", A.class);
      systemTagClassMap.put("html", HtmlPage.class);
      systemTagClassMap.put("div", Div.class);
      systemTagClassMap.put("span", Span.class);
      systemTagClassMap.put("img", Img.class);
      systemTagClassMap.put("button", Button.class);
      systemTagClassMap.put("style", Style.class);

      String[] emptyArgs = {};
      addTagAttributes("element", null, new String[] {"id", "style", "class"}, null);
      addTagAttributes("html", "element", new String[] {"manifest", "xmlns"}, null);
      addTagAttributes("select", "element", new String[] {"multiple", "disabled", "selectedindex"}, null);
      addTagAttributes("option", "element", new String[] {"selected", "value", "disabled"}, null);
      addTagAttributes("input", "element", new String[] {"value", "disabled", "type", "checked", "defaultchecked", "form", "name", "placeholder", "size", "autocomplete"}, null);
      addTagAttributes("textarea", "element", new String[] {"rows", "cols", "required", "readonly", "form", "name", "placeholder", "size"}, null);
      addTagAttributes("button", "input", emptyArgs, null);
      addTagAttributes("span", "element", emptyArgs, null);
      addTagAttributes("div", "element", emptyArgs, null);
      addTagAttributes("p", "element", emptyArgs, null);
      addTagAttributes("body", "element", emptyArgs, null);
      addTagAttributes("head", "element", emptyArgs, null);
      addTagAttributes("li", "element", emptyArgs, null);
      addTagAttributes("ul", "element", emptyArgs, null);
      addTagAttributes("ol", "element", emptyArgs, null);
      addTagAttributes("table", "element", emptyArgs, null);
      addTagAttributes("tr", "element", emptyArgs, null);
      addTagAttributes("td", "element", emptyArgs, null);
      addTagAttributes("th", "element", emptyArgs, null);
      addTagAttributes("form", "element", new String[] {"action", "method", "onsubmit"}, new String[] {"action"});
      addTagAttributes("a", "element", new String[] {"href", "disabled"}, new String[] {"href"});
      addTagAttributes("script", "element", new String[] {"type", "src"}, new String[] {"src"});
      addTagAttributes("link", "element", new String[] {"rel", "type", "href"}, new String[] {"href"});
      addTagAttributes("img", "element", new String[] {"src", "width", "height", "alt"}, new String[] {"src"});
      addTagAttributes("style", "element", new String[] {"type"}, null);
      addTagAttributes("pre", "element", emptyArgs, null);
      addTagAttributes("code", "element", emptyArgs, null);
      addTagAttributes("em", "element", emptyArgs, null);
      addTagAttributes("strong", "element", emptyArgs, null);
      addTagAttributes("header", "element", emptyArgs, null);
      addTagAttributes("footer", "element", emptyArgs, null);
      addTagAttributes("meta", "element", emptyArgs, null);
      addTagAttributes("iframe", "element", new String[] {"src", "width", "height", "name", "sandbox", "seamless"}, new String[] {"src"});
      addTagAttributes("fieldset", "element", emptyArgs, null);
      addTagAttributes("legend", "element", emptyArgs, null);
      addTagAttributes("label", "element", new String[] {"for", "form"}, null);
      // One per document so no worrying about merging or allocating unique ids for them
      singletonTagNames.add("head");
      singletonTagNames.add("body");
      singletonTagNames.add("html");
   }

   private static void addMatchingTagNames(String prefix, Set<String> candidates) {
      for (String tagName:htmlAttributeMap.keySet()) {
         if (tagName.startsWith(prefix)) {
            if (tagName.equals("element"))
               continue;
            candidates.add(tagName);
         }
      }
   }

   static void addMatchingAttributeNames(String tagName, String prefix, Set<String> candidates) {
      Set<String> attList = getPossibleAttributesForTag(tagName);
      addMatchingNamesFromSet(attList, prefix, candidates);
      addMatchingNamesFromSet(behaviorAttributes, prefix, candidates);
      addMatchingNamesFromSet(notInheritedAttributes, prefix, candidates);
      addMatchingNamesFromSet(HTMLElement.domAttributes.keySet(), prefix, candidates);
   }

   private static void addMatchingNamesFromSet(Set<String> attributes, String prefix, Set<String> candidates) {
      for (String att:attributes) {
         if (prefix == null || att.startsWith(prefix))
            candidates.add(att);
      }
   }

   static HashSet<String> notInheritedAttributes = new HashSet<String>();
   {
      notInheritedAttributes.add("abstract");
      notInheritedAttributes.add("id");
   }

   static HashSet<String> behaviorAttributes = new HashSet<String>();
   {
      behaviorAttributes.add("visible");
      behaviorAttributes.add("component");
      behaviorAttributes.add("extends");
      behaviorAttributes.add("implements");
      behaviorAttributes.add("tagMerge");
      behaviorAttributes.add("bodyMerge");
      behaviorAttributes.add("repeat");
      behaviorAttributes.add("repeatWrapper");
      behaviorAttributes.add("repeatVarName");
      behaviorAttributes.add("abstract");
      behaviorAttributes.add("serverContent");
      behaviorAttributes.add("exec");
      behaviorAttributes.add("addBefore");
      behaviorAttributes.add("addAfter");
      behaviorAttributes.add("orderValue");
      behaviorAttributes.add("stateless");
      behaviorAttributes.add("cache");
      behaviorAttributes.add("scope");
      behaviorAttributes.add("needsRefresh");
      behaviorAttributes.add(ALT_ID);

      // For select only
      behaviorAttributes.add("optionDataSource");
      behaviorAttributes.add("selectedValue");
   }

   static HashMap<String,String> attributePropertyAliases = new HashMap<String, String>();
   {
      attributePropertyAliases.put("class", "HTMLClass");
   }
   static HashMap<String,String> propertyAttributeAliases = new HashMap<String, String>();
   {
      propertyAttributeAliases.put("HTMLClass", "class");
   }

   public static String mapAttributeToProperty(String name) {
      String res = attributePropertyAliases.get(name);
      if (res == null)
         res = name;
      return res;
   }

   public static String mapPropertyToAttribute(String name) {
      String res = propertyAttributeAliases.get(name);
      if (res == null)
         res = name;
      return res;
   }

   boolean isBehaviorAttribute(String name) {
      if (HTMLElement.isDOMEventName(name))
         return true;
      if (behaviorAttributes.contains(name))
         return true;
      if (tagName != null && tagName.equals("html") && name.startsWith("xmlns:"))
         return true;
      return false;
   }

   private static boolean isHtmlNamespace(String ns) {
      return ns.equals("wicket");
   }

   public Set<String> getPossibleAttributes() {
      if (tagName == null)
         return null;
      return getPossibleAttributesForTag(tagName);
   }

   public static Set<String> getPossibleAttributesForTag(String tagName) {
      if (tagName == null)
         return null;
      Set<String> res = htmlAttributeMap.get(tagName);

      String extTagName = tagExtendsMap.get(tagName);
      if (extTagName != null) {
         Set<String> extPossNames = getPossibleAttributesForTag(extTagName);
         if (extPossNames != null) {
            if (res == null)
               return extPossNames;
            TreeSet<String> newRes = new TreeSet<String>();
            newRes.addAll(res);
            newRes.addAll(extPossNames);
            return newRes;
         }
      }
      return res;
   }

   private static boolean isAttributeForTag(HashMap<String, Set<String>> attributeMap, String tagName, String name, boolean htmlAttribute) {
      tagName = tagName.toLowerCase();
      name = name.toLowerCase();

      Set<String> map = attributeMap.get(tagName);
      if (map != null) {
         if (map.contains(name))
            return true;
      }
      else if (htmlAttribute) {
         System.out.println("*** Warning unrecognized html tag name: " + tagName);
      }
      String extTag = tagExtendsMap.get(tagName);
      if (extTag != null)
         return isAttributeForTag(attributeMap, extTag, name, htmlAttribute);

      if (htmlAttribute) {
         int nsix = name.indexOf(":");
         if (nsix != -1) {
            String namespace = name.substring(0, nsix);
            if (isHtmlNamespace(namespace))
               return true;
         }
      }
      return false;
   }

   /** Return true for any attribute that needs to be rendered as there or not without any attribute based on a boolean expression */
   private static boolean isBooleanAttribute(String attName) {
      return attName.equalsIgnoreCase("checked") || attName.equalsIgnoreCase("multiple") || attName.equalsIgnoreCase("disabled");
   }

   public boolean isHtmlAttribute(String name) {
      return isAttributeForTag(htmlAttributeMap, tagName, name, true);
   }

   public boolean isLinkAttribute(String name) {
      return isAttributeForTag(linkAttributeMap, tagName, name, false);
   }

   public boolean isRefreshAttribute(String name) {
      return true; // For now assume all attributes are refreshed on the client.  If not, we can turn this on so that invalidateStartTag gets called.  Just need to write the "move" element trick if we need it but its much better to reuse the tag when attributes change
   }

   public boolean isReadOnlyAttribute(String name) {
      if (name.equalsIgnoreCase("id") || name.equalsIgnoreCase("name"))
         return true;
      return false;
   }

   public void invalidate() {
      invalidateStartTag();
      invalidateBody();
   }

   public StringBuilder output() {
      StringBuilder sb = new StringBuilder();
      outputTag(sb);
      return sb;
   }

   /** Just like output but when invoked on the server, evaluates the output on the client as a remote method call */
   @sc.obj.Exec(clientOnly=true)
   public StringBuilder output_c() {
      return output();
   }

   private int repeatElementIndexOf(Object repeat, int startIx, Object repeatVar) {
      int sz = PTypeUtil.getArrayLength(repeat);
      for (int i = startIx; i < sz; i++) {
         Object arrayVal = DynUtil.getArrayElement(repeat, i);
         if (arrayVal == repeatVar || (arrayVal != null && arrayVal.equals(repeatVar)))
            return i;
      }
      return -1;
   }

   /** A hook for tag objects to override to be notified when the repeat tags have has been updated */
   public void repeatTagsChanged() {}

   /** Returns true if any values have been removed, added or replaced in the repeat property for this tag.  */
   public boolean anyChangedRepeatTags() {
      Object repeatVal = getCurrentRepeatVal();
      int repeatValSz = repeatVal == null ? 0 : DynUtil.getArrayLength(repeatVal);
      int repeatTagsSz = repeatTags == null ? 0 : repeatTags.size();

      if (repeatValSz != repeatTagsSz)
         return true;

      for (int i = 0; i < repeatValSz; i++) {
         Object newElem = DynUtil.getArrayElement(repeatVal, i);
         Element oldTag = repeatTags.get(i);
         Object oldElem = oldTag.repeatVar;
         // Note: not checking the equals method here.  We really only care if there were new repeatVal elements added or
         // removed because those are the situations where we need to refresh the repeatTags list.
         if (newElem != oldElem)
            return true;
      }
      return false;
   }

   public boolean syncRepeatTags(Object repeatVal) {
      int sz = repeatVal == null ? 0 : DynUtil.getArrayLength(repeatVal);
      boolean anyChanges = false;
      boolean childChanges = false;

      repeatTagsValid = true;

      // TODO: remove this?  We can't disable sync entirely.  We need to turn it on before we call "output" since there can be side-effect changes
      // in there which need to be synchronized.  Now that we do not sync the page objects, this should not be needed anyway.
      // Since these changes are derived from other properties, disable the recording of syn changes here.
      //SyncManager.SyncState oldSyncState = SyncManager.getSyncState();
      try {
         //SyncManager.setSyncState(SyncManager.SyncState.Disabled);

         ArrayList<Element> tags = repeatTags;
         if (tags == null) {
            repeatTags = tags = new ArrayList<Element>(sz);
            for (int i = 0; i < sz; i++) {
               Object arrayVal = DynUtil.getArrayElement(repeatVal, i);
               if (arrayVal == null) {
                  System.err.println("Null value for repeat element: " + i + " for: " + this);
                  continue;
               }
               Element arrayElem = createRepeatElement(arrayVal, i, null);
               tags.add(arrayElem);
               childChanges = anyChanges = true;
            }
         }
         else {
            int renumberIx = -1;
            for (int i = 0; i < sz; i++) {
               Object arrayVal = DynUtil.getArrayElement(repeatVal, i);
               if (arrayVal == null) {
                  System.err.println("Null value for repeat element: " + i + " for: " + this);
                  continue;
               }
               int curIx = repeatElementIndexOf(repeatTags, 0, arrayVal);

               if (curIx == i) // It's at the right spot in repeatTags for the new value of repeat.
                  continue;

               if (i < repeatTags.size()) {
                  if (i >= tags.size())
                     System.out.println("*** Internal error in sync repeat tags!");
                  Element oldElem = tags.get(i);
                  Object oldArrayVal = oldElem.repeatVar;
                  // The arrayVal in this spot is not the arrayVal for the current tag.
                  if (oldArrayVal != arrayVal && (oldArrayVal == null || !oldArrayVal.equals(arrayVal))) {
                     anyChanges = true;
                     // The current array val is new to the list
                     if (curIx == -1) {
                        // Either replace or insert a row
                        int curNewIx = repeatElementIndexOf(repeatVal, i, oldArrayVal);
                        if (curNewIx == -1) {
                           Element newElem = createRepeatElement(arrayVal, i, oldElem);
                           if (oldElem == newElem) {
                              // The createRepeatElement method returned the same object.
                              // Reuse the existing object so this turns into an incremental refresh
                              oldElem.setRepeatIndex(i);
                              oldElem.setRepeatVar(arrayVal);
                           }
                           else {
                              // The createRepeatElement method returned a different object
                              // In this case, the newElem tag may not be the same so we need to replace the element.
                              tags.remove(i);
                              removeElement(oldElem, i);
                              tags.add(i, newElem);
                              insertElement(newElem, i);
                              childChanges = true;
                           }
                        }
                        else {
                           // Assert curNewIx > i - if it is less, we should have already moved it when we processed the old guy
                           Element newElem = createRepeatElement(arrayVal, i, null);
                           tags.add(i, newElem);
                           insertElement(newElem, i);
                           childChanges = true;
                        }
                     }
                     // The current guy is in the list but later on
                     else {
                        Element elemToMove = tags.remove(curIx);
                        // Try to delete our way to the old guy so this stays incremental.  But at this point we also delete all the way to the old guy so the move is as short as possible (and to batch the removes in case this ever is used with transitions)
                        int delIx;
                        boolean needsMove = false;
                        for (delIx = i; delIx < curIx; delIx++) {
                           Element delElem = tags.get(i);
                           Object delArrayVal = delElem.repeatVar;
                           int curNewIx = repeatElementIndexOf(repeatVal, i, delArrayVal);
                           if (curNewIx == -1) {
                              Element toRem = tags.remove(delIx);
                              removeElement(toRem, delIx);
                              if (renumberIx == -1)
                                 renumberIx = delIx;
                           }
                           else
                              needsMove = true;
                        }
                        // If we deleted up to the current, we are done.  Otherwise, we need to re-order
                        if (needsMove) {
                           renumberIx = i;
                           elemToMove.setRepeatIndex(i);
                           tags.add(i, elemToMove);
                           moveElement(elemToMove, curIx, i);
                        }
                        childChanges = true;
                     }
                  }
               }
               else {
                  childChanges = anyChanges = true;
                  if (curIx == -1) {
                     Element arrayElem = createRepeatElement(arrayVal, i, null);
                     tags.add(arrayElem);
                     appendElement(arrayElem);
                  }
                  // Otherwise need to move it into its new location.
                  else {
                     Element toMove = tags.get(curIx);
                     tags.add(i, toMove);
                     toMove.setRepeatIndex(i);
                     moveElement(toMove, curIx, i);
                  }
               }
            }

            while (tags.size() > sz) {
               int ix = tags.size() - 1;
               Element toRem = tags.remove(ix);
               removeElement(toRem, ix);
               childChanges = anyChanges = true;
            }
            // We removed one or more elements so make sure the repeatIndex is correct now
            if (renumberIx != -1) {
               int tagSz = tags.size();
               for (int r = renumberIx; renumberIx < tagSz; r++) {
                  Element tagElem = tags.get(r);
                  if (tagElem.getRepeatIndex() != r)
                     tagElem.setRepeatIndex(r);
               }
               if (this instanceof IRepeatWrapper)
                  ((IRepeatWrapper) this).updateElementIndexes(renumberIx);
            }
         }
      }
      finally {
         //SyncManager.setSyncState(oldSyncState);
      }
      if (anyChanges)
         repeatTagsChanged();

      return childChanges;
   }

   // These methods are implemented for JS where they update the DOM.
   public void appendElement(Element elem) {
   }

   public void insertElement(Element elem, int ix) {
   }

   public void removeElement(Element elem, int ix) {
      // Remove all of the bindings on all of the children when we remove the tag.  ?? Do we need to queue these up and do them later for some reason?
      DynUtil.dispose(elem, true);
   }

   public void moveElement(Element elem, int fromIx, int toIx) {
   }

   /** Before we can run the sync code this method gets called so we can populate any components */
   public void initChildren() {
      if (repeat != null || this instanceof IRepeatWrapper) {
         syncRepeatTags(repeat);
      }
   }

   /**
    * For application code to manually synchronize the repeat state of the tags.  The noRefresh flag applies to the client
    * which is doing incremental refresh... here on the server, we render the entire page at the end of the response so
    * it has no effect.
    */
   public void refreshRepeat(boolean noRefresh) {
      if (repeat != null)
         syncRepeatTags(repeat);
   }

   public boolean isRepeatTag() {
      Object repeatVal = getCurrentRepeatVal();
      return repeatVal != null || this instanceof IRepeatWrapper;
   }

   public void outputTag(StringBuilder sb) {
      if (!visible) {
         if (invisTags == null) {
            invisTags = getChildrenById(getElementId() + "_" + ALT_ID);
            if (invisTags == null)
               invisTags = EMPTY_ELEMENT_ARRAY;
         }
         for (Element et:invisTags)
            et.outputTag(sb);
         return;
      }

      // Even events which fired during the tag initialization or since we last were rendered must be flushed so our content is accurate.
      DynUtil.execLaterJobs();

      Object repeatVal = getCurrentRepeatVal();
      boolean isRepeatTag = isRepeatTag();

      boolean cacheEnabled = isCacheEnabled();
      if (!cacheEnabled) {
         // Just re-render all tags all of the time when cache is not enabled
         if (isRepeatTag) {
            outputRepeatBody(repeatVal, sb);
         }
         else {
            callOutputStartTag(sb);
            callOutputBody(sb);
            callOutputEndTag(sb);
         }
      }
      else {
         if (isRepeatTag) {
            if (!repeatTagsValid && syncRepeatTags(repeatVal)) {
               bodyTxtValid = false;
               invalidateBody();
            }
            if (isServerTag())
               outputRepeatTagMarker(sb);
            if (!bodyValid) {
               StringBuilder bodySB = new StringBuilder();
               outputRepeatBody(repeatVal, bodySB);
               bodyCache = bodySB.toString();
            }
            if (bodyCache != null)
               sb.append(bodyCache);
         }
         else {
            if (!startTagValid) {
               StringBuilder newSB = new StringBuilder();
               callOutputStartTag(newSB);
               String newStartTagCache = newSB.toString();

               // We're rendering the parent again.  If our startTagTxt has changed we need to record the change at this level so we can later
               // update just this property if it changes on its own.
               if (isServerTag() && !StringUtil.equalStrings(startTagCache, newStartTagCache))
                  SyncManager.updateRemoteValue(this, "startTagTxt", newStartTagCache);

               startTagCache = newStartTagCache;
            }
            if (startTagCache != null)
               sb.append(startTagCache);
            if (!bodyValid) {
               StringBuilder bodySB = new StringBuilder();
               callOutputBody(bodySB);
               String newBody = bodySB.toString();
               if (isServerTag() && !StringUtil.equalStrings(bodyCache, newBody))
                  SyncManager.updateRemoteValue(this, "innerHTML", newBody);
               bodyCache = newBody;
            }
            if (bodyCache != null)
               sb.append(bodyCache);
            callOutputEndTag(sb);
         }
      }
   }

   private void outputRepeatTagMarker(StringBuilder sb) {
      sb.append("<span id=\'" + getId() + "' style='display:none'></span>"); // Warning - span tag cannot be 'self-closed' - TODO: is there a better tag to use here?  Meta is only supposed to be in the head section.
   }

   private void outputRepeatBody(Object repeatVal, StringBuilder sb) {
      syncRepeatTags(repeatVal);
      markBodyValid(true);
      for (int i = 0; i < repeatTags.size(); i++) {
         repeatTags.get(i).outputTag(sb);
      }
   }

   private Object getCurrentRepeatVal() {
      if (dynObj == null) {
         return this instanceof IRepeatWrapper ? repeat : null;
      }
      else {
         return ModelUtil.isAssignableFrom(IRepeatWrapper.class, dynObj.getDynType()) ?
                                              dynObj.getPropertyFromWrapper(this, "repeat") : null;
      }
   }

   private void callOutputStartTag(StringBuilder sb) {
      if (this instanceof IRepeatWrapper)
         return; // No start tag for repeat wrapper
      if (dynObj == null)
         outputStartTag(sb);
      else
         dynObj.invokeFromWrapper(this, "outputStartTag", "Ljava/lang/StringBuilder;", sb);
   }

   private void callOutputBody(StringBuilder sb) {
      if (dynObj == null)
         outputBody(sb);
      else
         dynObj.invokeFromWrapper(this, "outputBody", "Ljava/lang/StringBuilder;", sb);
   }

   private void callOutputEndTag(StringBuilder sb) {
      if (this instanceof IRepeatWrapper)
         return;
      if (dynObj == null)
         outputEndTag(sb);
      else
         dynObj.invokeFromWrapper(this, "outputEndTag", "Ljava/lang/StringBuilder;", sb);
   }

   public void outputStartTag(StringBuilder sb) {
      startTagValid = true;
      sb.append("<");
      sb.append(lowerTagName());
      if (id != null) {
         sb.append(" id='");
         sb.append(id);
         sb.append("'");
      }
      sb.append(">");
   }

   public void outputBody(StringBuilder sb) {
      markBodyValid(true);
   }

   public void markBodyValid(boolean val) {
      bodyValid = val;
      bodyTxtValid = val;
      repeatTagsValid = val;
   }

   public void markStartTagValid(boolean val) {
      startTagValid = val;
   }

   public void outputEndTag(StringBuilder sb) {
      sb.append("</");
      sb.append(lowerTagName());
      sb.append(">");
   }

   /**
    * Returns the current body contents by regenerating it - so the Element api mirrors the DOM api and for synchronization.
    * This is read-only by default on the server but used for serverTags to transfer a change of body to the client.
    */
   public String getInnerHTML() {
      Object repeatVal = getCurrentRepeatVal();
      boolean cacheEnabled = isCacheEnabled();
      if (cacheEnabled) {
         if (isRepeatTag() && !repeatTagsValid && syncRepeatTags(repeat)) {
            bodyTxtValid = false;
            invalidateBody();
         }
         if (bodyValid) {
            return bodyCache == null ? "" : bodyCache;
         }
      }
      StringBuilder sb = new StringBuilder();
      if (repeatVal == null) {
         outputBody(sb);
      }
      else {
         // Do not include the repeatTagMarker in the innerHTML.  We will have rendered it already with the initial page and currently we just replace all of the
         // elements after it so if we send it again, we'd just have to remove it from the DOM.
         //if (isServerTag())
         //   outputRepeatTagMarker(sb);
         outputRepeatBody(repeatVal,  sb);
      }
      String newInnerHTML = sb.toString();
      if (cacheEnabled)
         bodyCache = newInnerHTML;
      return newInnerHTML;
   }

   public void setInnerHTML(String htmlTxt) {
      bodyCache = htmlTxt;
   }

   /** Returns the current contents of the startTagTxt, composed of the tag name and attributes - e.g. <input id="foo"> */
   public String getStartTagTxt() {
      if (this instanceof IRepeatWrapper)
         return "";
      boolean cacheEnabled = isCacheEnabled();
      if (cacheEnabled) {
         if (startTagValid)
            return startTagCache == null ? "" : startTagCache;
      }
      StringBuilder sb = new StringBuilder();
      outputStartTag(sb);
      String newStartTagTxt = sb.toString();
      if (cacheEnabled)
         startTagCache = newStartTagTxt;
      return newStartTagTxt;
   }

   public void setStartTagTxt(String newStartTxt) {
      startTagCache = newStartTxt;
   }

   public void setTextContent(String tc) {
      if (tc == null)
         setProperty("children", null, true);
      else {
         HTMLLanguage l = HTMLLanguage.getHTMLLanguage();
         Object res = l.parseString(tc, l.templateBodyDeclarations);
         if (res instanceof ParseError) {
            throw new IllegalArgumentException("Invalid text content for HTML node: " + tc);
         }
         // TODO: any consideration for the children nodes we are are wiping out?
         children = (SemanticNodeList<Object>) ParseUtil.nodeToSemanticValue(res);
         children.setParentNode(this);
      }
   }

   public boolean isEmptyBody() {
      return !needsBody && (children == null || (selfClose != null && selfClose));
   }

   public String getTextContent() {
      if (isEmptyBody())
         return "";
      return children.toLanguageString();
   }

   public Element[] getChildrenByIdAndType(String id, Class type) {
      if (id == null && type == null) {
         return getChildrenByIdAndType(null, Element.class);
      }
      if (id != null) {
         Element[] children = getChildrenById(id);
         return children;
      }
      ArrayList<Element> res = null;
      Object[] cs = getObjChildren(true);
      if (cs != null) {
         for (int i = 0; i < cs.length; i++) {
            Object child = cs[i];
            if (child instanceof Element && type.isInstance(child)) {
               if (res == null) {
                  res = new ArrayList<Element>(cs.length);
               }
               res.add((Element) child);
            }
         }
      }
      if (res == null)
         return null;
      return res.toArray(new Element[res.size()]);
   }

   public Element[] getChildrenById(String id) {
      if (childrenById == null) { // NOTE: used to only do this if children or hiddenChildren were set by that doesn't work for runtime elements since we never parsed those, but the instance does implement getObjChildren
         childrenById = new TreeMap<String,Element[]>();
         addChildListToByIdMap(this.getObjChildren(true), id);
      }
      if (childrenById == null)
         return null;
      return childrenById.get(id);
   }

   public void addChildListToByIdMap(Object[] childList, String id) {
      if (childList != null) {
         for (Object child:childList) {
            if (child instanceof Element) {
               Element elem = (Element) child;
               String childId = elem.getElementId();
               if (childId == null)
                  continue;
               Element[] oldList = childrenById.get(childId);
               Element[] newList;
               if (oldList == null) {
                  oldList = new Element[1];
                  newList = oldList;
                  newList[0] = elem;
               }
               else {
                  int oldLen = oldList.length;
                  newList = new Element[oldLen + 1];
                  System.arraycopy(oldList, 0, newList, 0, oldList.length);
                  newList[oldLen] = elem;
               }
               childrenById.put(childId, newList);
            }
         }
      }
   }

   public void setId(String _id) {
      this.id = _id;
   }

   @Constant
   public String getId() {
      return id;
   }

   private String getFixedAttribute(String attName) {
      Attr attr = getAttribute(attName);
      if (attr != null) {
         if (PString.isString(attr.value)) {
            return attr.value.toString();
         }
         else {
            displayError("Attribute: " + attName + " must be a constant value");
         }
      }
      return null;
   }

   public MergeMode getMergeAttribute(String attName) {
      String value = getFixedAttribute(attName);
      if (value != null) {
         MergeMode mode = MergeMode.fromString(value);
         if (mode == null)
            displayError("Invalid: " + attName + " not a valid merge mode. " + " must be one of: " + MergeMode.values());
         return mode;
      }
      else
         return null;
   }

   public MergeMode getSubTagMerge() {
      MergeMode mode = getRawSubTagMerge();
      if (mode == null) {
         Element parTag = getEnclosingTag();
         if (parTag != null) {
            MergeMode subTagMode;

            subTagMode = parTag.getRawTagMerge();
            if (subTagMode != null)
               return subTagMode;

            subTagMode = parTag.getSubTagMerge();
            if (subTagMode != null)
               return subTagMode;
         }
         else
            return MergeMode.Merge;
      }
      return mode;
   }

   public MergeMode getRawTagMerge() {
      return getMergeAttribute("tagMerge");
   }

   public MergeMode getBodyMergeMode() {
      MergeMode mode = getMergeAttribute("bodyMerge");
      if (mode == null) {
         Element parTag = getEnclosingTag();
         if (parTag != null)
            return parTag.getSubTagMerge();
         else
            return MergeMode.Merge;
      }
      return mode;
   }

   public MergeMode getRawSubTagMerge() {
      return getMergeAttribute("subTagMerge");
   }

   public MergeMode getTagMergeMode() {
      MergeMode mode = getRawTagMerge();
      if (mode == null) {
         Element parTag = getEnclosingTag();
         if (parTag != null)
            return parTag.getSubTagMerge();
         else
            return MergeMode.Merge;
      }
      return mode;
   }

   public Object findType(String name, Object refType, TypeContext context) {
      if (refType != this) {
         if (tagObject != null) {
            Object res = tagObject.findType(name, refType, context);
            if (res != null)
               return res;
            res = findInnerTypeInChildList(children, name);
            if (res == null)
               res = findInnerTypeInChildList(hiddenChildren, name);
            if (res != null)
               return res;
         }
      }
      return super.findType(name, refType, context);
   }

   public Object findMethod(String name, List<? extends Object> parametersOrExpressions, Object fromChild, Object refType, boolean staticOnly, Object inferredType) {
      if (refType != this) {
         if (tagObject != null) {
            Object meth = tagObject.findMethod(name, parametersOrExpressions, fromChild, refType, staticOnly, inferredType);
            if (meth != null)
               return meth;
         }
         else {
            Object res = ModelUtil.definesMethod(Element.class, name, parametersOrExpressions, null, refType, false, staticOnly, inferredType, null, getLayeredSystem());
            if (res != null)
               return res;
         }
      }
      return super.findMethod(name, parametersOrExpressions, fromChild, refType, staticOnly, inferredType);
   }

   private Object findInnerTypeInChildList(SemanticNodeList<Object> childList, String name) {
      if (childList != null) {
         for (Object childObj:childList) {
            if (childObj instanceof Element) {
               Element childElement = (Element) childObj;
               String childId = childElement.getElementId();
               if (childId != null && childId.equals(name)) {
                  if (childElement.convertingToObject)
                     displayError("Recursive extend for: " + name + " in: " + tagName + " for: ");
                  else {
                     TypeDeclaration childType = childElement.convertToObject(getEnclosingTemplate(), tagObject, null, null, null);
                     if (childType != null)
                        return childType;
                  }
               }
            }
         }
      }
      return null;
   }

   public String toString() {
      String elemId = id;
      if (elemId == null) {
         Attr id = getAttribute("id");
         if (id != null && id.value instanceof String)
            elemId = (String) id.value;
      }
      return "<" + tagName + (elemId != null ? " id=" + elemId : "") + (selfClosed() ? "/>" : ">") + (children != null ? "...</" + tagName + ">" : "");
   }

   public String toDeclarationString() {
      return toString();
   }

   public String getUserVisibleName() {
      return tagName == null ? super.getUserVisibleName() : tagName;
   }

   public Object findMember(String name, EnumSet<MemberType> mtype, Object fromChild, Object refType, TypeContext ctx, boolean skipIfaces) {
      String repeatVar = getRepeatVarName();
      if (repeatVar != null && name.equals(repeatVar)) {
         if (tagObject != null)
            return tagObject.findMember(name, mtype, fromChild, refType, ctx, skipIfaces);

         Object repeatMember = findMember("repeatVar", mtype, fromChild, refType, ctx, skipIfaces);
         if (repeatMember != null) {
            Object repeatElemType = getRepeatElementType();
            FieldDefinition repeatVarField = FieldDefinition.create(getLayeredSystem(), repeatElemType, repeatVar);
            repeatVarField.parentNode = this;
            ParseUtil.initAndStartComponent(repeatVarField);
            return repeatVarField.variableDefinitions.get(0);
         }
         // Unless we can implement the "RE" (repeat element type-parameter) scheme this won't preserve the type.  It's only for the non-tag object case though
         // so not sure it's important.   Maybe we could just create a ParamTypedMember here and return that if so?
         return repeatMember;
      }

      Object o = definesMember(name, mtype, refType, ctx, skipIfaces, false);
      if (o != null)
         return o;

      return super.findMember(name, mtype, fromChild, refType, ctx, skipIfaces);
   }

   public Object definesMember(String name, EnumSet<MemberType> mtype, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed) {
      Object o;
      if (tagObject != null) {
         o = tagObject.definesMember(name, mtype, refType, ctx, skipIfaces, isTransformed);
         if (o != null)
            return o;
      }
      o = definesMemberInChildList(children, name, mtype, refType, ctx, skipIfaces, isTransformed);
      if (o != null)
         return o;
      o = definesMemberInChildList(hiddenChildren, name, mtype, refType, ctx, skipIfaces, isTransformed);
      if (o != null)
         return o;
      if (tagObject == null) {
         Object extType = getExtendsTypeDeclaration();
         o = ModelUtil.definesMember(extType, name, mtype, refType, ctx, skipIfaces, isTransformed, getLayeredSystem());
         if (o != null)
            return o;
      }
      if (mtype.contains(MemberType.Variable) && name.equals("out")) {
         Template template = getEnclosingTemplate();
         if (template != null) {
            Parameter param = template.getDefaultOutputParameters();
            param.parentNode = this;
            return param;
         }
      }
      o = super.definesMember(name, mtype, refType, ctx, skipIfaces, isTransformed);
      if (o == null && repeatWrapper != null) {
         o = repeatWrapper.definesMember(name, mtype, refType, ctx, skipIfaces, isTransformed);
      }
      return o;
   }

   private Object definesMemberInChildList(SemanticNodeList<Object> childList, String name, EnumSet<MemberType> mtype, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed) {
      Object o;
      if (childList != null) {
         for (Object td:childList) {
            if (td instanceof TemplateStatement) {
               if ((o = ((TemplateStatement) td).definesMember(name, mtype, refType, ctx, skipIfaces, isTransformed)) != null)
                  return o;
            }
            else if (td instanceof TemplateDeclaration) {
               if ((o = ((TemplateDeclaration) td).definesMember(name, mtype, refType, ctx, skipIfaces, isTransformed)) != null)
                  return o;
            }
         }
      }
      return null;
   }

   public LayeredSystem getLayeredSystem() {
      LayeredSystem sys = super.getLayeredSystem();
      if (sys == null)
         return LayeredSystem.getCurrent();
      return sys;
   }

   public List<String> getJSFiles() {
      LayeredSystem sys = getLayeredSystem();
      if (sys == null) {
         System.out.println("*** No Element.JSFiles - no layered system!");
         return null;
      }
      // First see if we are contained in an enclosing tag and just defer the source of js files to the parent tag.
      Element parent = getEnclosingTag();
      if (parent != null)
         return parent.getJSFiles();

      JSRuntimeProcessor jsRT = (JSRuntimeProcessor) sys.getRuntime("js");
      if (jsRT != null) {
         // Then find the type associated with this tag, look up the JS files associated with the root type of that type.
         Object decl = getTagType();
         if (decl != null) {
            Object declRoot = DynUtil.getRootType(decl);
            if (declRoot == null)
               declRoot = decl;
            List<String> fileList = jsRT.getJSFiles(declRoot);
            if (fileList != null) {
               ArrayList<String> res = new ArrayList<String>(fileList);
               Template templ = getEnclosingTemplate();
               for (int i = 0; i < res.size(); i++) {
                  String jsFilePath = res.get(i);
                  // We need to use relative paths for client-only or prepend the absolute path of the document root here
                  if (sys.serverEnabled && !jsFilePath.startsWith("/")) {
                     if (!sys.postBuildProcessing)  // We don't technically need this extra test... it was for the static file based websites like the doc which are built with a server, the JS links would not work from the file system, but would when you put them onto a web server.
                        jsFilePath = "/" + jsFilePath;
                     else {
                        // If this tag is not defined in a top-level directory, we need to prepend ../../ to find the js files which are relative to the root.
                        jsFilePath = FileUtil.concat(getRelPrefix(""), jsFilePath);
                     }
                  }
                  res.set(i, jsFilePath);
               }
               return res;
            }
            return null;
         }

      }
      else {
         if (serverTag) {
            Object type = getTagType();
            if (type != null) {
               String jsFiles = (String) DynUtil.getInheritedAnnotationValue(type, "sc.obj.ServerTagSettings", "jsFiles");
               if (jsFiles == null)
                  return null;
               String[] jsFilesArr = jsFiles.split(",");
               ArrayList<String> res = new ArrayList<String>(jsFilesArr.length);
               for (int i = 0; i < jsFilesArr.length; i++) {
                  String jsFilePath = jsFilesArr[i];
                  // TODO: code copied from above - move to a separate method
                  if (sys.serverEnabled && !jsFilePath.startsWith("/")) {
                     if (!sys.postBuildProcessing)
                        jsFilePath = "/" + jsFilePath;
                     else {
                        jsFilePath = FileUtil.concat(getRelPrefix(""), jsFilePath);
                     }
                  }
                  res.add(jsFilePath);
               }
               return res;
            }
         }
      }
      return null;
   }

   private Object getTagType() {
      LayeredSystem sys = getLayeredSystem();
      // Then find the type associated with this tag, look up the JS files associated with the root type of that type.
      Object decl = tagObject;
      if (decl == null) {
         if (dynObj != null)
            decl = dynObj.getDynType();
         else {
            decl = sys.getRuntimeTypeDeclaration(DynUtil.getTypeName(DynUtil.getType(this), false));
         }
      }
      return decl;
   }

   /** For now, this just handles converting relative URL paths so a path relative to a template in one directory can be
    * extended, or included into a template in a different directory.
    * TODO: should we have a mode where we specify a mapping from urls specified as path names to some other abstract name.  If so, we can use that table here to figure out which
    * external URL corresponds to a given internal path. */
   public static String getRelURL(String srcRelPath, String urlPath) {
      return URLUtil.concat(getRelPrefix(srcRelPath), urlPath);
   }

   /** In the generated code, we'll call this method to find the relative directory prefix to prepend (if any) for a reference from this src path in the tree.  */
   public static String getRelPrefix(String srcRelPath) {
      if (srcRelPath == null)
         srcRelPath = "";
      Window window = Window.getWindow();
      if (window == null)
         return srcRelPath;
      String curRelPath = window.location.pathname;
      if (curRelPath != null) {
         if (curRelPath.endsWith("/") && curRelPath.length() > 1)
            curRelPath = curRelPath.substring(0, curRelPath.length() - 1);
         curRelPath = URLUtil.getParentPath(curRelPath);
      }
      if (curRelPath == null || curRelPath.equals(srcRelPath))
         return srcRelPath;
      // We need to return a prefix for a relative url reference which will resolve to srcRelPath but in the context of curRelPath.
      String[] curRelDirs = curRelPath.split("/");
      String[] srcRelDirs = srcRelPath.length() == 0 ?  StringUtil.EMPTY_STRING_ARRAY : srcRelPath.split("/");

      int matchIx = -1;
      int matchLen = Math.min(curRelDirs.length, srcRelDirs.length);
      for (int i = 0; i < matchLen; i++) {
         if (curRelDirs[i].equals(srcRelDirs[i]))
            matchIx = i;
         else
            break;
      }
      StringBuilder relPath = new StringBuilder();
      for (int i = curRelDirs.length-1; i > matchIx; i--) {
         relPath.append("../");
      }
      for (int i = matchIx+1; i < srcRelDirs.length; i++) {
         String srcRelDir = srcRelDirs[i];
         if (srcRelDir.length() > 0) {
            relPath.append(srcRelDir);
            relPath.append("/");
         }
      }
      return relPath.toString();
   }

   public List<String> getAllJSFiles() {
      LayeredSystem sys = getLayeredSystem();
      if (sys == null) {
         System.out.println("*** allJSFiles - no layered system!");
         return null;
      }
      JSRuntimeProcessor jsRT = (JSRuntimeProcessor) sys.getRuntime("js");
      if (jsRT != null) {
         ArrayList<String> res = new ArrayList<String>();
         res.addAll(jsRT.jsBuildInfo.jsFiles);
         // For now, remove this by hand in a copy.  It gets added back in when we start a template type during eval and that type goes through the JSRuntimeProcessor's start method.  Probably should not be doing that...
         res.remove("<default>");
         return res;
      }
      return null;
   }

   /** Returns the set of types which are either mainInit elements (for JS) or which have the URL annotation (for server based components) */
   public List<URLPath> getURLPaths() {
      LayeredSystem sys = getLayeredSystem();
      if (sys == null) {
         System.err.println("*** No layeredSystem for URLPaths in Element : ");
         return null;
      }
      return sys.buildInfo.getURLPaths();
   }


   transient public String HTMLClass;

   public String getHTMLClass() {
      return HTMLClass;
   }

   // In the source this is called 'class' so use that name in the editor
   @sc.obj.EditorSettings(displayName="class")
   public void setHTMLClass(String cl) {
      HTMLClass = cl;
      Bind.sendChangedEvent(this, "HTMLClass");
   }

   transient public String style;

   public String getStyle() {
      return style;
   }

   public void setStyle(String s) {
      style = s;
      Bind.sendChangedEvent(this, "style");
   }

   // No need to transform the element and it's children since it did it's work in convertToObject.
   public boolean transform(ILanguageModel.RuntimeType rt) {
      return true;
   }

   // SemanticNode's compute equals by recursively matching all nodes in the tree which is both expensive and
   // problematic with elements given the recursive structure, where a sub-element can extend a parent type.
   public int hashCode() {
      return System.identityHashCode(this);
   }

   // TODO: this breaks the ability to compare two semantic values against each other using equals.
   public boolean equals(Object other) {
      return this == other;
   }

   public String getFullTypeName() {
      JavaModel model = getJavaModel();
      Element enclTag = getEnclosingTag();
      String name = getObjectName();
      while (enclTag != null) {
         name = CTypeUtil.prefixPath(enclTag.getObjectName(), name);
         enclTag = enclTag.getEnclosingTag();
      }
      String res = CTypeUtil.prefixPath(model.getPackagePrefix(), name);
      return res;
   }

   // Available on the client only where we have a real DOM element.
   @Bindable(manual=true)
   public int getOffsetWidth() {
      return -1;
   }
   @Bindable(manual=true)
   public int getOffsetHeight() {
      return -1;
   }
   @Bindable(manual=true)
   public int getOffsetTop() {
      return -1;
   }
   @Bindable(manual=true)
   public int getOffsetLeft() {
      return -1;
   }

   @Bindable(manual=true)
   public int getInnerWidth() {
      return -1;
   }

   @Bindable(manual=true)
   public int getInnerHeight() {
      return -1;
   }

   // TODO: implement these.  right now only used on the client.
   public Element getPreviousElementSibling() {
      return null;
   }

   @Bindable(manual=true)
   public boolean getHovered() {
      return false;
   }

   public void setHovered(boolean ignored) {
   }


   // TODO: make escAtt and escBody static?
   public String escAtt(CharSequence in) {
      if (in == null)
         return null;
      return StringUtil.escapeQuotes(in);
   }

   public String escBody(Object in) {
      if (in == null)
         return null;
      return StringUtil.escapeHTML(in.toString(), false).toString();
   }

   public void resetTagObject() {
      tagObject = null;
      convertingToObject = false;
      repeatWrapper = null;
      if (children != null) {
         for (Object child:children) {
            if (child instanceof Element) {
               ((Element) child).resetTagObject();
            }
         }
      }
      if (attributeList != null) {
         for (Attr att:attributeList)
            att.resetAttribute();
      }
   }

   public Element refreshNode() {
      if (tagObject != null) {
         TypeDeclaration refreshedNode = (TypeDeclaration) tagObject.refreshNode();
         if (refreshedNode != null) {
            Element newElem = refreshedNode.element;
            if (newElem != null)
               return newElem;
         }
      }
      return this;
   }

   public int suggestCompletions(String prefix, Object currentType, ExecutionContext ctx, String command, int cursor, Set<String> candidates, Object continuation) {
      if (children != null) {
         // This is in the inside body of the tag.  Until we start a new tag, it's just template text right?
         return -1;
      }
      if (tagName != null) {
         addMatchingTagNames(tagName, candidates);
         return 0;
      }
      return -1;
   }

   public String addNodeCompletions(JavaModel origModel, JavaSemanticNode origNode, String extMatchPrefix, int offset, String dummyIdentifier, Set<String> candidates, boolean nextNameInPath) {
      if (tagName == null)
         return null;

      String matchPrefix = null;
      int ix = tagName.indexOf(dummyIdentifier);
      if (ix != -1) {
         matchPrefix = tagName.substring(0, ix);
         addMatchingTagNames(matchPrefix, candidates);
      }
      else {
         if (attributeList != null) {
            for (Attr att:attributeList) {
               String res = att.addNodeCompletions(origModel, origNode, extMatchPrefix, offset, dummyIdentifier, candidates, nextNameInPath);
               if (res != null)
                  return res;
            }
         }
      }
      return matchPrefix;
   }

   public void setNodeName(String newName) {
      setProperty("tagName", newName);
   }

   // TODO: should this be the type name of the tag object? - i.e. the value of the id
   public String getNodeName() {
      return tagName;
   }

   public String toListDisplayString() {
      return toString();
   }

   /** This method has to be here so we properly remap JS references to here instead of SemanticNode.stop which does not exist in the JS runtime */
   public void stop() {
      super.stop();
      // NOTE: this probably already happens from JavaModel's types array which contains tagObject but just in case
      // that's not up-to-date, stop the tagObject here as well.
      if (tagObject != null) {
         tagObject.stop();
      }
      if (repeatWrapper != null)
         repeatWrapper.stop();
      if (hiddenChildren != null)
         hiddenChildren.stop();
      if (inheritedAttributes != null)
         inheritedAttributes.stop();
      // Do we need to do this?  Children should be a semantic node but for some reason it does not seem to be stopping all of the children
      if (children != null) {
         for (Object child:children) {
            if (child instanceof ILifecycle)
               ((ILifecycle) child).stop();
         }
      }
      extendsTypeDecl = null;
      defaultExtendsType = null;
      cachedObjectName = null;
      childrenById = null;
      tagObject = null;
      hiddenChildren = null;
      modifyType = null;
      id = null;
      specifiedId = false;
      repeatWrapper = null;
      startTagValid = false;
      bodyValid = false;
      needsSuper = false;
      needsBody = false;
      convertingToObject = false;
   }

   public TypeDeclaration getElementTypeDeclaration() {
      return tagObject;
   }

   public int getCloseStartTagOffset() {
      IParseNode pn = getParseNode();
      // Finds the start index of the parse-node which was produced by the child of this node's parselet so we know where the end
      // of the start tag for the element began.  We might parse this with either of these symbol parselets depending
      int ix = pn.getChildStartOffset(((HTMLLanguage) pn.getParselet().getLanguage()).reqEndTagChar);
      if (ix == -1)
         ix = pn.getChildStartOffset(((HTMLLanguage) pn.getParselet().getLanguage()).endTagChar);
      return ix;
   }

   public TypeDeclaration getRepeatWrapperType() {
      return repeatWrapper;
   }

   // Part of IRepeatWrapper - to manage changes in the ordering of the tags
   public void updateElementIndexes(int fromIx) {
   }

   public Element deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap) {
      Element res = (Element) super.deepCopy(options, oldNewMap);
      //if ((options & ISemanticNode.CopyInitLevels) != 0) {
         // Not copying tagObject here - it needs to be cloned at the root and then tagObjects assigned as a second pass
      //}
      return res;
   }

   public boolean isEventSource() {
      return false;
   }

   public ServerTag serverTagInfo = null;

   public ServerTag getServerTagInfo() {
      if (serverTagInfo != null)
         return serverTagInfo;

      BindingListener[] listeners = Bind.getBindingListeners(this);
      ServerTag stag = null;
      String id = getId();
      if (id != null) {
         stag = new ServerTag();
         stag.id = id;
         stag.eventSource = isEventSource();
      }
      if (listeners != null) {
         stag = addServerTagProps(listeners, stag, HTMLElement.domAttributes);
         // TODO: Do we need to add these to indicate the need for immediate versus non-immediate?
         //stag = addServerTagProps(listeners, stag, getCustomServerTagProps());

      }
      if (stag != null) {
         serverTagInfo = stag;
      }

      return stag;
   }

   private ServerTag addServerTagProps(BindingListener[] listeners, ServerTag stag, Map<String,IBeanMapper> propsMap) {
      if (propsMap == null)
         return stag;
      for (Map.Entry<String,IBeanMapper> domProp:propsMap.entrySet()) {
         BindingListener listener = Bind.getPropListeners(this, listeners, domProp.getValue());
         if (listener != null) {
            if (stag == null) {
               stag = new ServerTag();
               stag.id = getId();
            }
            if (stag.props == null)
               stag.props = new ArrayList<Object>();
            // Since we have a listener for one of the DOM events - e.g. clickEvent, we need to send the registration over to the client
            // so it knows to sync with that change.
            stag.eventSource = true;
            //stag.immediate = true;
            // TODO also add SyncPropOption here if we want to control immediate or other flags we add to how
            // to synchronize these properties from client to server?
            stag.props.add(domProp.getKey());
         }
      }
      return stag;
   }

   public Map<String,IBeanMapper> getCustomServerTagProps() {
      return null;
   }

   public Map<String,ServerTag> addServerTags(ScopeDefinition scopeDef, Map<String,ServerTag> serverTags, boolean defaultServerTag) {
      if (defaultServerTag || serverTag) {

         String tagId = getId();
         if (tagId == null && isSingletonTag() && tagName != null) {
            tagId = tagName;
         }
         if (tagId != null) {
            ServerTag serverTag = getServerTagInfo();
            if (serverTag != null) {
               // This call only returns the server tags which need to be sent to the client.  All server tags though will be registered in the sync system
               if (serverTag.eventSource) {
                  if (serverTags == null)
                     serverTags = new LinkedHashMap<String,ServerTag>();

                  serverTags.put(tagId, serverTag);
               }

               if (scopeDef != null) {
                  ScopeContext scopeCtx = scopeDef.getScopeContext(true);
                  if (scopeCtx != null) {
                     Object oldValue = scopeCtx.getValue(tagId);
                     if (oldValue != null) {
                        if (oldValue != this) {
                           System.err.println("*** Conflicting value for tag id: " + tagId + " in scope: " + scopeDef.name);
                        }
                     }
                     else {
                        // This starts out only being set on the parent from the code-generation perspective but we need it set on
                        // every synchronized object.
                        this.serverTag = true;

                        scopeCtx.setValue(tagId, this);
                        // Register this with the sync system so we can apply changes made on the client and detect changes
                        // made on the server to send back to the client.  Using the DOM element id so the sync results are traceable
                        // and those should be unique cause they are already a global name space used by this page.
                        // TODO: if we have name conflicts, we could pass the DOM element id in the ServerTagInfo as well as the object name
                        SyncManager.registerSyncInst(this, tagId, scopeDef.scopeId, true);
                     }
                  }
               }
            }
         }
         else {
            // No id is normal for the 'repeat wrapper' class
            if (!(this instanceof IRepeatWrapper))
               System.out.println("*** null id for repeat tag");
            // else - TODO: is this a case we want to handle?
         }
         defaultServerTag = true;
      }
      Object[] children = getObjChildren(false);
      if (children != null) {
         for (Object child:children) {
            if (child instanceof Element) {
               serverTags = ((Element) child).addServerTags(scopeDef, serverTags, defaultServerTag);
            }
         }
      }
      return serverTags;
   }


   /**
    * Called on the client only.  It will create or update a tag object based on the DOM element id.   When a ServerTag is provided, it provides additional info on what
    * the server needs the client to do, in order to synchronize properties of the server tag object.  For example, it includes properties with bindings on the server
    * the client knows which DOM events to listen to, in order to send the appropriate changes to the server.
    */
   public static Element updateServerTag(Element tagObj, String id, ServerTag stag, boolean addSync) {
      System.err.println("*** updateServerTag called on server - it is a client-only method used to create, update and get the server's subscription for a specific DOM tag");
      return null;
   }

   /**
    * This is a step we do after refreshing all of the bindings before rendering to improve incremental refresh efficiency.
    * If any 'repeat' properties have changed, we'll now go through and
    * determine if the repeat tag's body has changed or perhaps we can handle any repeat tag changes incrementally through the children.  This should be called
    * before fireChangedTagEvents which triggers the innerHTML property changes for incremental refresh.
    * This is a simple approach for now until we handle selective update of the elements of the list.  Right now if the class or structure of a child
    * has changed,
    */
   /*
   public void validateTags() {
      if (isRepeatTag()) {
         if (!repeatTagsValid && syncRepeatTags(getCurrentRepeatVal())) {
            bodyTxtValid = false;
            invalidateBody();
         }
      }

      Object[] children = getObjChildren(false);
      if (children != null) {
         for (Object child:children) {
            if (child instanceof Element) {
               ((Element) child).validateTags();
            }
         }
      }
   }
   */

   public void fireChangedTagEvents(boolean parentBodyChanged) {
      if (isRepeatTag()) {
         if (!repeatTagsValid) {
            if (syncRepeatTags(getCurrentRepeatVal())) {
               bodyTxtValid = false;
               bodyValid = false;
            }
         }
      }
      // TODO: this syncMode stuff here was a previous attempt to fix the bug for nested tag content.  The problem was that
      // when we validate the parent we would update the cached state of the children so there was no way to detect the child change
      // events.  Move that logic to the 'outputTag' method where we see a change in the text.
      SyncManager.SyncState oldSyncState = null;
      boolean setSyncModeToPrev = false;
      if (!startTagValid) // some attribute or other contents of the start tag for this tag object changed - so fire this change event.  The value is retrieved with getStartTagTxt()
         Bind.sendEvent(IListener.VALUE_CHANGED, this, _startTagTxtProp);
      if (!bodyTxtValid) { // Some part of our body txt has changed so fire the innerHTML property change event.  Anyone interested in that event can call getInnerHTML() which will mark bodyTxtValid
         Bind.sendEvent(IListener.VALUE_CHANGED, this, _innerHTMLProp);
         if (!parentBodyChanged) {
            setSyncModeToPrev = true;
            parentBodyChanged = true;
         }
      }

      // formerly: bodyValid && !parentBodyChanged
      if (bodyValid)
         return;

      if (!visible)
         return;

      //if (setSyncModeToPrev)
      //   oldSyncState = SyncManager.setSyncState(SyncManager.SyncState.ApplyingChanges);

      try {
         Object[] children = getObjChildren(false);
         if (children != null) {
            for (Object child:children) {
               if (child instanceof Element) {
                  ((Element) child).fireChangedTagEvents(parentBodyChanged);
               }
            }
         }
      }
      finally {
         //if (setSyncModeToPrev)
         //   SyncManager.setSyncState(oldSyncState);
      }
   }

   /**
    * Can be called at startup by frameworks to allow synchronization of tag objects.  This is helpful for 'serverTags' which
    * are always rendered on the server, but the important properties can be synchronized to the client and parsed and managed with
    * a smaller code footprint, or to mix and match Javascript client tag objects with html server-side rendered objects in a single page.
    */
   public static void initSync() {
      SyncManager.addSyncType(Event.class, new SyncProperties(null, null, new Object[]{"type", "timeStamp", "currentTag"}, 0));
      // By default, we'll synchronize any body content this tag has in a read-only way.  When it changes, there's a change event for innerHTML
      // and getInnerHTML() generates the new contents.  Same idea with startTagTxt for attribute changes.
      // Specific DOM type subclasses (e.g. input) have custom attributes that are sync'd for server tags we
      SyncManager.addSyncType(Element.class, new SyncProperties(null, null, new Object[]{
            "startTagTxt", "innerHTML", "style", "class", "clickEvent", "dblClickEvent", "mouseDownEvent", "mouseOutEvent",
            "mouseUpEvent", "keyDownEvent", "keyUpEvent", "keyPressEvent", "focusEvent", "blurEvent"}, 0));
      SyncManager.addSyncType(Select.class, new SyncProperties(null, null, new Object[]{"selectedIndex"}, Element.class, 0));
      SyncManager.addSyncType(Input.class, new SyncProperties(null, null, new Object[]{"value", "checked", "changeEvent"}, Element.class, 0));
      SyncManager.addSyncType(Form.class, new SyncProperties(null, null, new Object[]{"submitEvent", "submitCount"}, Element.class, 0));
      SyncManager.addSyncType(Option.class, new SyncProperties(null, null, new Object[]{"selected"}, Element.class, 0));
   }

   public boolean isCacheEnabled() {
      if (serverTag) // server tags need the cache to be enable
         return true;
      if (cache == null || cache == CacheMode.Unset) {
         Element enclTag = getEnclosingTag();
         if (enclTag != null)
            return enclTag.isCacheEnabled();
         return false;
      }
      return cache == CacheMode.Enabled;
   }

}
