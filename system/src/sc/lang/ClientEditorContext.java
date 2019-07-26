/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.bind.Bindable;
import sc.bind.Bind;
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
   LinkedHashSet<JavaModel> changedModels = new LinkedHashSet<JavaModel>();
   LinkedHashMap<SrcEntry, Object> errorModels = new LinkedHashMap<SrcEntry, Object>();

   // TODO: shouldn't the command line handle runtime types as well?
   ArrayList<BodyTypeDeclaration> currentTypes = new ArrayList<BodyTypeDeclaration>();
   int startTypeIndex = 0;

   boolean currentModelStale = false;

   public Layer currentLayer;

   public String layerPrefix;

   HashMap<SrcEntry,MemoryEditSession> memSessions = new HashMap<SrcEntry, MemoryEditSession>();

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
      return memorySessionChanged || memSessions.size() > 0;
   }

   public String getModelText(JavaModel model) {
      MemoryEditSession mes = getMemorySession(model.getSrcFile());
      if (mes == null)
         return model.getModelText();
      return mes.text;
   }

   public String getMemoryEditSessionText(SrcEntry ent) {
      MemoryEditSession mes = memSessions.get(ent);
      if (mes == null)
         return null;
      return mes.text;
   }

   public int getMemoryEditCaretPosition(SrcEntry ent) {
      MemoryEditSession mes = memSessions.get(ent);
      if (mes == null)
         return -1;
      return mes.caretPosition;
   }

   public String getMemoryEditSessionOrigText(SrcEntry ent) {
      MemoryEditSession mes = memSessions.get(ent);
      if (mes == null)
         return null;
      return mes.origText;
   }

   public MemoryEditSession getMemorySession(SrcEntry ent) {
      return memSessions.get(ent);
   }

   public void changeMemoryEditSession(String text, JavaModel model, int caretPos) {
      SrcEntry ent = model.getSrcFile();
      MemoryEditSession sess = memSessions.get(ent);
      if (sess == null) {
         sess = new MemoryEditSession();
         sess.origText = model.toLanguageString();
         memSessions.put(ent, sess);
      }
      sess.text = text;
      sess.model = model;
      sess.caretPosition = caretPos;
      setMemorySessionChanged(true);
   }


   public void cancelMemorySessionChanges() {
      boolean anySaved = false;
      for (MemoryEditSession mes:memSessions.values()) {
         mes.text = mes.origText;
         if (mes.saved) {
            mes.model.saveModelTextToFile(mes.origText);
            anySaved = true;
         }
         // Need to refresh the editor anyway even if the model itself did not changed
         mes.model.markChanged();
      }
      // Did we have to restore any files for which we've saved something that did not update properly?
      if (anySaved)
         doRefresh();
      else
         memSessions.clear();

      if (memSessions.size() == 0)
         setMemorySessionChanged(false);
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
      Object errorObj = errorModels.get(model.getSrcFile());
      if (errorObj instanceof JavaModel) {
         return ((JavaModel) errorObj).errorMessages.toString();
      }
      else if (errorObj instanceof String) {
         return (String) errorObj;
      }
      else if (errorObj == null)
         return null;
      return "???";
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
}
