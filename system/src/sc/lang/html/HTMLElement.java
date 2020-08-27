/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.bind.Bind;
import sc.bind.Bindable;
import sc.dyn.DynUtil;
import sc.lang.HTMLLanguage;
import sc.lang.java.TypeDeclaration;
import sc.lang.template.Template;
import sc.obj.ScopeContext;
import sc.obj.Sync;
import sc.obj.SyncMode;
import sc.parser.ParseError;
import sc.parser.ParseUtil;
import sc.sync.SyncManager;
import sc.type.CTypeUtil;
import sc.type.IBeanMapper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/** The base class for all HTML elements.
 *
 * Sync is disabled for HTML elements now (as set in Element. Instead, it's best to synchronize the view model and attach the view model to the
 * HTML view.  This is because we can always re-render the HTML page and so do not have to record the low-level dom state as changes are made.  If the UI is going to
 * work properly, it should be reactive to the view model anyway, so we can just save the model in one place unambiguously.   We want model changes to occur on the client and for
 * the UI to react immediately for interactivity as the default behavior.
 * <p>Some frameworks might want to sync some values of the UI to the server and then process clicks and stuff on the server. </p>
 *
 * If you do want to sync
 * HTML elements entirely, this is a good node to turn it on.   Otherwise you can do it on a tag by tag basis by annotating those classes.
 *
 * The Element class takes the RE type parameter for the repeat element.  We can add this back in so that repeatVar is typed with that type.
 * The benefit being that Java compiles in the repeat element's type without needing to add a new repeat var at compile time.  There are problems
 * getting that to work in the general case.  When you have a parent class that has type parameters and you have an inner instance class which also has
 * type parameters, Java intermittently barfs saying that the parent type is a "raw type" and can't have type parameters.   It's weird because in simple cases
 * it works but in the general case it falls over.  You would Need to change the code to generate an extends like:   class InnerSubClass<TP> extends outerClass<Object>.innerBaseClass<TP>
 */
//@Sync(syncMode= SyncMode.Automatic) // Turn back on sync mode for subclasses with this enabled.  Turned out to not be a good idea to sync both the UI and the model
@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js", extendsJSFiles="js/javasys.js")
@Sync(syncMode=SyncMode.Disabled, includeSuper=true)
@sc.obj.CompilerSettings(exportProperties=false)
public class HTMLElement<RE> extends Element<RE> {
   public final static sc.type.IBeanMapper _clickEventProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.HTMLElement.class, "clickEvent");
   public final static sc.type.IBeanMapper _dblClickEventProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.HTMLElement.class, "dblClickEvent");
   public final static sc.type.IBeanMapper _mouseDownEventProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.HTMLElement.class, "mouseDownEvent");
   public final static sc.type.IBeanMapper _mouseMoveEventProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.HTMLElement.class, "mouseMoveEvent");
   public final static sc.type.IBeanMapper _mouseOverEventProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.HTMLElement.class, "mouseOverEvent");
   public final static sc.type.IBeanMapper _mouseOutEventProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.HTMLElement.class, "mouseOutEvent");
   public final static sc.type.IBeanMapper _mouseUpEventProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.HTMLElement.class, "mouseUpEvent");
   public final static sc.type.IBeanMapper _keyDownEventProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.HTMLElement.class, "keyDownEvent");
   public final static sc.type.IBeanMapper _keyPressEventProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.HTMLElement.class, "keyPressEvent");
   public final static sc.type.IBeanMapper _keyUpEventProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.HTMLElement.class, "keyUpEvent");
   public final static sc.type.IBeanMapper _focusEventProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.HTMLElement.class, "focusEvent");
   public final static sc.type.IBeanMapper _blurEventProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.HTMLElement.class, "blurEvent");
   public final static sc.type.IBeanMapper _clientWidthProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.HTMLElement.class, "clientWidth");
   public final static sc.type.IBeanMapper _clientHeightProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.HTMLElement.class, "clientHeight");
   public final static sc.type.IBeanMapper _scrollWidthProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.HTMLElement.class, "scrollWidth");
   public final static sc.type.IBeanMapper _scrollHeightProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.HTMLElement.class, "scrollHeight");
   public final static sc.type.IBeanMapper _offsetLeftProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.HTMLElement.class, "offsetLeft");
   public final static sc.type.IBeanMapper _offsetTopProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.HTMLElement.class, "offsetTop");
   public final static sc.type.IBeanMapper _offsetWidthProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.HTMLElement.class, "offsetWidth");
   public final static sc.type.IBeanMapper _offsetHeightProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.HTMLElement.class, "offsetHeight");

   public final static sc.type.IBeanMapper _mouseDownMoveUpProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.HTMLElement.class, "mouseDownMoveUp");

   static HashMap<String,IBeanMapper> domAttributes = new HashMap<String,IBeanMapper>();
   {
      domAttributes.put("clickEvent", HTMLElement._clickEventProp);
      domAttributes.put("dblClickEvent", HTMLElement._dblClickEventProp);
      domAttributes.put("mouseDownEvent", HTMLElement._mouseDownEventProp);
      domAttributes.put("mouseOutEvent", HTMLElement._mouseOutEventProp);
      domAttributes.put("mouseMoveEvent", HTMLElement._mouseMoveEventProp);
      domAttributes.put("mouseUpEvent", HTMLElement._mouseUpEventProp);
      domAttributes.put("keyDownEvent", HTMLElement._keyDownEventProp);
      domAttributes.put("keyPressEvent", HTMLElement._keyPressEventProp);
      domAttributes.put("keyUpEvent", HTMLElement._keyUpEventProp);
      domAttributes.put("focusEvent", HTMLElement._focusEventProp);
      domAttributes.put("blurEvent", HTMLElement._blurEventProp);
      domAttributes.put("clientWidth", HTMLElement._clientWidthProp);
      domAttributes.put("clientHeight", HTMLElement._clientHeightProp);
      domAttributes.put("scrollWidth", HTMLElement._scrollWidthProp);
      domAttributes.put("scrollHeight", HTMLElement._scrollHeightProp);
      domAttributes.put("offsetTop", HTMLElement._offsetTopProp);
      domAttributes.put("offsetLeft", HTMLElement._offsetLeftProp);
      domAttributes.put("offsetWidth", HTMLElement._offsetWidthProp);
      domAttributes.put("offsetHeight", HTMLElement._offsetHeightProp);

      domAttributes.put("mouseDownMoveUp", HTMLElement._mouseDownMoveUpProp);
   }

   public HTMLElement() {
   }
   public HTMLElement(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
   }
   public HTMLElement(TypeDeclaration concreteType, Element parent, String id, Object repeatVar, int repeatIx) {
      super(concreteType, parent, id, repeatVar, repeatIx);
   }
   public HTMLElement(Element parent, String id, Object repeatVar, int repeatIx) {
      super(parent, id, repeatVar, repeatIx);
   }

   static HashSet<String> domEventNames = new HashSet<String>();

   static boolean isDOMEventName(String name) {
      return domEventNames.contains(name);
   }

   enum EventType {

      Click(true), DblClick(false), MouseDown(false), MouseMove(false), MouseOver(false), MouseOut(false), MouseUp(false), KeyDown(false), KeyPress(false), KeyUp(false), Submit(true), Change(false),
      Focus(false), Blur(false),

      // Not a real DOM event - our way of representing a listener that will move from the window, to the document for mouseDown to Move/Up so you can implement drag style interfaces (or drag/drop)
      MouseDownMoveUp(false);

      EventType(boolean preventDefault) {
         this.preventDefault = preventDefault;
         domEventNames.add(getEventName());
      }

      boolean preventDefault;

      public String getEventName() {
         String name = toString();
         name = CTypeUtil.decapitalizePropertyName(name) + "Event";
         return name;
      }

      public String getAttributeName() {
         return "on" + toString().toLowerCase();
      }

      /** Return true if the clickEvent for example should do onclick="return false;" */
      public boolean getPreventDefault() {
         return preventDefault;
      }
   }

   transient Event pendingEvent;
   transient EventType pendingType;

   @Bindable(manual=true) public MouseEvent getClickEvent() {
      return (MouseEvent) getDOMEvent(EventType.Click);
   }
   @Bindable(manual=true) public void setClickEvent(MouseEvent clickEvent) {
      setDOMEvent(EventType.Click, clickEvent, _clickEventProp);
   }

   /** Method implemented on the client only to generate a simulated DOM event that behaves like a click */
   @sc.obj.Exec(clientOnly=true)
   public void click() {
   }

   @Bindable(manual=true) public MouseEvent getDblClickEvent() {
      return (MouseEvent) getDOMEvent(EventType.DblClick);
   }
   @Bindable(manual=true) public void setDblClickEvent(MouseEvent clickEvent) {
      setDOMEvent(EventType.DblClick, clickEvent, _dblClickEventProp);
   }

   @Bindable(manual=true) public MouseEvent getMouseDownEvent() {
      return pendingType == EventType.MouseDown ? (MouseEvent) pendingEvent : null;
   }
   @Bindable(manual=true) public void setMouseDownEvent(MouseEvent event) {
      setDOMEvent(EventType.MouseDown, event, _mouseDownEventProp);
   }

   @Bindable(manual=true) public MouseEvent getMouseMoveEvent() {
      return pendingType == EventType.MouseMove ? (MouseEvent) pendingEvent : null;
   }
   @Bindable(manual=true) public void setMouseMoveEvent(MouseEvent event) {
      setDOMEvent(EventType.MouseMove, event, _mouseMoveEventProp);
   }

   @Bindable(manual=true) public MouseEvent getMouseOverEvent() {
      return pendingType == EventType.MouseOver ? (MouseEvent) pendingEvent : null;
   }
   @Bindable(manual=true) public void setMouseOverEvent(MouseEvent event) {
      setDOMEvent(EventType.MouseOver, event, _mouseOverEventProp);
   }

   @Bindable(manual=true) public MouseEvent getMouseOutEvent() {
      return pendingType == EventType.MouseOut ? (MouseEvent) pendingEvent : null;
   }
   @Bindable(manual=true) public void setMouseOutEvent(MouseEvent event) {
      setDOMEvent(EventType.MouseOut, event, _mouseOutEventProp);
   }

   @Bindable(manual=true) public MouseEvent getMouseUpEvent() {
      return pendingType == EventType.MouseUp ? (MouseEvent) pendingEvent : null;
   }
   @Bindable(manual=true) public void setMouseUpEvent(MouseEvent event) {
      setDOMEvent(EventType.MouseUp, event, _mouseUpEventProp);
   }

   @Bindable(manual=true) public KeyboardEvent getKeyDownEvent() {
      return pendingType == EventType.KeyDown ? (KeyboardEvent) pendingEvent : null;
   }
   @Bindable(manual=true) public void setKeyDownEvent(KeyboardEvent event) {
      setDOMEvent(EventType.KeyDown, event, _keyDownEventProp);
   }

   @Bindable(manual=true) public KeyboardEvent getKeyPressEvent() {
      return pendingType == EventType.KeyPress ? (KeyboardEvent) pendingEvent : null;
   }
   @Bindable(manual=true) public void setKeyPressEvent(KeyboardEvent event) {
      setDOMEvent(EventType.KeyPress, event, _keyPressEventProp);
   }

   @Bindable(manual=true) public KeyboardEvent getKeyUpEvent() {
      return pendingType == EventType.KeyUp ? (KeyboardEvent) pendingEvent : null;
   }
   @Bindable(manual=true) public void setKeyUpEvent(KeyboardEvent event) {
      setDOMEvent(EventType.KeyUp, event, _keyUpEventProp);
   }

   @Bindable(manual=true) public Event getFocusEvent() {
      return pendingType == EventType.Focus ? pendingEvent : null;
   }
   @Bindable(manual=true) public void setFocusEvent(Event event) {
      setDOMEvent(EventType.Focus, event, _focusEventProp);
   }

   @Bindable(manual=true) public Event getBlurEvent() {
      return pendingType == EventType.Blur ? pendingEvent : null;
   }
   @Bindable(manual=true) public void setBlurEvent(Event event) {
      setDOMEvent(EventType.Blur, event, _blurEventProp);
   }

   @Bindable(manual=true) public MouseEvent getMouseDownMoveUp() {
      return pendingType == EventType.MouseDownMoveUp ? (MouseEvent) pendingEvent : null;
   }
   @Bindable(manual=true) public void setMouseDownMoveUp(MouseEvent event) {
      setDOMEvent(EventType.MouseDownMoveUp, event, _mouseDownMoveUpProp);
   }

   protected Event getDOMEvent(EventType type) {
      return pendingType == type ? pendingEvent : null;
   }

   protected void setDOMEvent(EventType type, Event event, IBeanMapper prop) {
      pendingEvent = event;
      pendingType = type;
      // TODO: should this be sendSyncEvent.  I don't think it will be called when there is queuing in place but if so, it should defeat the queuing so we only
      // have one event active at one time and complete this guys event processing before the next one comes in.
      // For Sync should we also force a sync after we complete the event or at least ensure we capture the clickEvent in the sync layer.
      Bind.sendEvent(sc.bind.IListener.VALUE_CHANGED, this, prop, event);
      pendingEvent = null;
      pendingType = null;

   }

   // To be called on the client only where it calls the DOM focus element
   @sc.obj.Exec(clientOnly=true)
   public void focus() {
      Element root = getRootTag();
      if (root instanceof IPage) {
         IPageDispatcher dispatcher = ((IPage) root).getPageDispatcher();
         dispatcher.invokeRemote(this, HTMLElement.class, "focus", Void.class, null, null);
         return;
      }
      System.err.println("*** No sync context for remote focus method");
   }

   public static Set<String> formattingTags = new TreeSet<String>();
   static {
      formattingTags.add("b");
      formattingTags.add("i");
      formattingTags.add("p");
      formattingTags.add("li");
      formattingTags.add("ul");
      formattingTags.add("ol");
      formattingTags.add("br");
      formattingTags.add("span");
      formattingTags.add("div");
      formattingTags.add("table");
      formattingTags.add("tbody");
      formattingTags.add("tr");
      formattingTags.add("u");
      formattingTags.add("td");
      formattingTags.add("th");
      formattingTags.add("a");
      formattingTags.add("img");
   }

   public static Set<String> formattingAtts = new TreeSet<String>();
   static {
      formattingAtts.add("class");
      formattingAtts.add("href");
      formattingAtts.add("src");
      formattingAtts.add("style");
      formattingAtts.add("target");
      formattingAtts.add("data-filename");
   }

   /** Parses and validates the HTML provided in htmlStr. If allowedTags is not null, only tags with those names are allowed  */
   public static String validateClientHTML(String htmlStr, Set<String> allowedTags, Set<String> allowedAtts) {
      if (htmlStr == null)
         return null;
      HTMLLanguage lang = HTMLLanguage.getHTMLLanguage();

      Object parseRes = lang.parseString(htmlStr);
      if (parseRes instanceof ParseError) {
         return parseRes.toString();
      }
      else {
         Object semNode = ParseUtil.nodeToSemanticValue(parseRes);
         if (!(semNode instanceof Template))
            return "Invalid parse result";
         Template templ = (Template) semNode;
         if (templ.templateDeclarations == null)
            return null;
         for (Object decl:templ.templateDeclarations) {
            if (decl instanceof CharSequence)
               continue;
            if (decl instanceof Element) {
               Element tag = (Element) decl;

               String childRes = validateTagTree(tag, allowedTags, allowedAtts);
               if (childRes != null)
                  return childRes;
            }
            else
               return "Invalid contents in template";
         }
      }
      return null;
   }

   public static String validateTagTree(Element tag, Set<String> allowedTags, Set<String> allowedAtts) {
      String tagName = tag.tagName;
      if (tagName == null || (allowedTags != null && !(allowedTags.contains(tagName.toLowerCase()))))
         return "Html tag: " + tagName + " not allowed";
      if (tag.attributeList != null) {
         for (int i = 0; i < tag.attributeList.size(); i++) {
            Attr att = (Attr) tag.attributeList.get(i);
            if (att.name == null || !allowedAtts.contains(att.name))
               return "Invalid attribute for client HTML: " + att.name;
            if (!(att.value instanceof CharSequence))
               return "Invalid attribute value";
            String attValue = att.value.toString();
            if (attValue.startsWith("javascript:"))
               return "Invalid attribute containing javascript";
         }
      }
      if (tag.children != null) {
         for (Object child:tag.children) {
            if (child instanceof CharSequence)
               continue;
            if (child instanceof Element) {
               Element childTag = (Element) child;
               String res = validateTagTree(childTag, allowedTags, allowedAtts);
               if (res != null)
                  return res;
            }
            else if (child != null)
               return "Invalid child element for client HTML";
         }
      }
      return null;
   }
}
