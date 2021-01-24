/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ISemanticNode;
import sc.lang.SemanticNodeList;
import sc.lang.js.JSLanguage;
import sc.layer.LayeredSystem;
import sc.type.DynType;
import sc.util.StringUtil;

import java.util.List;

public class EnumConstant extends BodyTypeDeclaration {
   public List<Expression> arguments;

   // Set to true if there's a method of the same name.  This is used in the Javascript conversion, which unlike Java has one namespace shared by fields and methods (and the enum is converted to a field there)
   public transient boolean shadowedByMethod = false;

   public DeclarationType getDeclarationType() {
      return DeclarationType.ENUMCONSTANT;
   }

   public void init() {
      super.init();

      TypeDeclaration enclType = getEnclosingType();
      if (enclType.dynamicNew)
         dynamicNew = true;
      if (enclType.dynamicType)
         dynamicType = true;
   }

   public void validate() {
      super.validate();

      LayeredSystem sys = getLayeredSystem();
      TypeDeclaration enclType = getEnclosingType();
      if (sys != null && enclType != null && StringUtil.equalStrings(sys.getRuntimeName(), "js") && enclType.getMethods(typeName, null) != null)
         shadowedByMethod = true;
   }

   /** For this type only we add the enum constants as properties */
   public List<Object> getDeclaredProperties(String modifier, boolean includeAssigns, boolean includeModified, boolean includeInherited) {
      List<Object> res = super.getDeclaredProperties(modifier, includeAssigns, includeModified, includeInherited);

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
   public List<Object> getAllInnerTypes(String modifier, boolean thisClassOnly, boolean includeInherited) {
      List<Object> res = super.getAllInnerTypes(modifier, thisClassOnly, includeInherited);

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
         // Enum constant classes are static so they don't have outer objects
         enumTD.addModifier("static");
         // Making a copy here so we are not adding methods to an already started object when the parent type is not started.
         enumTD.setProperty("body", body.deepCopy(ISemanticNode.CopyNormal, null));

         // Need to create the enum constructor that takes the string and ordinal parameters
         ConstructorDefinition ctor = EnumDeclaration.newEnumConstructor(getLayeredSystem(), typeName, enumTD, enclType, arguments);
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

   public EnumConstant refreshNode() {
      if (typeName == null)
         return this;
      TypeDeclaration enclType = getEnclosingType();
      enclType = (TypeDeclaration) enclType.refreshNode();
      if (enclType == null)
         return this;
      Object enumConst = enclType.definesMember(typeName, MemberType.EnumOnlySet, null, null);
      if (enumConst == null || !(enumConst instanceof EnumConstant)) {
         System.err.println("*** Unable to refresh enum constant");
         return this;
      }
      return (EnumConstant) enumConst;
   }
}
