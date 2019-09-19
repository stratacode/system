/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.bind.Bindable;
import sc.bind.Bind;
import sc.dyn.DynUtil;
import sc.lang.java.BodyTypeDeclaration;
import sc.lang.java.JavaModel;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.layer.SrcEntry;
import sc.obj.Constant;
import sc.type.CTypeUtil;

import java.util.*;

/** This is the part of the editor context we share on the client. It's a workaround for the fact that we don't depend on the modify operator to build SC (so the IDE can build it directly) */
public abstract class ClientEditorContext {
   @Constant
   public LayeredSystem system;

   JavaModel pendingModel = null;
   @Bindable(manual=true)
   public LinkedHashSet<JavaModel> changedModels = new LinkedHashSet<JavaModel>();
   @Bindable(manual=true)
   public LinkedHashMap<SrcEntry, List<ModelError>> errorModels = new LinkedHashMap<SrcEntry, List<ModelError>>();

   // TODO: shouldn't the command line handle runtime types as well?
   ArrayList<BodyTypeDeclaration> currentTypes = new ArrayList<BodyTypeDeclaration>();
   int startTypeIndex = 0;

   boolean currentModelStale = false;

   public Layer currentLayer;

   public List<Layer> currentLayers = Collections.emptyList();

   public String layerPrefix;

   @Bindable(manual=true)
   HashMap<SrcEntry,MemoryEditSession> memSessions = null;

   public void setMemSessions(HashMap<SrcEntry,MemoryEditSession> msMap) {
      this.memSessions = msMap;
      Bind.sendChangedEvent(this, "memSessions");
   }

   public HashMap<SrcEntry,MemoryEditSession> getMemSessions() {
      return this.memSessions;
   }

   private boolean memorySessionChanged = false;

   private List<String> createInstTypeNames;

   /** A list of packages that are used to search for a type name - by default, we'll take any exported packages from the layers in the stack as the importPackages */
   ArrayList<String> importPackages = new ArrayList<String>();

   @Bindable(manual=true)
   public boolean getMemorySessionChanged() {
      return memorySessionChanged;
   }

   public void setMemorySessionChanged(boolean val) {
      memorySessionChanged = val;
      Bind.sendChangedEvent(this, "memorySessionChanged");
   }

   public boolean hasAnyMemoryEditSession(boolean memorySessionChanged) {
      return memorySessionChanged || (memSessions != null && memSessions.size() > 0);
   }

   /** Returns the current modelText to display. The extra modelText param is for receiving data binding events on the client */
   public String getModelText(JavaModel model, String modelText) {
      MemoryEditSession mes = getMemorySession(model.getSrcFile());
      if (mes == null)
         return modelText;
      return mes.getText();
   }

   public String getMemoryEditSessionText(SrcEntry ent) {
      MemoryEditSession mes = memSessions == null ? null : memSessions.get(ent);
      if (mes == null)
         return null;
      return mes.getText();
   }

   public int getMemoryEditCaretPosition(SrcEntry ent) {
      MemoryEditSession mes = memSessions == null ? null : memSessions.get(ent);
      if (mes == null)
         return -1;
      return mes.getCaretPosition();
   }

   public String getMemoryEditSessionOrigText(SrcEntry ent) {
      MemoryEditSession mes = memSessions == null ? null : memSessions.get(ent);
      if (mes == null)
         return null;
      return mes.getOrigText();
   }

   public MemoryEditSession getMemorySession(SrcEntry ent) {
      return memSessions == null ? null : memSessions.get(ent);
   }

   public void changeMemoryEditSession(String text, JavaModel model, int caretPos) {
      SrcEntry ent = model.getSrcFile();
      MemoryEditSession sess = null;
      HashMap<SrcEntry, MemoryEditSession> newMemSessions = null;
      if (memSessions == null) {
         newMemSessions = new HashMap<SrcEntry,MemoryEditSession>();
      }
      else
         sess = memSessions.get(ent);
      if (sess == null) {
         sess = new MemoryEditSession();
         sess.setOrigText(model.toLanguageString());
         if (newMemSessions == null)
            newMemSessions = new HashMap<SrcEntry,MemoryEditSession>(memSessions);
         newMemSessions.put(ent, sess);
      }
      sess.setText(text);
      sess.model = model;
      sess.setCaretPosition(caretPos);
      setMemorySessionChanged(true);

      if (newMemSessions != null) {
         memSessions = newMemSessions;
         Bind.sendChange(this, "memSessions", memSessions);
      }
   }

   public void cancelMemorySessionChanges() {
      boolean anySaved = false;
      if (memSessions == null)
         return;
      for (MemoryEditSession mes:memSessions.values()) {
         mes.setText(mes.getOrigText());
         if (mes.getSaved()) {
            mes.model.saveModelTextToFile(mes.getOrigText());
            anySaved = true;
         }
         // Need to refresh the editor anyway even if the model itself did not changed
         mes.model.markChanged();
      }
      // Did we have to restore any files for which we've saved something that did not update properly?
      if (anySaved)
         doRefresh();
      else
         memSessions = new HashMap<SrcEntry,MemoryEditSession>();

      if (memSessions.size() == 0)
         setMemorySessionChanged(false);

      Bind.sendChange(this, "memSessions", memSessions);
   }

   abstract void doRefresh();

   public boolean hasErrors(JavaModel model) {
      return errorModels.get(model.getSrcFile()) != null;
   }

   int errorsChanged;

   @Bindable(manual=true)
   public void setErrorsChanged(int val) {
      errorsChanged = val;
      Bind.sendChangedEvent(this, "errorsChanged");
   }

   public int getErrorsChanged() {
      return errorsChanged;
   }

   public String getErrors(JavaModel model, int changedValue) {
      if (model == null)
         return "<null model>";
      List<ModelError> errors = errorModels.get(model.getSrcFile());
      if (errors == null)
         return null;
      StringBuilder sb = new StringBuilder();
      for (ModelError error:errors) {
         if (sb.length() > 0)
            sb.append("\n");
         sb.append(error.error);
      }
      return sb.toString();
   }

   @Bindable(manual=true)
   public List<String> getCreateInstTypeNames() {
      return createInstTypeNames;
   }

   public void setCreateInstTypeNames(List<String> nl) {
      createInstTypeNames = nl;
      Bind.sendChangedEvent(this, "createInstTypeNames");
   }

   public boolean isCreateInstType(String typeName) {
      return createInstTypeNames != null && createInstTypeNames.contains(typeName);
   }

   public String getCreateInstFullTypeName(String typeName) {
      for (String tn:createInstTypeNames)
         if (CTypeUtil.getClassName(tn).equals(typeName))
            return tn;
      return null;
   }

   public void addCreateInstTypeName(String typeName) {
      if (createInstTypeNames == null)
         createInstTypeNames = new ArrayList<String>();
      if (!createInstTypeNames.contains(typeName)) {
         createInstTypeNames.add(typeName);
         Bind.sendChangedEvent(this, "createInstTypeNames");
      }
   }

   public void updateCurrentLayer(Layer l) {
      currentLayer = l;
      currentLayers = l == null ? Collections.emptyList() : l.getSelectedLayers();
   }

   /** Toggles the state of the current layer - if it's current, remove it. Otherwise add it. */
   public void layerSelected(Layer l, boolean addToSelection) {
      if (currentLayers == null) {
         setCurrentLayer(l);
      }
      else if (!addToSelection) {
         if (currentLayers.get(0) == l) {
            List<Layer> newLayers = l.getSelectedLayers();
            if (!DynUtil.equalObjects(newLayers, currentLayers)) {
               currentLayers = newLayers;
               Bind.sendChangedEvent(this, "currentLayers");
            }
         }
         else {
            setCurrentLayer(l);
         }
      }
      else {
         boolean handled = false;
         int insertPos = -1;
         ArrayList<Layer> newCurrentLayers = new ArrayList<Layer>(currentLayers);
         for (int i = 0; i < currentLayers.size(); i++) {
            Layer current = currentLayers.get(i);
            if (current == l) {
               // Both addToSelection and normal should remove the element - or should one of them just ignore the selection?
               newCurrentLayers.remove(i);
               handled = true;
               break;
            }
            if (current.getLayerPosition() < l.getLayerPosition()) {
               insertPos = i;
               break;
            }
         }
         if (!handled) {
            if (insertPos != -1)
               newCurrentLayers.add(insertPos, l);
            else
               newCurrentLayers.add(l);
         }
         Layer newCurrentLayer = newCurrentLayers.size() == 0 ? null : newCurrentLayers.get(0);
         if (newCurrentLayer != currentLayer) {
            currentLayer = newCurrentLayer;
            updateLayerPrefix();
            Bind.sendChangedEvent(this, "currentLayer");
         }
         // Because we don't have access to sc.util.ArrayList which implements IChangeable and sends it's own events in add, set, etc.
         // we will just rebuild the list each time since it's pretty small.
         currentLayers = newCurrentLayers;
         Bind.sendChangedEvent(this, "currentLayers");
      }
   }

   @Bindable(manual=true)
   public void setCurrentLayer(Layer newLayer) {
      // TODO: should we try to preserve imports here and only switch the layer of the pending model if there's no type?
      if (currentLayer != newLayer) {
         updateCurrentLayer(newLayer);
         updateLayerPrefix();
         Bind.sendChangedEvent(this, "currentLayer");
         Bind.sendChangedEvent(this, "currentLayers");
      }
   }

   private void updateLayerPrefix() {
      if (currentLayer != null)
         layerPrefix = currentLayer.packagePrefix;
      else
         layerPrefix = null;
   }

}
