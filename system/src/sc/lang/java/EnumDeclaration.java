/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.DynEnumConstant;
import sc.lang.ISemanticNode;
import sc.lang.SemanticNodeList;
import sc.layer.LayeredSystem;
import sc.parser.ParseUtil;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

public class EnumDeclaration extends TypeDeclaration {

   // A dummy enum we use for looking up the values and valueOf methods
   public static enum DUMMY { }

   public transient MethodDefinition valuesMethod;
   public transient MethodDefinition valueOfMethod;

   /** For some purposes, like JS we need to convert this to a class.  This holds that class in that case. */
   public transient ClassDeclaration enumClass = null;

   public DeclarationType getDeclarationType() {
      return DeclarationType.ENUM;
   }

   public static class ValuesMethodDefinition extends MethodDefinition {
      public Object invoke(ExecutionContext ctx, List<Object> paramValues) {
         BodyTypeDeclaration enumDecl = (BodyTypeDeclaration) ctx.getCurrentStaticType();
         return enumDecl.getEnumValues();
      }
   }

   public static class ValueOfMethodDefinition extends MethodDefinition {
      public Object invoke(ExecutionContext ctx, List<Object> paramValues) {
         BodyTypeDeclaration enumDecl = (BodyTypeDeclaration) ctx.getCurrentStaticType();
         String name = (String) paramValues.get(0);
         int index = enumDecl.getDynStaticFieldIndex(name);
         if (index == -1)
            throw new IllegalArgumentException("No enum constant named: " + name + " for enum type: " + this);
         return enumDecl.getStaticValues()[index];
      }
   }

   private void initValuesMethod() {
      if (valuesMethod == null) {
         valuesMethod = new ValuesMethodDefinition();
         valuesMethod.name = "values";
         valuesMethod.addModifier("static");
         ClassType retType = (ClassType) ClassType.create(getFullTypeName());
         retType.arrayDimensions = "[]";
         valuesMethod.setProperty("type", retType);
         addToHiddenBody(valuesMethod);
      }
   }

   private void initValueOfMethod() {
      if (valueOfMethod == null) {
         valueOfMethod = new ValueOfMethodDefinition();
         valueOfMethod.setProperty("parameters", new Parameter());
         valueOfMethod.parameters.setProperty("type", ClassType.create("java.lang.String"));
         valueOfMethod.parameters.variableName = "name";
         valueOfMethod.name = "valueOf";
         valueOfMethod.setProperty("type", ClassType.create(getFullTypeName()));
         valueOfMethod.addModifier("static");
         addToHiddenBody(valueOfMethod);
      }
   }

   public Object declaresMethod(String name, List<? extends Object> types, ITypeParamContext ctx, Object refType, boolean staticOnly, Object inferredType, boolean includeModified) {
      Object o = super.declaresMethod(name, types, ctx, refType, staticOnly, inferredType, includeModified);
      if (o != null)
         return o;

      if (name.equals("values") && types.size() == 0) {
         initValuesMethod();
         return valuesMethod;
      }

      if (name.equals("valueOf")) {
         initValueOfMethod();
         return valueOfMethod;
      }

      // For the static methods, we'll define new MethodDefinition objects and add them to the hiddenBody which implement
      // the values and valueOf methods.   For other methods, get the runtime type.  if it is a dynamic enum type,
      // we use DynEnumConstant.class to get the method.  For static ones, return the method on the enum class itself.
      
      return ModelUtil.definesMethod(DynEnumConstant.class, name, types, ctx, refType, false, staticOnly, inferredType);
   }

   public Object definesMethod(String name, List<?> types, ITypeParamContext ctx, Object refType, boolean isTransformed, boolean staticOnly, Object inferredType) {
      Object o = super.definesMethod(name, types, ctx, refType, isTransformed, staticOnly, inferredType);
      if (o != null)
         return o;

      // All enum types inherit the methods from the java.lang.Enum
      o = ModelUtil.definesMethod(java.lang.Enum.class, name, types, ctx, refType, isTransformed, staticOnly, inferredType);
      if (o != null)
         return o;
      return null;
   }

   public void unregister() {
   }

   public boolean implementsType(String fullTypeName, boolean assignment, boolean allowUnbound) {
      // TODO: should we implement this by just calling ModelUtil.implementsType(Enum.class, fullTypeName)
      if (fullTypeName.equals("java.lang.Enum") || fullTypeName.equals("java.io.Serializable") || fullTypeName.equals("java.lang.Comparable"))
         return true;
      return super.implementsType(fullTypeName, assignment, allowUnbound);
   }

   /** For this type only we add the enum constants as properties */
   public List<Object> getDeclaredProperties(String modifier, boolean includeAssigns, boolean includeModified) {
      List<Object> res = super.getDeclaredProperties(modifier, includeAssigns, includeModified);

      if (body != null) {
         for (int i = 0; i < body.size(); i++) {
            Definition member = body.get(i);
            if (ModelUtil.isEnum(member)) {
               if (res == null)
                  res = new ArrayList<Object>();
               res.add(member);
            }
         }
      }
      return res;
   }

   /**
    * For an object definition where we are optimizing out the class, we need to skip to get the actual runtime
    * class.  Then, convert that to a type name, then see if there is a class available.
    */
   public Class getCompiledClass() {
      String typeName = getFullTypeName();
      // Inner classes, or those defined in methods may not have a type name and so no way to look them up
      // externally.
      if (typeName == null)
         return null;
      JavaModel model = getJavaModel();
      Object classObj = model.getClass(typeName, false, model.getLayer(), model.isLayerModel, true);
      if (classObj instanceof Class)
         return (Class) classObj;
      return null;
   }

   public boolean isEnumeratedType() {
      return true;
   }

   private int getEmptyIndex() {
      int ix = -1;
      if (body != null) {
         for (int i = 0; i < body.size(); i++) {
            Statement st = body.get(i);
            if (st instanceof EmptyStatement) {
               ix = i;
               break;
            }
            if (!(st instanceof EnumConstant))
               System.out.println("*** Error: invalid EnumDeclaration - must have EnumConstant list followed by EmptyStatement, then body declarations");
         }
      }
      return ix;
   }

   public void addBodyStatement(Statement s) {
      if (!(s instanceof EmptyStatement || s instanceof EnumConstant)) {
         int ix = getEmptyIndex();
         if (ix == -1)
            super.addBodyStatement(new EmptyStatement());
      }
      super.addBodyStatement(s);
   }

   public void addBodyStatementsAt(int ix, List<Statement> statements) {
      int eix = getEmptyIndex();
      if (eix == -1)
         super.addBodyStatement(new EmptyStatement());
      super.addBodyStatementsAt(eix + ix + 1, statements);
   }

   static Object[] defaultConstrParamTypes = {String.class, Integer.class};
   static String[] defaultConstrParamNames = {"_name", "_ordinal"};

   public ClassDeclaration transformEnumToJS() {
      if (enumClass != null)
         return enumClass;

      SemanticNodeList<Statement> clBody = new SemanticNodeList<Statement>();

      ClassDeclaration enumCl = ClassDeclaration.create("class", typeName, ClassType.create("java.lang.Enum"));
      // Inner enums are implicitly static
      if (getEnclosingType() != null)
         enumCl.addModifier("static");
      enumCl.parentNode = parentNode;
      enumCl.setProperty("implementsTypes", implementsTypes);
      enumCl.setProperty("body", clBody);
      String enumTypeName = getFullTypeName();
      SemanticNodeList<Expression> valArrayArgs = new SemanticNodeList<Expression>();
      int lastConstIx = 0;
      if (body != null) {
         for (Statement st:body) {
            if (st instanceof EnumConstant) {
               EnumConstant ec = (EnumConstant) st;
               ec.transformEnumConstantToJS(enumCl);
               lastConstIx = enumCl.body.size();
               valArrayArgs.add(IdentifierExpression.create(enumTypeName, ec.typeName));
            }
            else if (!(st instanceof EmptyStatement))
               enumCl.addBodyStatement(st);
         }
      }

      // Add this in so we can resolve the type of the method in code we add to thise type.
      initValuesMethod();
      MethodDefinition tMeth = (MethodDefinition) valuesMethod.deepCopy(ISemanticNode.CopyNormal, null);
      tMeth.addModifier("abstract");
      enumCl.addBodyStatement(tMeth);

      initValueOfMethod();
      tMeth = (MethodDefinition) valueOfMethod.deepCopy(ISemanticNode.CopyNormal, null);
      tMeth.addModifier("abstract");
      enumCl.addBodyStatement(tMeth);

      SemanticNodeList<Expression> arrDimensions = new SemanticNodeList<Expression>(1);
      arrDimensions.add(null);
      FieldDefinition valField = FieldDefinition.createFromJavaType(ClassType.create("Object[]"), "_values", "=", NewExpression.create("Object", arrDimensions, ArrayInitializer.create(valArrayArgs)));
      valField.addModifier("static");
      enumCl.addBodyStatementAt(lastConstIx, valField);

      // Prevent the initSyncProcess from running on the transformed class (since it already ran here)
      enumCl.syncPropertiesInited = syncPropertiesInited;
      enumCl.syncProperties = syncProperties;
      enumCl.cachedNeedsSync = cachedNeedsSync;

      Object[] constrs = getConstructors(null);
      if (constrs != null && constrs.length > 0) {
         for (Object constrObj:constrs) {
            if (constrObj instanceof ConstructorDefinition) {
               ConstructorDefinition constr = (ConstructorDefinition) constrObj;
               constr = (ConstructorDefinition) constr.deepCopy(ISemanticNode.CopyNormal, null);

               ArrayList<Object> newTypes = new ArrayList<Object>();
               ArrayList<String> newNames = new ArrayList<String>();
               Object[] oldTypes = constr.getParameterTypes(false);
               String[] oldNames = constr.parameters.getParameterNames();

               for (int i = 0; i < defaultConstrParamNames.length; i++) {
                  newTypes.add(defaultConstrParamTypes[i]);
                  newNames.add(defaultConstrParamNames[i]);
               }

               if (oldTypes != null) {
                  for (int i = 0; i < oldTypes.length; i++) {
                     newTypes.add(oldTypes[i]);
                     newNames.add(oldNames[i]);
                  }
               }

               constr.setProperty("parameters", Parameter.create(newTypes.toArray(new Object[newTypes.size()]), newNames.toArray(new String[newNames.size()]), null, this));
               addEnumSuperCall(constr);
               enumCl.addBodyStatement(constr);

               ParseUtil.restartComponent(constr);
            }
         }
      }
      else {
         ConstructorDefinition constr = newEnumConstructor(typeName, this);
         enumCl.addBodyStatement(constr);
      }

      // We used to replace the regular enum decl with the class but this messes up a subsequent transform.  Instead
      // The JS code pulls out the enum class anyway so this seems like a better plan.  The JS java code will still use
      // an enum.
      //parentNode.replaceChild(this, enumCl);

      enumClass = enumCl;
      return enumCl;
   }

   static ConstructorDefinition newEnumConstructor(String typeName, ITypeDeclaration definedInType) {
      ConstructorDefinition constr = new ConstructorDefinition();
      constr.name = typeName;
      constr.setProperty("parameters", Parameter.create(defaultConstrParamTypes, defaultConstrParamNames, null, definedInType));
      addEnumSuperCall(constr);
      return constr;
   }

   static void addEnumSuperCall(ConstructorDefinition constr) {
      SemanticNodeList<Expression> args = new SemanticNodeList<Expression>();
      for (String pname:defaultConstrParamNames) {
         args.add(IdentifierExpression.create(pname));
      }
      constr.addStatement(IdentifierExpression.createMethodCall(args, "super"));
   }

   public EnumDeclaration deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap) {
      EnumDeclaration res = (EnumDeclaration) super.deepCopy(options, oldNewMap);

      if ((options & CopyInitLevels) != 0) {
         res.valuesMethod = valuesMethod;
         res.valueOfMethod = valueOfMethod;

         res.enumClass = enumClass;
      }
      return res;
   }

   public boolean needsTransform() {
      LayeredSystem sys = getLayeredSystem();
      return super.needsTransform() || (sys.runtimeProcessor != null && sys.runtimeProcessor.getNeedsEnumToClassConversion());
   }

   public boolean useDefaultModifier() {
      return true;
   }

   public String getOperatorString() {
      return "enum";
   }
}
