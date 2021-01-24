/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.bind.Bind;
import sc.bind.Bindable;
import sc.bind.BindingListener;
import sc.js.ServerTag;
import sc.obj.Constant;
import sc.obj.IObjectId;
import sc.obj.TypeSettings;
import sc.type.IBeanMapper;
import sc.type.PTypeUtil;
import sc.dyn.DynUtil;

import java.util.ArrayList;


@sc.js.JSSettings(prefixAlias="js_", jsLibFiles="js/tags.js")
public class Screen implements IObjectId {
   private final static sc.type.IBeanMapper widthProp = DynUtil.resolvePropertyMapping(sc.lang.html.Screen.class, "width");
   private final static sc.type.IBeanMapper heightProp = DynUtil.resolvePropertyMapping(sc.lang.html.Screen.class, "height");

   private transient String id;

   Window window;

   private static IBeanMapper[] screenSyncProps = new IBeanMapper[] {widthProp, heightProp};

   Screen(Window window) {
      this.window = window;
      this.id = "screen";
   }

   private int width = Window.DefaultWidth, height = Window.DefaultHeight;

   public ServerTag getServerTagInfo(String id) {
      BindingListener[] listeners = Bind.getBindingListeners(this);
      ServerTag stag = null;
      if (listeners != null) {
         for (int i = 0; i < screenSyncProps.length; i++) {
            IBeanMapper propMapper = screenSyncProps[i];
            BindingListener listener = Bind.getPropListeners(this, listeners, propMapper);
            if (listener != null) {
               if (stag == null) {
                  stag = new ServerTag();
                  stag.id = "screen";
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

   public String getObjectId() {
      return getId();
   }

   public void setId(String _id) {
      this.id = _id;
   }

   @Constant
   public String getId() {
      return id;
   }

   @Bindable(manual=true)
   public int getWidth() {
      return width;
   }
   public void setWidth(int w) {
      this.width = w;
      Bind.sendChange(this, widthProp, w);
   }

   @Bindable(manual=true)
   public int getHeight() {
      return height;
   }
   public void setHeight(int h) {
      this.height = h;
      Bind.sendChange(this, heightProp, h);
      if (window != null) {
         window.screenSizeChanged();
      }
   }
}
