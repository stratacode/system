/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.bind.Bind;
import sc.bind.Bindable;
import sc.obj.Sync;
import sc.obj.SyncMode;
import sc.type.CTypeUtil;
import sc.type.IBeanMapper;

import java.util.HashSet;

/** The base class for all HTML elements.
 *
 * Sync is disabled for HTML elements now (as set in Element. Instead, by default synchronize the model itself.  This is because
 * we can always re-render the HTML page and so not have to record it's state as changes are made.  The UI tends to react reliably to the
 * model so if we save just the model, we save things in one place unambiguously.   We want model changes to occur on the client and for
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
@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js", dependentJSFiles="js/javasys.js")
@Sync(syncMode=SyncMode.Disabled, includeSuper=true)
public class HTMLElement<E> extends Element<E> {
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

   public HTMLElement() {
   }
   public HTMLElement(sc.lang.java.TypeDeclaration concreteType)  {
      super(concreteType);
   }

   static HashSet<String> domEventNames = new HashSet<String>();

   static boolean isDOMEventName(String name) {
      return domEventNames.contains(name);
   }

   enum EventType {

      Click(true), DblClick(false), MouseDown(false), MouseMove(false), MouseOver(false), MouseOut(false), MouseUp(false), KeyDown(false), KeyPress(false), KeyUp(false), Submit(true), Change(false),
      Focus(false), Blur(false);

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

   Event pendingEvent;
   EventType pendingType;

   @Bindable(manual=true) public MouseEvent getClickEvent() {
      return (MouseEvent) getDOMEvent(EventType.Click);
   }
   @Bindable(manual=true) public void setClickEvent(MouseEvent clickEvent) {
      setDOMEvent(EventType.Click, clickEvent, _clickEventProp);
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
}
