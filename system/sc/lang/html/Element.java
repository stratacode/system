/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.bind.Bind;
import sc.bind.Bindable;
import sc.dyn.DynUtil;
import sc.dyn.IObjChildren;
import sc.js.URLPath;
import sc.lang.*;
import sc.lang.java.*;
import sc.lang.js.JSRuntimeProcessor;
import sc.lang.sc.ModifyDeclaration;
import sc.lang.sc.PropertyAssignment;
import sc.lang.template.*;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.obj.*;
import sc.parser.PString;
import sc.parser.ParseError;
import sc.parser.ParseUtil;
import sc.sync.ISyncInit;
import sc.sync.SyncManager;
import sc.type.CTypeUtil;
import sc.type.PTypeUtil;
import sc.util.IdentityHashSet;
import sc.util.StringUtil;

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
// Turn off suynchronization for all of the tag info etc.  This stuff gets compiled into the generated clases and so is state that already exists on the client.
@Sync(syncMode= SyncMode.Disabled)
// Using the js_ prefix for the tags.  Because tags.js uses the SyncManager in the JS file, we need to add this dependency explicitly.
@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
@CompilerSettings(dynChildManager="sc.lang.html.TagDynChildManager")
public class Element<RE> extends Node implements ISyncInit, IStatefulPage, IObjChildren, ITypeUpdateHandler {
   public static boolean trace = false;

   public String tagName;
   public SemanticNodeList<Attr> attributeList;
   public SemanticNodeList<Object> children;
   public transient SemanticNodeList<Object> hiddenChildren;
   public transient SemanticNodeList<Attr> inheritedAttributes;
   // Set to true if this tag has a close symbol in the open tag
   public Boolean selfClose;
   // Should always match tagName via toLowerCase.  TODO: For the grammar, we should only need to store a boolean as to whether there's a close tag or not.  But we also need a read-only "closeTagName" property for generation and not sure how to specify that.  I suppose using different rules for generation than for parsing?
   public String closeTagName;

   private transient String id;

   private transient boolean specifiedId = false;

   public transient TypeDeclaration tagObject;

   private transient TypeDeclaration repeatWrapper;

   private transient Object modifyType;


   private transient TreeMap<String,Element[]> childrenById = null;

   private transient boolean startTagValid = false, bodyValid = false;

   private transient boolean needsSuper = false;
   private transient boolean needsBody = true;

   private transient Object defaultExtendsType = null;

   /** When processing a template once we hit a parent node whose type is not available in this mode, we insert a dummy node and only process static portsion of the template until some tag sets exec="process" to turn back on processing */
   public boolean staticContentOnly = false;

   private Object repeat;

   private boolean visible = true;

   // Applies to the client only - server content is not renderered on the client.  Diffs from the exec flags in that if you use exec="client" the code is not compiled into the client version at all.  When the client version
   // is used to generate a server-side html file, that's awkward cause you can't for example render the script tags and things that should only go on the server.
   public boolean serverContent = false;

   public boolean needsRefresh = false;

   private String cachedObjectName = null;

   public final static boolean nestedTagsInStatements = false;


   @Bindable(manual=true)
   public void setVisible(boolean vis) {
      visible = vis;
      invalidateStartTag();
      Bind.sendChangedEvent(this, "visible");
   }

   public boolean getVisible() {
      return visible;
   }

   /**
    * Typed either as a List<RE> or an RE[]
    *
    * This instance can be used in two modes - one as the controller of the array in which case repeat is set.  The other when it represents the element of the array and repeatVar is set to the element of the array.
    */
    public void setRepeat(Object rv) {
      repeat = rv;
      Bind.sendChangedEvent(this, "repeat");
   }

   public Object getRepeat() {
      return repeat;
   }

   private RE repeatVar;

   @Bindable(manual=true)
   public void setRepeatVar(RE rv) {
      repeatVar = rv;
      Bind.sendChangedEvent(this, "repeatVar");
   }

   public RE getRepeatVar() {
      return repeatVar;
   }

   private int repeatIndex = -1;

   @Bindable(manual=true)
   public void setRepeatIndex(int ix) {
      repeatIndex = ix;
      Bind.sendChangedEvent(this, "repeatIndex");
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

   private Element createRepeatElement(Object val, int ix) {
      Element res;
      boolean flush = false;
      flush = SyncManager.beginSyncQueue();
      // When this instance was created from a tag, create the element from the generated code.
      if (this instanceof IRepeatWrapper) {
         IRepeatWrapper wrapper = (IRepeatWrapper) this;
         res = wrapper.createElement(val, ix);
      }
      else if (dynObj != null) {
         res = (Element) dynObj.invokeFromWrapper(this, "createElement","Ljava/lang/Object;I", val, ix);
      }
      // If it was created by parsing an HTML file, do it by hand.  We will not have an enclosing instance in this case so the deepCopy will work even in that case.
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
         registerSyncInstAndChildren(res);
      }
      if (flush)
         SyncManager.flushSyncQueue();
      return res;
   }

   // The repeat element's identity is synchronized from the client to the server so mark those instances as
   // "not new" so the modify tag is sent.  In general, it seems best to create the children when we create the parent
   // instead of the first time on the refresh anyway.
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
      // Not checking serverContent on the tag itself because we do create that tag on client and server.  The child
      // tags for that tag though are not created and so should not be using symmetrics ids.
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

   // These ids shoud be consistent for a given page.  Since we traverse the object graphs in the same order on client and server hopefully they will be :)
   // Share the idSpaces map with the root tag.
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

   public void initialize() {
      super.initialize();
      if (tagObject != null && !tagObject.isInitialized()) {
         tagObject.initialize();
      }
   }

   public int getDefinedExecFlags() {
      String execStr = getFixedAttribute("exec");
      int execFlags = 0;
      if (execStr != null) {
         execStr = execStr.toLowerCase();
         execFlags = (execStr.indexOf("server") != -1 ? ExecServer : 0) | (execStr.indexOf("client") != -1 ? ExecClient : 0) | (execStr.indexOf("process") != -1 ? ExecProcess : 0);
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
      return HTMLLanguage.INDENTED_SET.contains(tagName);
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

   public boolean execOmitObject() {
      Template template = getEnclosingTemplate();
      int genFlags = template.getGenerateExecFlags();
      return (((genFlags & getComputedExecFlags()) == 0 && !childNeedsObject()) && genFlags == ExecServer);
   }

   /** Returns true if this tag needs an object definition */
   public boolean needsObject() {
      if (tagObject != null)
         return true;

      Template template = getEnclosingTemplate();
      if (template == null)
         return false;

      // When we are generating the server, if this says only put this tag on the client, we just omit the object.
      // If we are doing the client though, we need the object to tell us this is a server tag (so we include it properly when we render the parent)
      if (execOmitObject())
         return false;

      // Annotation layers do not generate types - they can be used in extends though
      Layer templLayer = template.getLayer();
      if (templLayer != null && templLayer.annotationLayer)
         return false;

      boolean needsObject = getElementId() != null || getAttribute("extends") != null || getAttribute("tagMerge") != null || getDynamicAttribute() != null || isRepeatElement();

      Object ctx = getEnclosingContext();
      // When not part of a top-level tag, we can't define the object structure.  We'll have to treat this case as elements having no state - just do normal string processing on the templates
      // TODO: Do we need to do anything to tags nested inside of TemplateStatements?  I.e. those that would be theoretically created/destroyed in the outputBody method.  We could do it perhaps
      // with inner types and an inline-destroy method?
      if (ctx instanceof GlueStatement || ctx instanceof GlueExpression) {
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
      }
      else
         System.out.println("*** Error: can't find tag under parent");
      // not reached
      throw new UnsupportedOperationException();
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
      if (id != null) // Explicitly named id - for example, the template file will rename the root element to match the file name if there's only one element in some cases
         return id;
      Attr idAttr = getAttribute("id");
      Attr nameAttr = getAttribute("name");
      if (idAttr != null && nameAttr != null)
         displayError("Invalid tag definition: use only one of id or name");
      else if (idAttr != null || nameAttr != null) {
         Attr useAttr = idAttr != null ? idAttr : nameAttr;
         Object val = useAttr.value;
         if (PString.isString(val))
            return CTypeUtil.escapeIdentifierString(val.toString());
         else
            displayError("Dynamic name/id attributes not supported");
      }
      return null;
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
         // Eliminate redundant setId calls in the generated code.  Look for the first tag in our hierarchy which specified the id
         // If it matches, return it.  This is particular important now because unique elements like head, body need to have a fixed id so if you
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
         else if (tagName.equalsIgnoreCase("form") && (getAttribute("submitCount") != null || getAttribute("submitEvent") != null) && getAttribute("onsubmit") == null)
            tagSpecial = " onSubmit=\"return false;\"";
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
            if (needsId()) {
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
    * For "OutputAll" and this is a dynamic tag (i.e. needs an object) it is added to the parent's outputTag method
    *      objectName.outputTag(sb);
    *
    * For static tags (deprecated), we add the expressions needed to render this tag as content in the parent method.
    *
    * For dynamic objects, we also call this method with just outputStart and outputBody.  The content for the object
    * then gets added to the outputStart and outputBody methods of the generated object for this tag.
    * */
   public int addToOutputMethod(TypeDeclaration parentType, BlockStatement block, Template template, int doFlags, SemanticNodeList<Object> uniqueChildren, int initCt, boolean statefulContext) {
      // Not processing this element for this template e.g. a server-only object which is being generated for the client
      boolean inactive = execOmitObject();

      int ix = initCt;

      boolean needsObject = needsObject();
      if (doFlags == doOutputAll && needsObject) {
         if (!isAbstract()) {
            String objName = isRepeatElement() ? getRepeatObjectName() : getObjectName();
            Statement st = IdentifierExpression.createMethodCall(getOutputArgs(template), this == template.rootType ? null : objName, "outputTag");
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
                  if ((isHtmlAttribute(att.name))) { // Do we need to draw thie attribute
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
                           strExprs.add(boolExpr);
                        }
                        else {
                           str.append(" ");
                           str.append(att.name);
                           str.append("=");
                           if (outputExpr == null || outputExpr instanceof StringLiteral) {
                              if (att.isString()) {
                                 str.append("\'");

                                 if (att.name.equals("id") && needsObject) {
                                    strExprs.add(StringLiteral.create(str.toString()));
                                    strExprs.add(IdentifierExpression.createMethodCall(new SemanticNodeList(), "getId"));
                                    str = new StringBuilder();
                                 }
                                 else {
                                    str.append(att.getOutputString());
                                 }
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
                              Expression outputExprCopy = (Expression) outputExpr.deepCopy(ISemanticNode.CopyNormal, null);
                              // If this is a comparison operator like foo != bar it needs to be wrapped to be combined with a + b + ...
                              if (outputExpr.needsParenWrapper())
                                 outputExprCopy = ParenExpression.create(outputExprCopy);

                              Object exprType = outputExpr.getTypeDeclaration();
                              if (exprType != null && ModelUtil.isString(exprType)) {
                                 SemanticNodeList<Expression> escArgs = new SemanticNodeList<Expression>();
                                 escArgs.add(outputExprCopy);
                                 Expression escExpr = IdentifierExpression.createMethodCall(escArgs, "escAtt");
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
                     displayWarning("Unknown attribute: ", att.name, " for tag: ");
                  }
               }
            }
            str = addExtraAttributes(str, strExprs);

            if (!inactive) {
               if (needsId()) {
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
         }

         if (uniqueChildren != null && (doFlags & doOutputBody) != 0) {
            if (str.length() > 0 && !inactive) {
               strExprs.add(StringLiteral.create(str.toString()));
               str = new StringBuilder();
            }
            if (strExprs.size() > 0) {
               for (Expression strExpr:strExprs) {
                  ix = template.addTemplateDeclToOutputMethod(parentType, block, strExpr, false, "Body", ix, this, statefulContext, false);
               }
               strExprs = new SemanticNodeList<Expression>();
            }

            for (Object child:uniqueChildren) {
               if ((staticContentOnly || inactive) && child instanceof Expression) {
                  Expression childExpr = (Expression) child;
                  childExpr.inactive = true;
               }
               else
                 ix = template.addTemplateDeclToOutputMethod(parentType, block, child, true, "Body", ix, this, statefulContext, true);
            }
         }
         if ((selfClose == null || !selfClose || needsBody) && closeTagName != null && (doFlags & doOutputEnd) != 0 && !inactive) {
            str.append("</");
            str.append(closeTagName);
            str.append(">");
         }

         if (str.length() > 0)
            strExprs.add(StringLiteral.create(str.toString()));

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
               displayError("Tag visible attribute must be a valid expression: ");
            }
            else
               tagExpr = QuestionMarkExpression.create(outExpr, tagExpr, StringLiteral.create(""));
         }

         if (tagExpr != null)
            ix = template.addTemplateDeclToOutputMethod(parentType, block, tagExpr, false, methSuffix, ix, this, statefulContext, false);
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
      List<?> tps = ModelUtil.getTypeParameters(tagType);

      ClassType ct = (ClassType) ClassType.create(ModelUtil.getTypeName(tagType));

      // We will optionally supply a parameter type to the extends type we create.  Only if the
      // base class has one.
      if (tps != null && tps.size() == 1 && ModelUtil.isTypeVariable(tps.get(0))) {
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
            res = getJavaModel().findTypeDeclaration(extStr, true, true);
            if (res == null) {
               displayError("No extends type: ", extStr, " for tag: ");
               res = findType(extStr, this, null);
               res = getJavaModel().findTypeDeclaration(extStr, true, true);
            }
         }
         return res;
      }
      return null;
   }

   public Object getSpecifiedExtendsTypeDeclaration() {
      Object res = getDeclaredExtendsTypeDeclaration();
      if (res != null)
         return res;
      return getDefaultExtendsTypeDeclaration(false);
   }

   private static HashSet<String> verboseBaseTypeNames = null;

   public Object getDefaultExtendsTypeDeclaration(boolean processable) {
      if (defaultExtendsType != null)
         return defaultExtendsType;
      LayeredSystem sys = getLayeredSystem();
      ArrayList<String> tagPackageList = sys == null ? new ArrayList<String>(0) : sys.tagPackageList;
      String thisPackage = getJavaModel().getPackagePrefix();
      int tagPackageStart = 0;

      // If we are in a tag-class that's already a top-level class and looking up from a package that's already in the tag package list, look starting from where this guy is.
      // If we are looking up an inner type that happens to be in the same package, we still need to pick up the most specific version of that type or layered inheritance does nto work.
      if (thisPackage != null && getEnclosingTag() == null) {
         int ct = 0;
         for (String tagPackage:tagPackageList) {
            if (thisPackage.equals(tagPackage)) {
               tagPackageStart = ct+1;
               break;
            }
            ct++;
         }
      }
      for (int i = tagPackageStart; i < tagPackageList.size(); i++) {
         String tagPackage = tagPackageList.get(i);
         String tagName = lowerTagName();
         Template enclTemplate = getEnclosingTemplate();
         if (tagName.equals("html") && enclTemplate.singleElementType)
            tagName = "htmlPage";
         // No longer capitalizing.  Object names should be lower case
         String typeName = CTypeUtil.prefixPath(tagPackage, CTypeUtil.capitalizePropertyName(tagName));
         Template templ = getEnclosingTemplate();
         // There's the odd case where the template itself is defining the default for this tag.  Obviously don't use the default in that case.
         if (typeName.equals(templ.getModelTypeName()))
            continue;
         // When we are compiling, need to pick up the src type declaration here so that we can get at the corresponding element to inherit tags.
         Object res = sys.getTypeDeclaration(typeName, true);
         if (res != null && (!processable || ModelUtil.isProcessableType(res))) {
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
      return null;
   }

   public Object getExtendsTypeDeclaration() {
      Object specType = getSpecifiedExtendsTypeDeclaration();
      if (specType != null)
         return specType;
      // Content tags default to HTMLElements
      return HTMLElement.class;
   }

   public TypeDeclaration getEnclosingType() {
      Element tag = getEnclosingTag();
      if (tag != null && tag.tagObject != null)
         return tag.tagObject;

      Template templ = getEnclosingTemplate();
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
         element.invalidateBody();
   }

   public void invalidateStartTag() {
      if (startTagValid) {
         startTagValid = false;
         invalidateParent();
      }
   }

   public void invalidateBody() {
      if (bodyValid) {
         bodyValid = false;
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
                     enclTemplate.initialize();
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
                  enclTemplate.initialize();
               TypeDeclaration dtd = (TypeDeclaration) derivedTD;
               if (dtd.element != null)
                  return dtd.element;
            }
            derivedType = derivedTD.getExtendsTypeDeclaration();
            if (derivedType instanceof BodyTypeDeclaration)
               derivedTD = (BodyTypeDeclaration) derivedType;
            else
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
                  enclTemplate.initialize();
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
         childRes.needsBody = children != null || (derivedElement != null && !derivedElement.isEmptyBody());
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
      return null;
   }

   public boolean getNoCache() {
      return getBooleanAttribute("noCache");
   }

   public void _updateInst() {
      invalidate();
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
               repeatElementType = ModelUtil.getArrayOrListComponentType(repeatType);
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
         if (type == null)
            displayError("No implements type: " + name + ": ");
         else
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

      String objName = getObjectName();

      MergeMode tagMerge = getTagMergeMode();
      MergeMode bodyMerge = getBodyMergeMode();

      if (existing == null) {
         if (parentType != null)
            existing = parentType.getInnerType(objName, null);
         // else - no existing type - this must be passed in from the template
      }

      Object extTypeDecl = getExtendsTypeDeclaration();
      boolean canProcess = true;
      boolean canInherit = true;
      Layer tagLayer = getJavaModel().getLayer();

      if (extTypeDecl instanceof Class) {
         Object newExtTypeDecl = ModelUtil.resolveSrcTypeDeclaration(tagLayer.layeredSystem, extTypeDecl);
         if (newExtTypeDecl instanceof BodyTypeDeclaration)
            extTypeDecl = newExtTypeDecl;
      }

      boolean remoteContent = isRemoteContent();

      boolean isRepeatElement = isRepeatElement();
      if (isRepeatElement && !remoteContent) {
         repeatWrapper = ClassDeclaration.create(isAbstract() ? "class" : "object", getRepeatObjectName(), JavaType.createJavaType(HTMLElement.class));
         repeatWrapper.addImplements(JavaType.createJavaType(IRepeatWrapper.class));
         repeatWrapper.element = this;
         repeatWrapper.layer = tagLayer;
         repeatWrapper.addModifier("public");

         SemanticNodeList repeatMethList = (SemanticNodeList) TransformUtil.parseCodeTemplate(Object.class,
                 "   public sc.lang.html.Element createElement(Object val, int ix) {\n " +
                 "      sc.lang.html.Element elem = new " + objName + "();\n" +
                 "      elem.repeatVar = val;\n" +
                 "      elem.repeatIndex = ix;\n" +
                 "      return elem;\n" +
                 "   }",
                 SCLanguage.INSTANCE.classBodySnippet, false);

         // TODO: should the Repeat wrapper implement IObjChildren so that the getObjChildren method is implemented by
         // retrieving the current repeat tags?   This would let a node in the editor that is a repeat display its
         // children in the child form.
         repeatWrapper.addBodyStatement((Statement) repeatMethList.get(0));

         if (parentType != null)
            parentType.addBodyStatement(repeatWrapper);
         else
            template.addTypeDeclaration(repeatWrapper);

         addParentNodeAssignment(repeatWrapper);
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
               Object modifyExtendsType = ModelUtil.getExtendsClass(modifyType);
               if (modifyExtendsType != null && modifyExtendsType != Object.class) {
                  if (!ModelUtil.isAssignableFrom(HTMLElement.class, modifyExtendsType)) {
                     displayError("tag with id: ", objName, " modifies type: ", ModelUtil.getTypeName(modifyType), " in layer:", ModelUtil.getLayerForType(null, modifyType) + " already extends: ", ModelUtil.getTypeName(modifyExtendsType), " which has no schtml file (and does not extends HTMLElement): ");
                     extendsType = null;
                  }
                  Object declaredExtends = getDeclaredExtendsTypeDeclaration();
                  if (declaredExtends != null) {
                     if (!ModelUtil.isAssignableFrom(modifyExtendsType, declaredExtends)) {
                        displayError("The extends attribute: ", ModelUtil.getTypeName(declaredExtends), " overrides an incompatible extends type: ", ModelUtil.getTypeName(modifyExtendsType), " for tag: ");
                        extendsType = null;
                     }
                  }
                  // Do not set an extends type here - we need to inherit it from the modified type
                  else
                     extendsType = null;
               }
            }
         }
      }

      if (extTypeDecl != null && !ModelUtil.isAssignableFrom(HTMLElement.class, extTypeDecl))
         displayTypeError("extends type for tag must extend HTMLElement or come from an schtml template: ");

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
         //if (isAbstract())
         //   tagType.addModifier("abstract");
      }
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
         idIx = addSetServerContent(tagType, idIx);
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
      if (fixedIdAtt != null)
         fixedIdAtt = CTypeUtil.escapeIdentifierString(fixedIdAtt);
      if (needsAutoId(fixedIdAtt)) {
         SemanticNodeList<Expression> args = new SemanticNodeList<Expression>();
         args.add(StringLiteral.create(tagType.typeName));
         if (getNeedsClientServerSpecificId())
            args.add(BooleanLiteral.create(true));
         // Needs to be after the setParent call.
         tagType.addBodyStatement(PropertyAssignment.create("id", IdentifierExpression.createMethodCall(args, "allocUniqueId"), "="));
         specifiedId = true;
      }
      if (tagName != null) {
         // If either we are the first class to extend HTMLElement or we are derived indirectly from Page (and so may not have assigned a tag name)
         if (extTypeDecl == HTMLElement.class || ModelUtil.isAssignableFrom(Page.class, extTypeDecl)) {
            tagType.addBodyStatement(PropertyAssignment.create("tagName", StringLiteral.create(tagName), "="));
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

                   // Need to make a copy of the attribute expressions.  We can regenerate the tag type and update it.  if we share the same expression with the old and new types, the stop of the old type messes up the new type.
                   if (attExpr != null)
                      att.valueExprClone = attExpr = attExpr.deepCopy(CopyNormal | CopyInitLevels, null);

                   if (isIdProperty) {
                      // We might be inheriting this same id in which case do not set it - otherwise, we get duplicates of the same unique id.
                      // But if we are extending a tag with a different id, we do need to set it even if we are inheriting it.
                      if (!PString.isString(att.value) || !inheritsId(att.value.toString())) {
                         SemanticNodeList<Expression> args = new SemanticNodeList<Expression>();
                         args.add(attExpr);
                         if (getNeedsClientServerSpecificId())
                            args.add(BooleanLiteral.create(true));
                         attExpr = IdentifierExpression.createMethodCall(args, "allocUniqueId");
                      }
                      else
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
                      else if (att.name.equals("repeat"))
                         repeatWrapper.addBodyStatementIndent(pa);
                      else
                         tagType.addBodyStatementIndent(pa);
                      // If we're tracking changes for the page content
                      if (template.statefulPage && !isReadOnlyAttribute(att.name) && isHtmlAttribute(att.name) && !hasInvalidateBinding(att.name) && !isRefreshAttribute(att.name)) {
                         PropertyAssignment ba = PropertyAssignment.create(Element.mapAttributeToProperty(att.name), IdentifierExpression.createMethodCall(new SemanticNodeList(), "invalidateStartTag"), "=:");
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
            FieldDefinition repeatVarField = FieldDefinition.create(repeatElementType, repeatVarName, ":=:", repeatExpr);
            tagType.addBodyStatementIndent(repeatVarField);
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
            String preTagStr;
            if (preTagContent == null) {
               StringBuilder parentPreTagContent = getInheritedPreTagContent();

               // If we extend or modify a tag that has the doctype control in it, we need to compile it in here because we do not always inherit the outputStartTag method
               if (parentPreTagContent != null && parentPreTagContent.length() > 0) {
                  preTagContent = parentPreTagContent;
               }
            }
            if (preTagContent != null && (preTagStr = preTagContent.toString().trim()).length() > 0) {
               template.addTemplateDeclToOutputMethod(tagType, outputStartMethod.body, preTagStr, false, "StartTag", resCt, this, true, false);
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
            outputBodyMethod.initBody();
            SemanticNodeList<Expression> outArgs = new SemanticNodeList<Expression>();
            outArgs.add(IdentifierExpression.create("out"));
            // TODO: add Options here to do this inside of the body via a new special case of "super" with no args or anything.  Manually wrap the sub-page's content.  Not sure this is necessary because of the flexibility of merging.
            // ? Optional feature: rename the outputBody method via an attribute on the super-class so that you can do customized add-ons to template types (e.g. "BodyPages" where the html, head, etc. are generated from a common wrapper template) - NOTE - now thinking this is not necessary because of the flexibility of merging
            if (bodyMerge == MergeMode.Append || bodyMerge == MergeMode.Merge) {
               // Use of addBefore or addAfter with append mode?  Not sure this makes sense... maybe an error?
               if (uniqueChildren == children || bodyMerge == MergeMode.Merge) {
                  // Do not do the super if there are any addBefore or addAfter's in our children's list
                  if (needsSuper)
                     outputBodyMethod.addStatement(IdentifierExpression.createMethodCall(outArgs, "super.outputBody"));
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
            tagType.addBodyStatementIndent(outputBodyMethod);
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

   /** Are we the top-level page element */
   protected boolean isPageElement() {
      return getEnclosingTag() == null;
   }

   private int addParentNodeAssignment(BodyTypeDeclaration type) {
      BodyTypeDeclaration parentType = type.getEnclosingType();
      if (parentType != null) {
         //PropertyAssignment pa = PropertyAssignment.create("parentNode", IdentifierExpression.create(parentType.typeName, "this"), "=");
         PropertyAssignment pa = PropertyAssignment.create("parentNode", SelectorExpression.create(IdentifierExpression.create(parentType.typeName), VariableSelector.create("this", null)), "=");
         type.addBodyStatementIndent(pa);
         return type.body.size();
      }
      return 0;
   }

   private int addSetServerContent(BodyTypeDeclaration type, int idx) {
      BodyTypeDeclaration parentType = type.getEnclosingType();
      if (parentType != null) {
         //PropertyAssignment pa = PropertyAssignment.create("parentNode", IdentifierExpression.create(parentType.typeName, "this"), "=");
         PropertyAssignment pa = PropertyAssignment.create("serverContent", BooleanLiteral.create(true), "=");
         type.addBodyStatementIndent(pa);
         return type.body.size();
      }
      return idx;
   }

   static HashMap<String, Set<String>> htmlAttributeMap = new HashMap<String, Set<String>>();
   static HashMap<String, String> tagExtendsMap = new HashMap<String, String>();

   static HashSet<String> singletonTagNames = new HashSet<String>();

   static void addTagAttributes(String tagName, String extName, String[] htmlAttributes) {
      htmlAttributeMap.put(tagName, new TreeSet(Arrays.asList(htmlAttributes)));
      tagExtendsMap.put(tagName, extName);
   }
   static {
      String[] emptyArgs = {};
      addTagAttributes("element", null, new String[] {"id", "style", "class"});
      addTagAttributes("html", "element", emptyArgs);
      addTagAttributes("select", "element", new String[] {"multiple", "disabled", "selectedindex"});
      addTagAttributes("option", "element", new String[] {"selected", "value", "disabled"});
      addTagAttributes("input", "element", new String[] {"value", "disabled", "type", "checked", "defaultchecked", "form", "name", "placeholder", "size", "autocomplete"});
      addTagAttributes("textarea", "element", new String[] {"rows", "cols", "required", "readonly", "form", "name", "placeholder", "size"});
      addTagAttributes("button", "input", emptyArgs);
      addTagAttributes("span", "element", emptyArgs);
      addTagAttributes("div", "element", emptyArgs);
      addTagAttributes("p", "element", emptyArgs);
      addTagAttributes("body", "element", emptyArgs);
      addTagAttributes("head", "element", emptyArgs);
      addTagAttributes("li", "element", emptyArgs);
      addTagAttributes("ul", "element", emptyArgs);
      addTagAttributes("ol", "element", emptyArgs);
      addTagAttributes("table", "element", emptyArgs);
      addTagAttributes("tr", "element", emptyArgs);
      addTagAttributes("td", "element", emptyArgs);
      addTagAttributes("th", "element", emptyArgs);
      addTagAttributes("form", "element", new String[] {"action", "method", "onsubmit"});
      addTagAttributes("a", "element", new String[] {"href", "disabled"});
      addTagAttributes("script", "element", new String[] {"type", "src"});
      addTagAttributes("link", "element", new String[] {"rel", "type", "href"});
      addTagAttributes("img", "element", new String[] {"src", "width", "height", "alt"});
      addTagAttributes("style", "element", new String[] {"type"});
      addTagAttributes("pre", "element", emptyArgs);
      addTagAttributes("code", "element", emptyArgs);
      addTagAttributes("em", "element", emptyArgs);
      addTagAttributes("strong", "element", emptyArgs);
      addTagAttributes("header", "element", emptyArgs);
      addTagAttributes("footer", "element", emptyArgs);
      addTagAttributes("meta", "element", emptyArgs);
      addTagAttributes("iframe", "element", new String[] {"src", "width", "height", "name", "sandbox", "seamless"});
      addTagAttributes("fieldset", "element", emptyArgs);
      addTagAttributes("legend", "element", emptyArgs);
      addTagAttributes("label", "element", new String[] {"for", "form"});
      // One per document so no worrying about merging or allocating unique ids for them
      singletonTagNames.add("head");
      singletonTagNames.add("body");
      singletonTagNames.add("html");
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
      behaviorAttributes.add("repeatVarName");
      behaviorAttributes.add("abstract");
      behaviorAttributes.add("serverContent");
      behaviorAttributes.add("exec");
      behaviorAttributes.add("addBefore");
      behaviorAttributes.add("addAfter");
      behaviorAttributes.add("orderValue");
      behaviorAttributes.add("noCache");

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

   private boolean isBehaviorAttribute(String name) {
      if (HTMLElement.isDOMEventName(name))
         return true;
      if (behaviorAttributes.contains(name))
         return true;
      return false;
   }

   private static boolean isHtmlNamespace(String ns) {
      return ns.equals("wicket");
   }

   private static boolean isHtmlAttributeForTag(String tagName, String name) {
      tagName = tagName.toLowerCase();
      name = name.toLowerCase();

      Set<String> map = htmlAttributeMap.get(tagName);
      if (map != null) {
         if (map.contains(name))
            return true;
      }
      else
         System.out.println("*** Warning unrecognized tag used in object: " + tagName);
      String extTag = tagExtendsMap.get(tagName);
      if (extTag != null)
         return isHtmlAttributeForTag(extTag, name);

      int nsix = name.indexOf(":");
      if (nsix != -1) {
         String namespace = name.substring(0, nsix);
         if (isHtmlNamespace(namespace))
            return true;
      }
      return false;
   }

   /** Return true for any attribute that needs to be rendered as there or not without any attribute based on a boolean expression */
   private static boolean isBooleanAttribute(String attName) {
      return attName.equalsIgnoreCase("checked") || attName.equalsIgnoreCase("multiple") || attName.equalsIgnoreCase("disabled");
   }

   public boolean isHtmlAttribute(String name) {
      return isHtmlAttributeForTag(tagName, name);
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

   private int repeatElementIndexOf(Object repeat, int startIx, Object repeatVar) {
      int sz = PTypeUtil.getArrayLength(repeat);
      for (int i = startIx; i < sz; i++) {
         Object arrayVal = DynUtil.getArrayElement(repeat, i);
         if (arrayVal == repeatVar || (arrayVal != null && arrayVal.equals(repeatVar)))
            return i;
      }
      return -1;
   }

   public void syncRepeatTags(Object repeatVal) {
      int sz = repeatVal == null ? 0 : DynUtil.getArrayLength(repeatVal);

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
               Element arrayElem = createRepeatElement(arrayVal, i);
               tags.add(arrayElem);
            }
         }
         else {
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
                  // The guy in this spot is not our guy.
                  if (oldArrayVal != arrayVal && (oldArrayVal == null || !oldArrayVal.equals(arrayVal))) {
                     // The current guy is new to the list
                     if (curIx == -1) {
                        // Either replace or insert a row
                        int curNewIx = repeatElementIndexOf(repeatVal, i, oldArrayVal);
                        if (curNewIx == -1) { // Reuse the existing object so this turns into an incremental refresh
                           oldElem.setRepeatIndex(i);
                           oldElem.setRepeatVar(arrayVal);
                        }
                        else {
                           // Assert curNewIx > i - if it is less, we should have already moved it when we processed the old guy
                           Element newElem = createRepeatElement(arrayVal, i);
                           tags.add(i, newElem);
                           insertElement(newElem, i);
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
                           }
                           else
                              needsMove = true;
                        }
                        // If we deleted up to the current, we are done.  Otherwise, we need to re-order
                        if (needsMove) {
                           elemToMove.setRepeatIndex(i);
                           tags.add(i, elemToMove);
                           moveElement(elemToMove, curIx, i);
                        }
                     }
                  }
               }
               else {
                  if (curIx == -1) {
                     Element arrayElem = createRepeatElement(arrayVal, i);
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
            }
         }
      }
      finally {
         //SyncManager.setSyncState(oldSyncState);
      }
   }

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
   public void initSync() {
      if (repeat != null || this instanceof IRepeatWrapper) {
         syncRepeatTags(repeat);
      }
   }

   public void outputTag(StringBuilder sb) {
      if (!visible)
         return;

      // Even events which fired during the tag initialization or since we last were rendered must be flushed so our content is accurate.
      DynUtil.execLaterJobs();

      if (dynObj == null) {
         if (repeat != null || this instanceof IRepeatWrapper) {
            syncRepeatTags(repeat);
            if (repeatTags != null) {
               for (int i = 0; i < repeatTags.size(); i++) {
                  repeatTags.get(i).outputTag(sb);
               }
            }
         }
         else {
            outputStartTag(sb);
            outputBody(sb);
            outputEndTag(sb);
         }
      } else {
         Object repeatVal = dynObj.getPropertyFromWrapper(this, "repeat");
         if (repeatVal != null || ModelUtil.isAssignableFrom(IRepeatWrapper.class, dynObj.getDynType())) {
            syncRepeatTags(repeatVal);
            if (repeatTags != null) {
               for (int i = 0; i < repeatTags.size(); i++) {
                  repeatTags.get(i).outputTag(sb);
               }
            }
         }
         else {
            dynObj.invokeFromWrapper(this, "outputStartTag", "Ljava/lang/StringBuilder;", sb);
            dynObj.invokeFromWrapper(this, "outputBody", "Ljava/lang/StringBuilder;", sb);
            dynObj.invokeFromWrapper(this, "outputEndTag", "Ljava/lang/StringBuilder;", sb);
         }
      }
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
      bodyValid = true;
   }

   public void outputEndTag(StringBuilder sb) {
      sb.append("</");
      sb.append(lowerTagName());
      sb.append(">");
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
      if (childrenById == null && children != null || hiddenChildren != null) {
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
            displayError("Dynamie: " + attName + " attribute not supported");
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

   public Element() {
   }
   public Element(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
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

   private Object findInnerTypeInChildList(SemanticNodeList<Object> childList, String name) {
      if (childList != null) {
         for (Object childObj:childList) {
            if (childObj instanceof Element) {
               Element childElement = (Element) childObj;
               String childId = childElement.getElementId();
               if (childId != null && childId.equals(name)) {
                  TypeDeclaration childType = childElement.convertToObject(getEnclosingTemplate(), tagObject, null, null, null);
                  if (childType != null)
                     return childType;
               }
            }
         }
      }
      return null;
   }

   public String toString() {
      String elemId = getElementId();
      return "<" + tagName + (elemId != null ? " id=" + elemId : "") + (selfClosed() ? "/>" : ">") + (children != null ? "...</" + tagName + ">" : "");
   }

   public String getUserVisibleName() {
      return tagName == null ? super.getUserVisibleName() : tagName;
   }

   public Object findMember(String name, EnumSet<MemberType> mtype, Object fromChild, Object refType, TypeContext ctx, boolean skipIfaces) {
      String repeatVar = getRepeatVarName();
      if (repeatVar != null && name.equals(repeatVar)) {
         if (tagObject != null)
            return tagObject.findMember(name, mtype, fromChild, refType, ctx, skipIfaces);

         // Unless we can implement the "RE" (repeat element type-parameter) scheme this won't preserve the type.  It's only for the non-tag object case though
         // so not sure it's important.   Maybe we could just create a ParamTypedMember here and return that if so?
         return findMember("repeatVar", mtype, fromChild, refType, ctx, skipIfaces);
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
         o = ModelUtil.definesMember(extType, name, mtype, refType, ctx, skipIfaces, isTransformed);
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
      return super.definesMember(name, mtype, refType, ctx, skipIfaces, isTransformed);
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
         System.out.println("*** No jsFiles - no layered system!");
         return null;
      }
      JSRuntimeProcessor jsRT = (JSRuntimeProcessor) sys.getRuntime("js");
      if (jsRT != null) {
         // First see if we are contained in an enclosing tag and just defer the source of js files to the parent tag.
         Element parent = getEnclosingTag();
         if (parent != null)
            return parent.getJSFiles();

         // Then find the type associated with this tag, look up the JS files associated with the root type of that type.
         Object decl = tagObject;
         if (decl == null) {
            if (dynObj != null)
               decl = dynObj.getDynType();
            else {
               decl = sys.getRuntimeTypeDeclaration(DynUtil.getTypeName(DynUtil.getType(this), false));
            }
         }
         if (decl != null) {
            Object declRoot = DynUtil.getRootType(decl);
            if (declRoot == null)
               declRoot = decl;
            return jsRT.getJSFiles(declRoot);
         }

      }
      return null;
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
      return sys.getURLPaths();
   }


   public String HTMLClass;

   public String getHTMLClass() {
      return HTMLClass;
   }

   public void setHTMLClass(String cl) {
      HTMLClass = cl;
      Bind.sendChangedEvent(this, "HTMLClass");
   }

   public String style;

   public String getStyle() {
      return style;
   }

   public void setStyle(String s) {
      style = s;
      Bind.sendChangedEvent(this, "style");
   }

   public void registerServerPage() {
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
}
