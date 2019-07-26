/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.bind.Bind;
import sc.bind.BindingListener;
import sc.js.ServerTag;
import sc.obj.IObjectId;
import sc.type.IBeanMapper;

import java.util.ArrayList;

/**
 * A Java + server class that represents the browser's 'document'.  It's used to expose events from the document object
 * as a server api. Although you can manipulate the content of the document in JS, we don't support that through this api
 * even though it inherits from HTMLElement.
 */
@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class Document extends HTMLElement implements IObjectId {
   private final static sc.type.IBeanMapper mouseDownEventProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Document.class, "mouseDownEvent");
   private final static sc.type.IBeanMapper mouseMoveEventProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Document.class, "mouseMoveEvent");
   private final static sc.type.IBeanMapper mouseUpEventProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Document.class, "mouseUpEvent");

   private final static sc.type.IBeanMapper activeElementProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Document.class, "activeElement");

   private static IBeanMapper[] documentSyncProps = new IBeanMapper[] {mouseDownEventProp, mouseMoveEventProp, mouseUpEventProp};

   private Element activeElement;

   public void setActiveElement(Element ae) {
      this.activeElement = ae;
      Bind.sendChange(this, activeElementProp, ae);
   }

   public Element getActiveElement() {
      return activeElement;
   }

   /* inherited via the DOM events
   private MouseEvent mouseMoveEvent, mouseUpEvent;

   public void setMouseMoveEvent(MouseEvent mm) {
      this.mouseMoveEvent = mm;
      Bind.sendChange(this, "mouseMoveEvent", mm);
   }
   public MouseEvent getMouseMoveEvent() {
      return mouseMoveEvent;
   }

   public void setMouseUpEvent(MouseEvent mm) {
      this.mouseUpEvent = mm;
      Bind.sendChange(this, "mouseUpEvent", mm);
   }
   public MouseEvent getMouseUpEvent() {
      return mouseUpEvent;
   }
   */

   {
      setId("document");
   }

   public ServerTag getServerTagInfo(String id) {
      BindingListener[] listeners = Bind.getBindingListeners(this);
      ServerTag stag = null;
      if (listeners != null) {
         for (int i = 0; i < documentSyncProps.length; i++) {
            IBeanMapper propMapper = documentSyncProps[i];
            BindingListener listener = Bind.getPropListeners(this, listeners, propMapper);
            if (listener != null) {
               if (stag == null) {
                  stag = new ServerTag();
                  stag.id = "document";
               }
               if (stag.props == null)
                  stag.props = new ArrayList<Object>();
               stag.eventSource = true;
               stag.props.add(propMapper.getPropertyName());
            }
         }
      }
      return stag;
   }

   @Override
   public String getObjectId() {
      return getId();
   }
}
