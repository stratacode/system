/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.bind.Bind;
import sc.bind.Bindable;
import sc.bind.BindingListener;
import sc.js.ServerTag;
import sc.js.ServerTagContext;
import sc.obj.Constant;
import sc.obj.IObjectId;
import sc.type.IBeanMapper;
import sc.type.PTypeUtil;

import java.util.*;

/**
 * In your Java code, retrieve the Window object with Window.getWindow().  In SC code, it's Window.window.
 * <p>
 * Exposes the subset of the window api which we support from Java running on the client.
 * You can bind to the innerWidth and innerHeight properties.  When you do, a resize event listener is
 * added for these events on the page and your bindings are updated automatically.
 * We expose both "document" and "documentTag" properties.  Occasionally in your API you need a reference
 * to the real javascript object.  In those cases use Window.document.  In all other cases use Window.documentTag
 * so you get the wrapper object.  The Window.document will be the same as Window.documentTag on the server.
 * </p>
 * TODO: simulate this on the server by calling setWindow for each request. */
@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class Window implements IObjectId {
   private final static sc.type.IBeanMapper innerWidthProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Window.class, "innerWidth");
   private final static sc.type.IBeanMapper innerHeightProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Window.class, "innerHeight");
   private final static sc.type.IBeanMapper devicePixelRatioProp = sc.dyn.DynUtil.resolvePropertyMapping(sc.lang.html.Window.class, "devicePixelRatio");

   @Constant
   public Location location;

   // Mirrors the JS api
   @Constant
   public Document document;

   // Returns the tag object that wraps the document
   @Constant
   public Document documentTag;

   private static IBeanMapper[] windowSyncProps = new IBeanMapper[] {innerWidthProp, innerHeightProp, devicePixelRatioProp};

   public static int DefaultWidth = 1100;
   public static int DefaultHeight = 500;

   // TODO - we could have the client set these via an XMLHTTP request but the whole point is to render CSS and HTML
   // up front properly.  We could at least choose different values for different user-agents.
   private int innerWidth = DefaultWidth, innerHeight = DefaultHeight;

   @Bindable(manual=true)
   public int getInnerWidth() {
      return innerWidth;
   }
   public void setInnerWidth(int iw) {
      this.innerWidth = iw;
      Bind.sendChange(this, innerWidthProp, iw);
   }

   @Bindable(manual=true)
   public int getInnerHeight() {
      return innerHeight;
   }
   public void setInnerHeight(int ih) {
      this.innerHeight = ih;
      Bind.sendChange(this, innerHeightProp, ih);
   }


   private double devicePixelRatio = 1.0;
   @Bindable(manual=true)
   public double getDevicePixelRatio() {
      return devicePixelRatio;
   }
   public void setDevicePixelRatio(double f) {
      this.devicePixelRatio = f;
      Bind.sendChange(this, devicePixelRatioProp, f);
   }

   @Bindable(manual=true)
   public boolean getWindowSizeValid() {
      return false;
   }

   int errorCount;
   @Bindable(manual=true)
   public int getErrorCount() {
      return errorCount;
   }

   public static Window createNewWindow(String requestURL, String serverName, int serverPort, String requestURI, String pathInfo, String queryStr, String userAgentStr) {
      Window win = new Window();
      if (userAgentStr != null) {
         UserAgentInfo userAgentInst = UserAgentInfo.getUserAgent(userAgentStr);
         if (userAgentInst != null) {
            int iw = userAgentInst.getDefaultInnerWidth();
            if (iw != -1)
               win.setInnerWidth(iw);
            int ih = userAgentInst.getDefaultInnerHeight();
            if (ih != -1)
               win.setInnerHeight(ih);
            double dpr = userAgentInst.getDefaultDevicePixelRatio();
            if (dpr != -1.0)
               win.setDevicePixelRatio(dpr);
         }
      }
      win.location = new Location();
      Location loc = win.location;
      if (serverPort == 80) {
         loc.port = "";
         loc.host = serverName;
      }
      else {
         loc.host = serverName + ":" + serverPort;
         loc.port = String.valueOf(serverPort);
      }
      loc.hostname = serverName;
      loc.setHref(requestURL);
      loc.setPathname(requestURI);
      loc.setSearch(queryStr);
      int hashIx = requestURL.lastIndexOf("#");
      if (hashIx != -1)
         loc.hash = requestURL.substring(hashIx+1);
      win.document = win.documentTag = new Document();
      return win;
   }

   // TODO: do we need to do binding events when this changes from thread-to-thread?  Will anyone cache this value from request to request?
   @Bindable(manual=true)
   public static Window getWindow() {
      return (Window) PTypeUtil.getThreadLocal("window");
   }

   public static void setWindow(Window window) {
      PTypeUtil.setThreadLocal("window", window);
   }

   public void addServerTags(ServerTagContext stCtx) {
      ServerTag windowServerTag = getServerTagInfo("window");
      stCtx.updateServerTag("window", windowServerTag);

      ServerTag documentServerTag = document.getServerTagInfo("document");
      stCtx.updateServerTag("document", documentServerTag);
   }

   public ServerTag getServerTagInfo(String id) {
      BindingListener[] listeners = Bind.getBindingListeners(this);
      ServerTag stag = null;
      if (listeners != null) {
         for (int i = 0; i < windowSyncProps.length; i++) {
            IBeanMapper propMapper = windowSyncProps[i];
            BindingListener listener = Bind.getPropListeners(this, listeners, propMapper);
            if (listener != null) {
               if (stag == null) {
                  stag = new ServerTag();
                  stag.id = id;
               }
               if (stag.props == null)
                  stag.props = new ArrayList<Object>();
               // Since we have a listener for one of the DOM events - e.g. clickEvent, we need to send the registration over to the client
               // so it knows to sync with that change.
               stag.eventSource = true;
               //stag.immediate = true;
               // TODO also add SyncPropOption here if we want to control immediate or other flags we add to how
               // to synchronize these properties from client to server?
               stag.props.add(propMapper.getPropertyName());
            }
         }
      }
      return stag;
   }

   private ServerTag addServerTagProps(BindingListener[] listeners, ServerTag stag, Map<String,IBeanMapper> propsMap) {
      return stag;
   }

   @Override
   public String getObjectId() {
      return "window";
   }
}
