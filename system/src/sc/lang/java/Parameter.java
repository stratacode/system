/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.SemanticNodeList;

import java.util.*;

public class Parameter extends AbstractVariable implements IVariable {
   public SemanticNodeList<Object> variableModifiers;
   public JavaType type;
   public boolean repeatingParameter;
   public Parameter nextParameter;

   public static Parameter create(Object[] types, String[] names, ITypeParamContext ctx, ITypeDeclaration definedInType) {
      if (types == null || types.length == 0)
          return null;
      Parameter curr = null, first = null;
      int i = 0;
      for (Object type:types) {
         Parameter next = new Parameter();
         JavaType methType = JavaType.createFromParamType(type, ctx, definedInType);
         next.setProperty("type", methType);
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
      if (variableName != null && variableName.endsWith("[]")) {
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
         if (variableName.equals(name))
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
         baseType = new ArrayTypeDeclaration(getJavaModel().getModelTypeDeclaration(), baseType,"[]");
      if (arrayDimensions != null)
         baseType = new ArrayTypeDeclaration(getJavaModel().getModelTypeDeclaration(), baseType, arrayDimensions);
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
         baseType = new ArrayTypeDeclaration(getJavaModel().getModelTypeDeclaration(), baseType,"[]");
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

   public void addDependentTypes(Set<Object> types) {
      if (type != null)
         type.addDependentTypes(types);
      if (nextParameter != null)
         nextParameter.addDependentTypes(types);
   }

}
