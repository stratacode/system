/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.bind.Bind;
import sc.dyn.DynUtil;
import sc.lang.java.JavaModel;

// @sc.obj.Sync(onDemand=true) - done by API in EditorContext.initSync
public class MemoryEditSession implements sc.obj.IObjectId {
   public JavaModel model;
   public boolean cancelled; // Set to true when this edit session has been reverted

   EditorContext ctx;

   // Most current parse of the text (only on the server and not sync'd)
   Object lastParseRes;

   public void setModel(JavaModel m) {
      this.model = m;
      Bind.sendChangedEvent(this, "model");
   }

   public JavaModel getModel() {
      return model;
   }

   private String text;
   public void setText(String t) {
      if (text == null || !text.equals(t))
         setStaleErrors(true);
      text = t;
      Bind.sendChangedEvent(this, "text");
   }
   public String getText() {
      return text;
   }

   private String origText;
   public void setOrigText(String t) {
      origText = t;
      Bind.sendChangedEvent(this, "origText");
   }
   public String getOrigText() {
      return origText;
   }

   private int caretPosition; // Save the current spot in the text
   public void setCaretPosition(int cp) {
      caretPosition = cp;
      Bind.sendChangedEvent(this, "caretPosition");
   }
   public int getCaretPosition() {
      return caretPosition;
   }

   private boolean saved; // Have we saved this since origText was set
   public void setSaved(boolean s) {
      saved = s;
      Bind.sendChangedEvent(this, "saved");
   }
   public boolean getSaved() {
      return saved;
   }

   private boolean staleErrors; // Have we saved this since origText was set - not synchronized and only used on the server now
   public void setStaleErrors(boolean s) {
      if (s && !staleErrors && ctx != null) {
         ctx.scheduleRefreshMemorySessionErrors();
      }
      staleErrors = s;
      Bind.sendChangedEvent(this, "staleErrors");
   }
   public boolean getStaleErrors() {
      return staleErrors;
   }

   public String getObjectId() {
      return "MES_" + (model == null ? "null" : sc.type.CTypeUtil.escapeIdentifierString(model.getLayer().getLayerName() + "__" + model.getSrcFile().relFileName));
   }
}
