/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.dyn.DynUtil;
import sc.lang.SemanticNodeList;
import sc.layer.LayeredSystem;
import sc.obj.IObjectId;

import java.util.*;

public class Parameter extends AbstractVariable implements IVariable, IObjectId {
   public SemanticNodeList<Object> variableModifiers;
   public JavaType type;
   public boolean repeatingParameter;
   public Parameter nextParameter;

   public static Parameter create(LayeredSystem sys, Object[] types, String[] names, ITypeParamContext ctx, ITypeDeclaration definedInType) {
      if (types == null || types.length == 0)
          return null;
      Parameter curr = null, first = null;
      int i = 0;
      for (Object type:types) {
         Parameter next = new Parameter();
         if (type != null) {
            JavaType methType = JavaType.createFromParamType(sys, type, ctx, definedInType);
            next.setProperty("type", methType);
         }
         next.variableName = names[i++];
         if (curr == null)
            first = curr = next;
         else {
            curr.setProperty("nextParameter", next);
            curr = next;
         }
      }
      return first;
   }

   public void init() {
      if (initialized)
         return;

      // Propagate the old-school C array dimension syntax to the type
      if (arrayDimensions != null) {
         type.arrayDimensions = arrayDimensions;
      }

      if (variableName != null && variableName.endsWith("[]")) {
         System.err.println("*** Is this reached?");
         int dimsIx = variableName.indexOf("[]");
         type.arrayDimensions = variableName.substring(dimsIx);
         variableName = variableName.substring(0,dimsIx);
      }
      super.init();
   }

   public Object definesMember(String name, EnumSet<MemberType> type, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed) {
      Object res;
      if (type.contains(MemberType.Variable)) {
         if (nextParameter != null && (res = nextParameter.definesMember(name, type, refType, ctx, skipIfaces, isTransformed)) != null)
            return res;
         if (variableName != null && variableName.equals(name))
            return this;
      }
      return super.definesMember(name, type, refType, ctx, skipIfaces, isTransformed);
   }

   private Object getBaseTypeDeclaration() {
      if (type == null)
         return null;

      return type.getTypeDeclaration();
   }

   public Object getTypeDeclaration() {
      Object baseType = getBaseTypeDeclaration();
      if (baseType == null)
         return null;
      if (repeatingParameter)
         baseType = new ArrayTypeDeclaration(getLayeredSystem(), getJavaModel().getModelTypeDeclaration(), baseType,"[]");
      /*
       * This is now applied during init or when the parameter is first retrieved from the list
      if (arrayDimensions != null)
         baseType = new ArrayTypeDeclaration(getJavaModel().getModelTypeDeclaration(), baseType, arrayDimensions);
      */
      return baseType;
   }

   public String getGenericTypeName(Object resultType, boolean includeDims) {
      if (type == null)
         return null;
      return type.getGenericTypeName(resultType, includeDims);
   }

   public String getAbsoluteGenericTypeName(Object resultType, boolean includeDims) {
      if (type == null)
         return null;
      return type.getAbsoluteGenericTypeName(resultType, includeDims);
   }

   public Object getComponentType() {
      if (!repeatingParameter && arrayDimensions == null)
         throw new UnsupportedOperationException();

      Object baseType = getBaseTypeDeclaration();
      if (baseType == null)
          return null;

      if (repeatingParameter && arrayDimensions != null)
         baseType = new ArrayTypeDeclaration(getLayeredSystem(), getJavaModel().getModelTypeDeclaration(), baseType,"[]");
      return baseType;
   }

   public List<Parameter> getParameterList() {
      if (nextParameter == null)
         return Collections.singletonList(this);
      else {
         List<Parameter> l = new ArrayList<Parameter>();
         Parameter current = this;
         while (current != null) {
            l.add(current);
            current = current.nextParameter;
         }
         return l;
      }
   }

   public Object[] getParameterTypes() {
      Object[] parameterTypes = null;
      int num = getNumParameters();
      if (num == 0)
         return null;
      parameterTypes = new Object[num];
      List<Parameter> paramList = getParameterList();
      for (int i = 0; i < num; i++)
         parameterTypes[i] = paramList.get(i).getTypeDeclaration();
      return parameterTypes;
   }

   public JavaType[] getParameterJavaTypes(boolean convertRepeating) {
      if (nextParameter == null) {
         JavaType retType = type;
         // Old style array dimensions
         if (!initialized && arrayDimensions != null && retType.arrayDimensions == null)
            retType.arrayDimensions = arrayDimensions;
         if (convertRepeating && repeatingParameter)
            retType = retType.convertToArray(null);
         return new JavaType[]{retType};
      }
      else {
         JavaType[] arr = new JavaType[getNumParameters()];
         Parameter current = this;
         int ix = 0;
         while (current != null) {
            JavaType useType = current.type;
            if (current.repeatingParameter)
               useType = useType.convertToArray(null);
            arr[ix++] = useType;
            if (!current.initialized && arrayDimensions != null && useType.arrayDimensions == null)
               useType.arrayDimensions = current.arrayDimensions;
            current = current.nextParameter;
         }
         return arr;
      }
   }

   public int getNumParameters() {
      int ct = 1;
      Parameter current = nextParameter;
      while (current != null) {
         ct++;
         current = current.nextParameter;
      }
      return ct;
   }

   public String[] getParameterNames() {
      int num = getNumParameters();
      String[] names = new String[num];
      Parameter first = this;
      for (int i = 0; i < num; i++) {
         names[i] = first.variableName;
         first = first.nextParameter;
      }
      return names;
   }

   public void refreshBoundType(int flags) {
      if (type != null)
         type.refreshBoundType(flags);
      if (nextParameter != null)
         nextParameter.refreshBoundType(flags);
   }

   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {
      if (type != null)
         type.addDependentTypes(types, mode);
      if (nextParameter != null)
         nextParameter.addDependentTypes(types, mode);
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      if (type != null)
         sb.append(type);
      else
         sb.append("<null>");
      sb.append(" ");
      if (variableName != null)
         sb.append(variableName);
      else
         sb.append("<null>");

      return sb.toString();
   }

   public String getParameterTypeName() {
      Object type = getTypeDeclaration();
      if (type == null)
         return "<no type>";
      return ModelUtil.getTypeName(type);
   }

   public String getObjectId() {
      return DynUtil.getObjectId(this, null, "PMD_" + getParameterTypeName()  + "_" + variableName);
   }
}
