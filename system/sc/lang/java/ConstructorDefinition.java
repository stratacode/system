/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.sc.ModifyDeclaration;

import java.util.Arrays;
import java.util.List;

public class ConstructorDefinition extends AbstractMethodDefinition {

   private transient boolean objectConstructor;

   public void start() {
      TypeDeclaration td = getEnclosingType();
      objectConstructor = td.getDeclarationType() == DeclarationType.OBJECT;

      if (name == null || !name.equals(td.typeName)) {
         displayError("Method: " + name + " missing return type (e.g. void).  For a constructor, use: " + td.typeName + ": ");
      }

      super.start();

   }

   /**
    * If you happen to refer to an object in the constructor it leads to an infinite loop which is a pain
    * so here we flag that as an error and don't hook up the reference.
    */
   public Object findType(String typeName, Object refType, TypeContext context) {
      if (objectConstructor) {
         TypeDeclaration td;
         if ((td = getEnclosingType()).typeName.equals(typeName)) {
            System.err.println("**** Illegal access to object of type: " + typeName + " from within its constructor: " + toDefinitionString());
            return td;
         }
      }
      return super.findType(typeName, refType, context);
   }


   public boolean isProperty() {
      return false;
   }

   public Object getReturnType() {
      return null;
   }


   public Object getTypeDeclaration(List<? extends ITypedObject> args) {
      return null;
   }

   public String getPropertyName() {
      return null;
   }

   public boolean hasGetMethod() {
      return false;
   }

   public boolean hasSetMethod() {
      return false;
   }

   public Object getGetMethodFromSet() {
      return null;  //To change body of implemented methods use File | Settings | File Templates.
   }

   public Object getSetMethodFromGet() {
      return null;  //To change body of implemented methods use File | Settings | File Templates.
   }

   public boolean isGetMethod() {
      return false;
   }

   public boolean isSetMethod() {
      return false;
   }

   public boolean isGetIndexMethod() {
      return false;
   }

   public boolean isSetIndexMethod() {
      return false;
   }

   public Object definesConstructor(List<?> methParams, ITypeParamContext ctx, boolean isTransformed) {
      if (parametersMatch(methParams, ctx))
         return this;
      return null;
   }

   /** When overriding constructors, we need to convert them to a method */
   public MethodDefinition convertToMethod(String methName) {
      MethodDefinition methDef = new MethodDefinition();
      PrimitiveType voidType = new PrimitiveType();
      voidType.typeName = "void";
      methDef.type = voidType;
      methDef.name = methName;
      methDef.arrayDimensions = arrayDimensions;
      methDef.typeParameters = typeParameters;
      methDef.modified = modified;
      //methDef.overriddenMethodName = methName;
      methDef.overriddenLayer = overriddenLayer;

      methDef.setProperty("parameters", parameters);
      methDef.setProperty("body", body);
      methDef.modifiers = modifiers;
      parentNode.replaceChild(this, methDef);
      return methDef;
   }

   public Object invoke(ExecutionContext ctx, List<Object> paramValues) {
      BodyTypeDeclaration pendingConst = ctx.getPendingConstructor();
      boolean setInst = false;

      // We have a case where there was a dynamic class created with a super in the top level method so we set the pending
      // constructor.  But that super tag called another constructor which has no super.  In this case, we'll just create it
      // here using the default constructor of the parent type.  There must be one right?
      if (pendingConst != null && !callsSuper()) {
         ctx.setPendingConstructor(null);

         Object inst = pendingConst.constructInstFromArgs(null, ctx, false);


         setInst = true;
         ctx.pushCurrentObject(inst);
      }
      TypeDeclaration td = getEnclosingType();
      if (td.getEnclosingInstType() != null) {
         // Strip off the hidden this param - no name for that value in the ExecutionContext.
         paramValues = paramValues.subList(1, paramValues.size());
      }
      try {
        return super.invoke(ctx, paramValues);
      }
      finally {
         if (setInst) {
            ctx.popCurrentObject();
         }
      }
   }

   public String getTypeSignature() {
      int num;
      TypeDeclaration td = getEnclosingType();
      StringBuilder sb = new StringBuilder();
      BodyTypeDeclaration outer;
      // Need to include the parent arg in the signature for this method.  The runtime really does not differentiate
      // this implicit parameter of the constructor so might be trickier to try and infer it all of the time?
      if ((outer = td.getEnclosingInstType()) != null) {
         sb.append(outer.getSignature());
      }
      if (parameters != null && (num = parameters.getNumParameters()) != 0) {
         List<Parameter> paramList = parameters.getParameterList();
         for (int i = 0; i < num; i++)
            sb.append(paramList.get(i).type.getSignature());
      }
      return sb.length() == 0 ? null : sb.toString();
   }

   /** Returns the constructor args for any compiled types which we extends from.  These constructorArgs needs to be compiled into the super call */
   public Expression[] getConstrArgs() {
      TypeDeclaration td = getEnclosingType();
      Object extType = td.getExtendsTypeDeclaration();
      if (extType != null && !ModelUtil.isDynamicType(extType))
         return super.getConstrArgs();
      return null;
   }

   public boolean isModifySuper() {
      // Don't count a constructor that is modifying a previous constructor.
      if (getEnclosingType() instanceof ModifyDeclaration) {
         ModifyDeclaration modType = (ModifyDeclaration) getEnclosingType();
         // We are modifying the super constructor so it's not a real extends super
         if (modType.declaresConstructor(Arrays.asList(getParameterTypes()), null) != null)
            return true;
      }
      return false;
   }

   /** Returns a real super() expression in the constructor - i.e. one that maps to an extended class.  Used for dynamic stubs to determine if we need to use the original super expression in the method to preserve the compile time contract. */
   public IdentifierExpression getSuperExpresssion() {
      BlockStatement bd = body;
      if (bd == null || bd.statements == null)
         return null;
      // Do not include super() expressions which are really invoking the previous type's constructor
      if (isModifySuper())
         return null;
      for (Statement st:bd.statements)
         if (st.callsSuper() && st instanceof IdentifierExpression)
            return (IdentifierExpression) st;
      return null;
   }

   public boolean useDefaultModifier() {
      BodyTypeDeclaration enclType = getEnclosingType();
      // Constructors for enums can't be public or protected
      if (enclType instanceof EnumDeclaration)
         return false;
      return enclType.useDefaultModifier();
   }
}


