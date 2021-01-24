/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import java.util.List;
import java.util.Map;

import sc.bind.Bind;
import sc.bind.Bindable;
import sc.bind.IChangeable;
import sc.dyn.DynUtil;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.obj.Constant;
import sc.obj.IObjectId;

/** A wrapper around the TypeDeclaration classes for synchronizing editable type info to a remote client.
 *  There's a javascript version of this class */
public class ClientTypeDeclaration extends TypeDeclaration implements IChangeable, IObjectId {
   /*
   @Constant
   public String typeName;
   public boolean isLayerType;
   */

   transient BodyTypeDeclaration orig = null;

   public BodyTypeDeclaration getOriginal() {
      return orig;
   }

   private DeclarationType declarationType;
   @Constant
   public void setDeclarationType(DeclarationType dt) {
      declarationType = dt;
   }
   public DeclarationType getDeclarationType() {
      return declarationType;
   }

   private List<Object> declaredProperties;
   @Bindable(manual=true)
   public List<Object> getDeclaredProperties() {
      return declaredProperties;
   }
   public void setDeclaredProperties(List<Object> ap) {
      if (!DynUtil.equalObjects(ap, declaredProperties)) {
         declaredProperties = ap;
         Bind.sendChangedEvent(this, "declaredProperties");
      }
   }

   private String constructorParamNames;
   @Constant
   public String getConstructorParamNames() {
      return constructorParamNames;
   }
   public void setConstructorParamNames(String names) {
      constructorParamNames = names;
   }

   private AbstractMethodDefinition editorCreateMethod;
   @Constant
   public AbstractMethodDefinition getEditorCreateMethod() {
      return editorCreateMethod;
   }
   public void setEditorCreateMethod(AbstractMethodDefinition ecm) {
      editorCreateMethod = ecm;
   }

   private String packageName;
   @Constant
   public void setPackageName(String pn) {
      packageName = pn;
   }
   public String getPackageName() {
      return packageName;
   }

   private String scopeName;
   @Constant
   public void setScopeName(String pn) {
      scopeName = pn;
   }
   public String getScopeName() {
      if (orig != null)
         return orig.getScopeName();
      return scopeName;
   }

   private boolean dynamicType;
   @Constant
   public void setDynamicType(boolean dt) {
      dynamicType = dt;
   }
   public boolean isDynamicType() {
      return dynamicType;
   }

   Layer layer;
   @Constant
   public void setLayer(Layer l) {
      layer = l;
   }
   public Layer getLayer() {
      return layer;
   }

   String comment;
   @Constant
   public void setComment(String c) {
      comment = c;
   }
   public String getComment() {
      return comment;
   }

   public void markChanged() {
      Bind.sendChangedEvent(this, null);
   }

   public ClientTypeDeclaration getClientTypeDeclaration() {
      return this;
   }

   String fullTypeName;
   @Constant
   public String getFullTypeName() {
      return fullTypeName;
   }

   public void setFullTypeName(String ftn) {
      fullTypeName = ftn;
   }

   String extendsTypeName;
   @Constant
   public String getExtendsTypeName() {
      return extendsTypeName;
   }
   public void setExtendsTypeName(String ftn) {
      extendsTypeName = ftn;
   }

   public List<Object> getDeclaredProperties(String modifier, boolean includeAssigns, boolean includeModified, boolean editorProperties) {
      if (orig != null)
         return orig.getDeclaredProperties(modifier, includeAssigns, includeModified, editorProperties);
      if (!includeAssigns || includeModified)
         System.err.println("*** unimplemented option in ClientTypeDeclaration");
      return getDeclaredProperties();
   }

   public String getObjectId() {
      // Unique ids - MD = "metadata"
      return DynUtil.getObjectId(this, null, "MD_" + typeName);
   }

   public LayeredSystem getLayeredSystem() {
      if (orig != null)
         return orig.getLayeredSystem();
      return super.getLayeredSystem();
   }

   public Class getCompiledClass(boolean init) {
      if (orig != null)
         return orig.getCompiledClass(init);
      return super.getCompiledClass(init);
   }

   public Object getExtendsTypeDeclaration() {
      if (orig != null)
         return orig.getExtendsTypeDeclaration();
      return super.getExtendsTypeDeclaration();
   }

   private Map<String,Object> annotations;
   @Constant
   public void setAnnotations(Map<String,Object> an) {
      annotations = an;
   }
   public Map<String, Object> getAnnotations() {
      return annotations;
   }

   private int modifierFlags;
   public void setModifierFlags(int val) {
      modifierFlags = val;
   }
   public int getModifierFlags() {
      return modifierFlags;
   }

   private BodyTypeDeclaration modifiedType;
   @Constant
   public void setModifiedType(BodyTypeDeclaration mt) {
      modifiedType = mt;
   }
   public BodyTypeDeclaration getModifiedType() {
      return modifiedType;
   }

}
