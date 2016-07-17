/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ISemanticNode;
import sc.lang.SemanticNodeList;
import sc.lang.js.JSLanguage;
import sc.type.DynType;

import java.util.List;

public class EnumConstant extends BodyTypeDeclaration {
   public List<Expression> arguments;

   public DeclarationType getDeclarationType() {
      return DeclarationType.ENUMCONSTANT;
   }

   public void init() {
      super.init();
   }

   /** For this type only we add the enum constants as properties */
   public List<Object> getDeclaredProperties(String modifier, boolean includeAssigns, boolean includeModified) {
      List<Object> res = super.getDeclaredProperties(modifier, includeAssigns, includeModified);

      if (res == null)
         return null;

      // Need to remove the enum constants from the list before we return them.  We can't inherit those or it turns all recursive
      for (int i = 0; i < res.size(); i++) {
         if (ModelUtil.isEnum(res.get(i))) {
            res.remove(i);
            i--;
         }
      }

      return res;
   }

   /** Just like above - we cannot show the enum constants as children of themselves even if they inherit them through the type hierarchy */
   public List<Object> getAllInnerTypes(String modifier, boolean thisClassOnly) {
      List<Object> res = super.getAllInnerTypes(modifier, thisClassOnly);

      // Need to remove the enum constants from the list before we return them.  We can't inherit those or it turns all recursive
      if (res != null) {
         for (int i = 0; i < res.size(); i++) {
            if (ModelUtil.isEnum(res.get(i))) {
               res.remove(i);
               i--;
            }
         }
      }
      return res;
   }

   public boolean isAssignableFrom(ITypeDeclaration other, boolean assignmentSemantics) {
      return other == this; 
   }

   public boolean isAssignableTo(ITypeDeclaration other) {
      return other == this || ModelUtil.isAssignableFrom(other, getEnclosingType());
   }

   public Object getRuntimeType() {
      return getCompiledClass();
   }

   public Object getDerivedTypeDeclaration() {
      return getEnclosingType();
   }

   public boolean implementsType(String otherTypeName, boolean assignment, boolean allowUnbound) {
      String fte = getFullTypeName();
      if (fte != null && fte.equals(otherTypeName))
         return true;

      Object ext = getDerivedTypeDeclaration();
      if (ext != null) {
         if (ModelUtil.implementsType(ext, otherTypeName, assignment, allowUnbound))
            return true;
      }
      return false;
   }

   public boolean hasModifier(String modifier) {
      // Enum constants are always static - this is to avoid special casing the error detection code
      if (modifier.equals("static"))
         return true;
      return super.hasModifier(modifier);
   }

   public List<?> getClassTypeParameters() {
      return null;
   }

   public boolean isComponentType() {
      return false;  
   }

   public DynType getPropertyCache() {
      return null;
   }

   public boolean isRealType() {
      return true;
   }

   public boolean getDefinesCurrentObject() {
      return true;
   }

   public boolean isEnumConstant() {
      return true;
   }

   public boolean isStaticType() {
      return true;
   }

   public String getDefaultDynTypeClassName() {
      return "sc.lang.DynEnumConstant";
   }

   public List<Expression> getEnumArguments() {
      return arguments;
   }

   // As per what Java does when you do toString on the Enum
   public String toString() {
      return typeName;
   }

   public void transformEnumConstantToJS(ClassDeclaration cl) {
      boolean useEnumClass = false;
      BodyTypeDeclaration enclType = getEnclosingType();

      BodyTypeDeclaration enumTD;
      // For enums that have body definitions we need to declare
      if (body != null) {
         enumTD = ClassDeclaration.create("class", typeName, ClassType.create(enclType.typeName));
         // Making a copy here so we are not adding methods to an already started object when the parent type is not started.
         enumTD.setProperty("body", body.deepCopy(ISemanticNode.CopyNormal, null));

         // Need to create the enum constructor that takes the string and ordinal parameters
         ConstructorDefinition ctor = EnumDeclaration.newEnumConstructor(getLayeredSystem(), typeName, enumTD);
         enumTD.addBodyStatement(ctor);

         cl.addSubTypeDeclaration(enumTD);
         useEnumClass = true;
      }
      else
         enumTD = enclType;

      // Need to use the relative type name here for the useEnumClass case since the absolute one will not resolve properly (since absolute references still go against the official type which has not been converted from an EnumDefinition).
      String enumClass = useEnumClass ? typeName : enclType.getFullTypeName();
      SemanticNodeList enumArgs = new SemanticNodeList();
      enumArgs.add(StringLiteral.create(typeName));
      enumArgs.add(IntegerLiteral.create(getEnumOrdinal()));
      if (arguments != null) {
         enumArgs.addAll(arguments);
      }
      NewExpression newExpr = NewExpression.create(enumClass, enumArgs);
      newExpr.setParselet(JSLanguage.getJSLanguage().newExpression);
      ClassType enumClassType = (ClassType) ClassType.create(enumClass);
      //if (useEnumClass)
      //   enumClassType.type = enumTD;
      FieldDefinition enumField = FieldDefinition.createFromJavaType(enumClassType, typeName, "=", newExpr);
      enumField.addModifier("static");
      cl.addBodyStatement(enumField);
   }

   public AccessLevel getAccessLevel(boolean explicitOnly) {
      // enum constants are always public... at least they do not take modifiers :)
      return AccessLevel.Public;
   }

   public String getOperatorString() {
      return "<enum constant>";
   }
}
