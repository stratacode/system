/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.bind.Bind;
import sc.bind.ConstantBinding;
import sc.classfile.CFClass;
import sc.dyn.DynUtil;
import sc.dyn.IDynObject;
import sc.lang.*;
import sc.lang.html.Body;
import sc.lang.js.JSRuntimeProcessor;
import sc.lang.js.JSUtil;
import sc.lang.sc.PropertyAssignment;
import sc.lang.template.Template;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.obj.ComponentImpl;
import sc.parser.*;
import sc.type.*;
import sc.util.PerfMon;
import sc.util.StringUtil;
import sc.bind.BindingDirection;
import sc.bind.IBinding;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.*;

public class IdentifierExpression extends ArgumentsExpression {
   public List<IString> identifiers;
   public NewExpression innerCreator;

   public List<IString> getAllIdentifiers() {
      return identifiers;
   }

   // One identifier type in each slot of the idTypes array.  This corresponds to one element in the "a.b" expression
   public enum IdentifierType {
      BoundName,        // Used in interprete mode - referencedObject is "the" value to return
      PackageName,      // This identifier is a component in a package name.  Preceded either BoundType or BoundObjectName
      BoundTypeName,    // This slot refers to an actual type - referencedObject is either a type declaration or class.
      BoundObjectName,  // This slot refers to an "object" definition.  Otherwise similar to the above.
      ResolvedObjectName, // A special type of object that we resolved via a customResolver.
      UnboundName,      // Did not find a name for this slot
      VariableName,     // Bound to a local var, param, for var, etc.
      FieldName,        // Bound to a field
      GetVariable,      // A slot x which is bound to getX
      IsVariable,       // A slot x which is bound to isX
      SetVariable,      // The above when it is part of an assignment expression (isAssignment = true)
      ThisExpression,   // 'this' appears in this slot.
      SuperExpression,  // 'super' appears in this slot
      MethodInvocation, // A bound method name
      RemoteMethodInvocation, // A method that's not bound in this runtime but is bound in a synchronized runtime.
      UnboundMethodName, // unrecognized method name
      ArraySelector,     // Used by the SelectorExpression's array selector - not used by this class.
      GetSetMethodInvocation, // A weird case - we have a getX or setX which matches a field not yet converted
      GetObjectMethodInvocation, // Another weird case - we have a getX which matches an object not yet converted
      EnumName,          // Bound to an EnumConstant or object implementing java.lang.Enum
      Unknown
    }

   transient IdentifierType[] idTypes;
   transient Object[] boundTypes;
   transient boolean isAssignment;
   transient boolean referenceInitializer;

   transient boolean jsTransformed = false;

   transient Object inferredType;
   transient boolean inferredFinal = true;

   public static IdentifierExpression create(IString... args) {
      IdentifierExpression ie = new IdentifierExpression();
      SemanticNodeList<IString> ids = new SemanticNodeList<IString>(args.length);
      for (IString arg:args) {
         ids.add(arg);
      }

      ie.setProperty("identifiers", ids);
      return ie;
   }

   public static IdentifierExpression create(String... args) {
      IdentifierExpression ie = new IdentifierExpression();
      ie.setIdentifiersFromArgs(args);
      return ie;
   }

   public static IdentifierExpression createMethodCall(SemanticNodeList arguments, String... idents) {
      IdentifierExpression ie = new IdentifierExpression();
      ie.setIdentifiersFromArgs(idents);
      if (arguments != null)
         ie.setProperty("arguments", arguments);
      return ie;
   }

   protected void setIdentifiersFromArgs(String... args) {
      SemanticNodeList<IString> ids = new SemanticNodeList<IString>(args.length);
      for (String arg:args) {
         if (arg == null)
            continue;
         // Strings with the "." need to be split up into separate identifiers
         if (arg.indexOf(".") != -1) {
            String[] toAdd = StringUtil.split(arg, '.');
            ids.addAll(Arrays.asList(PString.toPString(toAdd)));
         }
         else {
            ids.add(PString.toIString(arg));
         }
      }

      setProperty("identifiers", ids);
   }

   public void setAssignment(boolean assign) {
      isAssignment = assign;
   }

   private void resolveTypeReference() {
      int sz;
      Object referencedObject;

      try {
         PerfMon.start("resolveIdentifierExpression");

      List<IString> idents = getAllIdentifiers();

      if (idents != null && (sz = idents.size()) > 0) { // NewExpression may not be chained off of an identifier
         int nextIx = 1;

         // Preserve the arrays here - if we were cloned, we still try to start but if we can't resolve our type use the
         // type in the old version.
         if (idTypes == null || idTypes.length != sz)
            idTypes = new IdentifierType[sz];
         else {
            boolean resolved = true;
            for (int idx = 0; idx < sz; idx++)
               if (idTypes[idx] == null || idTypes[idx] == IdentifierType.UnboundName || idTypes[idx] == IdentifierType.UnboundMethodName) {
                  resolved = false;
                  break;
               }
            // This type was copied via deepCopy - it's already been resolved so no need to try and do that again.
            if (resolved) {
               propagateInferredTypes();
               return;
            }
         }
         if (boundTypes == null || boundTypes.length != sz)
            boundTypes = new Object[sz];
         JavaModel model = getJavaModel();
         boolean useExtensions = model != null && model.enableExtensions();

         ITypeDeclaration enclType = getEnclosingIType();

         String firstIdentifier = idents.get(0).toString();

         // Sync uses very simple identifiers to refer to objects, set via the customResolver hook.  When that's in
         // place we look up the type thee and return an identifier for it so we can evaluate it, etc.
         if (arguments == null && model != null && model.customResolver != null) {
            Object type = model.customResolver.resolveType(model.getPackagePrefix(), getIdentifierPathName(sz), true, null);
            if (type != null) {
               for (int i = 0; i < sz - 1; i++)
                  idTypes[i] = IdentifierType.PackageName; // TODO: or bound type if there's a super-type?
               idTypes[sz-1] = IdentifierType.ResolvedObjectName;
               boundTypes[sz-1] = type;
               propagateInferredTypes();
               return;
            }
         }

         if (firstIdentifier.equals("this")) {
            idTypes[0] = IdentifierType.ThisExpression;

            if (sz == 1 && arguments != null && enclType != null) {
               AbstractMethodDefinition enclMeth = getEnclosingMethod();
               if (enclMeth == null) {
                  displayTypeError("Invalid this method call - must be in a method: ");
               }
               else if (!(enclMeth instanceof ConstructorDefinition)) {
                  displayTypeError("this() must be inside of a constructor: ");
               }
               else {
                  Object constr = ModelUtil.declaresConstructor(getLayeredSystem(), enclType, arguments, null);
                  if (constr == null) {
                     if (model != null && !model.disableTypeErrors && isInferredFinal() && isInferredSet()) {
                        String othersMessage = getOtherConstructorsMessage(enclType, "\n   Did you mean:\n");
                        displayTypeError("No constructor matching: ", ModelUtil.argumentsToString(arguments), othersMessage, " for: ");
                     }
                  }
                  else if (constr == enclMeth) {
                     displayTypeError("Illegal recursive this", ModelUtil.argumentsToString(arguments), " method call for: ");
                     constr = ModelUtil.declaresConstructor(getLayeredSystem(), enclType, arguments, null);
                  }
                  else
                     boundTypes[0] = constr;
               }
            }
            else
               boundTypes[0] = enclType;
         }
         else if (firstIdentifier.equals("super")) {
            ITypeDeclaration thisType = enclType;

            if (thisType == null) {
               displayRangeError(0, 0, "Invalid super expression - no enclosing type for: ");
            }
            else {
               Object superType = thisType.getDerivedTypeDeclaration();
               Object extendsType = thisType.getExtendsTypeDeclaration();

               if (sz == 1) {
                  if (arguments == null) {
                     displayError("'super' must have arguments or property reference: ");
                  }
                  // The super(..) which implies a constructor.  Here we bind to the constructor itself, unless there is no
                  // constructor in which case we will bind to the type.
                  else if (superType != null) {
                     AbstractMethodDefinition enclMeth = getEnclosingMethod();
                     if (enclMeth == null) {
                        displayTypeError("Invalid super method call - must be in a method: ");
                     }
                     else if (!(enclMeth instanceof ConstructorDefinition)) {
                        displayTypeError("super() must be inside of a constructor: ");
                     }
                     else {
                        Object constr = ModelUtil.declaresConstructor(getLayeredSystem(), superType, arguments, null);
                        // When transforming to JS, we need to resolve the super(name, ordinal) constructor which is part of the base type in JS only
                        if (constr == null && superType instanceof EnumDeclaration && arguments.size() == 2) {
                           constr = superType;
                        }
                        if (constr == null) {
                           if (extendsType != superType && extendsType != null) {
                              constr = ModelUtil.declaresConstructor(getLayeredSystem(), extendsType, arguments, null);
                              if (constr != null)
                                 boundTypes[0] = constr;
                           }
                           if (constr == null) {
                              Object[] constructors = ModelUtil.getConstructors(superType, null);
                              if (arguments.size() > 0 || (constructors != null && constructors.length > 0)) {
                                 if (model != null && !model.disableTypeErrors && isInferredSet() && isInferredFinal()) {
                                    String typeMessage = superType == extendsType || extendsType == null ?
                                                      " super type: " + ModelUtil.getTypeName(superType) :
                                                      " modified/super types: " + ModelUtil.getTypeName(superType) + "/" + ModelUtil.getTypeName(extendsType);
                                    String othersMessage = getOtherConstructorsMessage(superType, "\n   Did you mean:\n");
                                    if (extendsType != superType && extendsType != null)
                                       othersMessage += getOtherConstructorsMessage(extendsType, "\n   Or one of the super type's constructors:\n");
                                    displayTypeError("No constructor matching: ", ModelUtil.argumentsToString(arguments), othersMessage, " for " + typeMessage + " in: ");
                                    constr = ModelUtil.declaresConstructor(getLayeredSystem(), superType, arguments, null);
                                    if (superType != extendsType && extendsType != null)
                                       constr = ModelUtil.declaresConstructor(getLayeredSystem(), extendsType, arguments, null);
                                 }
                              }
                              else {
                                 boundTypes[0] = superType; // The zero arg constructor case
                              }
                           }
                        }
                        else
                           boundTypes[0] = constr;
                     }
                  }
                  else {
                     displayTypeError("No type for 'super'.  Parent type: ", enclType.getTypeName(), " has no extends or modified type for: ");
                  }
               }
               else {
                  if (superType == null)
                     boundTypes[0] = Object.class;
                  else
                     boundTypes[0] = superType;
               }
               // Should we set this to null if it's unbound?
               idTypes[0] = IdentifierType.SuperExpression;
            }
         }
         else if (sz == offset()) {
            if (arguments != null && !(this instanceof NewExpression)) {
               Object foundMeth = findMethod(firstIdentifier, arguments, this, enclType, isStatic(), inferredType);
               if (foundMeth != null) {
                  foundMeth = parameterizeMethod(this, foundMeth, null, inferredType, arguments, getMethodTypeArguments());
                  idTypes[0] = IdentifierType.MethodInvocation;
                  boundTypes[0] = foundMeth;
               }
               if (boundTypes[0] == null) {
                  String propertyName = isAssignment ? ModelUtil.isSetMethod(firstIdentifier, ModelUtil.listToTypes(arguments), null) :
                          ModelUtil.isGetMethod(firstIdentifier, ModelUtil.listToTypes(arguments), null);

                  if (propertyName != null) {
                     Object fieldObj = findMember(propertyName, MemberType.FieldSet, this, enclType, null, false);
                     if (fieldObj instanceof VariableDefinition) {
                        boundTypes[0] = fieldObj;
                        idTypes[0] = IdentifierType.GetSetMethodInvocation;
                     }
                     else if (fieldObj == null && !isAssignment) {
                        Object typeObj = findType(firstIdentifier, enclType, null);
                        if (typeObj != null && ModelUtil.isObjectType(typeObj)) {
                           boundTypes[0] = typeObj;
                           idTypes[0] = IdentifierType.GetObjectMethodInvocation;
                        }
                     }
                  }
               }
               if (boundTypes[0] instanceof ITypeDeclaration) {
                  idTypes[0] = IdentifierType.BoundObjectName;
               }
               // TODO: When this identifier expression is inside of of a Template, the enclType is the rootType but this expression still lives in the
               // template hierarchy.  So when it does the findMethod inside of the Template it never checks the root type.  If the enclType is the Template
               // the template checks the root type.  So either we should do findMethod originally on encltype or maybe the encltype should be the template?
               if (boundTypes[0] == null && enclType instanceof BodyTypeDeclaration) {
                  boundTypes[0] = ((BodyTypeDeclaration) enclType).findMethod(firstIdentifier, arguments, this, enclType, isStatic(), inferredType);
               }
               if (boundTypes[0] == null) {
                  checkRemoteMethod(this, null, firstIdentifier, 0, idTypes, boundTypes, arguments, false, inferredType);
               }
               if (boundTypes[0] == null && model != null && !model.disableTypeErrors && isInferredSet() && isInferredFinal()) {
                  String otherMethods = enclType == null ? "" : getOtherMethodsMessage(enclType, firstIdentifier);
                  displayRangeError(0, 0, "No method named: ", firstIdentifier, ModelUtil.argumentsToString(arguments), otherMethods, " for: ");
                  foundMeth = findMethod(firstIdentifier, arguments, this, enclType, isStatic(), inferredType);
               }
               if (idTypes[0] == null) {
                  idTypes[0] = IdentifierType.MethodInvocation;
               }
            }
            else {
               // Type defined in this file
               // Precedence rules: look for a local variable definition first.  Then a get then a field.
               Object varObj = findMember(firstIdentifier, MemberType.VariableSet, this, null, null, false);
               Object typeObj;
               Object propObj = null;
               boolean isVariable;
               if (varObj == null) {
                  // Note: using PropertyAssignment set here so that we pick up any Bindable annotations on that property
                  // assignment.
                  EnumSet<MemberType> toSearchFor = useExtensions ?
                          (isAssignment ? MemberType.PropertySetSet : MemberType.PropertyAssignmentSet) : MemberType.FieldEnumSet;
                  propObj = varObj = findMember(firstIdentifier, toSearchFor, this, enclType, null, false);
                  typeObj = findType(firstIdentifier, enclType, null);
                  isVariable = false;
               }
               else {
                  isVariable = true;
                  typeObj = null;
               }

               // If we are binding a class, we'll check global types even if we have a member of that name.  In that case,
               // the type should override the var I think.  Object is one problematic case.  We might end up with a generated
               // field or getX method for the object name resolution.  In that case, it should not matter which one you use?
               // It's slow to search for global type names.
               if (!isVariable && typeObj == null && model != null  && (allowClassBinding() || varObj == null)) {
                  typeObj = model.findTypeDeclaration(firstIdentifier, varObj == null);
               }

               if (typeObj != null && ModelUtil.isObjectType(typeObj)) {
                  idTypes[0] = IdentifierType.BoundObjectName;
                  boundTypes[0] = typeObj;
                  if (varObj != null && varObj instanceof IVariable) {
                     IVariable var = (IVariable) varObj;
                     Object varTypeObj = ModelUtil.getVariableTypeDeclaration(var);
                     if (varTypeObj != null && !ModelUtil.isAssignableFrom(varTypeObj, typeObj) && !inNamedPropertyMethod(firstIdentifier))
                        displayError("Ambiguous object expression - variable: " + ModelUtil.toDefinitionString(var) + " and object: " + ModelUtil.toDefinitionString(typeObj) + " from: ");
                  }
               }
               else if (typeObj instanceof Class || typeObj instanceof CFClass) {
                  if (allowClassBinding()) {
                     idTypes[0] = IdentifierType.BoundTypeName;
                     boundTypes[0] = typeObj;
                  }
                  else {
                     idTypes[0] = IdentifierType.UnboundName;
                  }
               }
               else if (typeObj instanceof TypeDeclaration) {
                  TypeDeclaration type = (TypeDeclaration) typeObj;

                  // this is usually illegal by itself except when this expression is the expression
                  // in a SelectorExpression where the first selector is "this".
                  if (type.getDeclarationType() != DeclarationType.OBJECT && allowClassBinding()) {
                     idTypes[0] = IdentifierType.BoundTypeName;
                     boundTypes[0] = type;
                  }
                  else {
                     idTypes[0] = IdentifierType.UnboundName;
                  }
               }
               else if (ModelUtil.isEnum(typeObj)) {
                  idTypes[0] = IdentifierType.EnumName;
                  boundTypes[0] = typeObj;
               }
               // In interpreted mode, CommandVModel will return the CommandInterpreter as kind of like a constant
               else if (typeObj != null) {
                  idTypes[0] = IdentifierType.BoundName;
                  boundTypes[0] = typeObj;
               }
               else {
                  if (idTypes == null) {
                     System.out.println("*** Invalid identifier expression!");
                     return;
                  }
                  idTypes[0] = IdentifierType.UnboundName;
               }

               if (idTypes[0] == IdentifierType.UnboundName) {
                  if (varObj != null) {
                     boundTypes[0] = varObj;
                     if (propObj instanceof ParamTypedMember)
                        varObj = propObj = ((ParamTypedMember) propObj).getMemberObject();
                     if (propObj != null && ModelUtil.isObjectType(propObj))
                        idTypes[0] = IdentifierType.BoundObjectName;
                     else {
                        boolean needsGetSet = isAssignment ? ModelUtil.hasSetMethod(propObj) : ModelUtil.isPropertyGetSet(propObj);
                        if (!useExtensions) {
                           idTypes[0] = ModelUtil.isField(varObj) || ModelUtil.hasField(varObj) ? IdentifierType.FieldName : ModelUtil.isEnum(varObj) ?
                                   IdentifierType.EnumName : IdentifierType.VariableName;
                        }
                        else {
                           idTypes[0] = needsGetSet ?
                                   (isAssignment ? IdentifierType.SetVariable :
                                           ModelUtil.isPropertyIs(propObj) ? IdentifierType.IsVariable : IdentifierType.GetVariable) :
                                   ModelUtil.isField(varObj) || ModelUtil.hasField(varObj) ? IdentifierType.FieldName : ModelUtil.isEnum(varObj) ?
                                           IdentifierType.EnumName : IdentifierType.VariableName;
                        }
                     }
                  }
               }
               if (idTypes[0] == IdentifierType.UnboundName) {
                  displayRangeError(0, 0, "No identifier: ", firstIdentifier, " in ");

                  // TODO remove or keep commented out, this is for diagnostics, when this fails and we want to debug the code path
                  EnumSet<MemberType> mTypes = isAssignment ? MemberType.PropertySetSet : MemberType.PropertyGetSet;
                  Object memb = findMember(firstIdentifier, mTypes, this, enclType, null, false);
               }
            }
         }
         else if ((referencedObject = findMember(firstIdentifier, MemberType.VariableSet, this, enclType, null, false)) != null) {
            boundTypes[0] = referencedObject;
            idTypes[0] = ModelUtil.isField(referencedObject) ? IdentifierType.FieldName : IdentifierType.VariableName;
         }
         else if ((referencedObject = findMember(firstIdentifier, useExtensions ? MemberType.PropertyAssignmentSet : MemberType.FieldEnumSet, this, enclType, null, false)) != null) {
            boundTypes[0] = referencedObject;
            idTypes[0] = getIdentifierTypeFromType(boundTypes[0]);
         }
         else {
            if ((boundTypes[0] = findType(firstIdentifier, enclType, null)) != null ||
                    ((model = getJavaModel()) != null && (boundTypes[0] = model.findTypeDeclaration(firstIdentifier, true)) != null)) {
               if (boundTypes[0] instanceof ITypeDeclaration) {
                  if (((ITypeDeclaration)boundTypes[0]).getDeclarationType() == DeclarationType.OBJECT)
                     idTypes[0] = IdentifierType.BoundObjectName;
                     // If we can resolve this identifier through the custom resolver, it's an object.  This handles class types which are top-level types like
                     // pages etc. which are created as objects.
                  else if (model == null || model.customResolver == null || model.customResolver.resolveType(model.getPackagePrefix(), firstIdentifier, false, null) == null)
                     idTypes[0] = IdentifierType.BoundTypeName;
                  else
                     idTypes[0] = IdentifierType.ResolvedObjectName;
               }
               // TODO: detect "object" types from the class
               else if (boundTypes[0] instanceof Class || boundTypes[0] instanceof CFClass) {
                  if (ModelUtil.isObjectType(boundTypes[0]))
                     idTypes[0] = IdentifierType.BoundObjectName;
                  else
                     idTypes[0] = IdentifierType.BoundTypeName;
               }
               // Special case for the command-line interpreter.  FindType returns the interpreter and we do not
               // have to resolve the reference at eval time.
               else if (ModelUtil.isEnum(boundTypes[0])) {
                  idTypes[0] = IdentifierType.EnumName;
               }
               else {
                  idTypes[0] = IdentifierType.BoundName;
               }
            }
            else {
               idTypes[0] = IdentifierType.UnboundName;
               String ident;
               int pathIx;
               IString lastIdent;
               // Fully qualified object refs are ok if we are in sc
               if (useExtensions || allowClassBinding()) {
                  // For methods, don't try to resolve the method name as a type
                  // to save on false lookups - which are the most expensive
                  int nix = arguments == null ? 0 : 1;
                  int last = sz - nix - 1;
                  lastIdent = idents.get(last);
                  // Don't search for paths that end with 'this' as it's expensive to look for something that does not exist
                  if (lastIdent != null && lastIdent.equals("this") && last > 0)
                     nix++;
                  ident = getIdentifierPathName(sz-nix);
                  pathIx = nix;
               }
               else {
                  int last = sz - 1;
                  lastIdent = idents.get(last);
                  if (lastIdent != null && lastIdent.equals("this")) {
                     ident = getIdentifierPathName(last - 1);
                     pathIx = 2;
                  }
                  else {
                     ident = getQualifiedClassIdentifier();
                     pathIx = 1;
                  }
               }
               if (model != null) {
                  do {
                     Object resolvedType = model.findTypeDeclaration(ident, true);
                     if (resolvedType != null) {
                        int k;
                        for (k = 0; k < sz-offset()-pathIx; k++) {
                           String rootIdent = getIdentifierPathName(k+1);
                           Object rootType = model.findTypeDeclaration(rootIdent, true);
                           if (rootType == null) {
                              idTypes[k] = IdentifierType.PackageName;
                              boundTypes[k] = null;
                           }
                           // Even if we've matched a child type, need to check if that object is a subobject
                           // of another parent, since the parent will need transforming too.
                           else {
                              idTypes[k] = ModelUtil.isObjectType(rootType) ?
                                      IdentifierType.BoundObjectName : IdentifierType.BoundTypeName;
                              boundTypes[k] = rootType;
                           }
                        }
                        idTypes[k] = ModelUtil.isObjectType(resolvedType) ?
                                IdentifierType.BoundObjectName : IdentifierType.BoundTypeName;

                        // Explicitly disallow a.b.c where c is a class unless it is an object
                        if (k == sz-1 && !allowClassBinding() && idTypes[k] == IdentifierType.BoundTypeName) {
                           idTypes[k] = IdentifierType.UnboundName;
                        }
                        else {
                           boundTypes[k] = resolvedType;
                        }
                        nextIx = k + 1;
                        break;
                     }
                     else {
                        if (ident != null && ident.indexOf(".") == -1) {
                           displayRangeError(0, 0, "No type: " + ident + " in ");
                           break;
                        }
                        else {
                           ident = CTypeUtil.getPackageName(ident);
                           pathIx++;
                        }
                     }
                  } while (true);
               }
            }
         }

         String nextName = firstIdentifier;

         for (int i = nextIx; i < sz; i++) {
            Object currentType = getTypeForIdentifier(i-1);

            if (currentType == null)
               idTypes[i] = IdentifierType.Unknown;
            else {

               nextName = idents.get(i).toString();

               // If we are an assignment expression and this is the last identifier it gets marked as a Set if there is
               // a setter.
               boolean setLast = false;
               boolean isMethod = false;

               if (i == sz - 1) {
                  setLast = isAssignment;
                  isMethod = arguments != null;
               }

               List<JavaType> methodTypeArgs = getMethodTypeArguments();
               bindNextIdentifier(this, currentType, nextName, i, idTypes, boundTypes, setLast, isMethod, arguments, methodTypeArgs,
                       bindingDirection, i == 1 && isStaticContext(i-1), inferredType);

               // If we resolved the last entry as an object but its a static member, treat that as a class, not an object
               int last;
               if (i > 0 && idTypes[last = (i - 1)] == IdentifierType.BoundObjectName && isStaticTarget(i)) {
                  idTypes[last] = IdentifierType.BoundTypeName;
               }
            }
         }

         if (isStatic() && !isStaticTarget(0) && boundTypes[0] != null) {
            displayTypeError("Non static definition: '" + firstIdentifier + "' bound to: " + ModelUtil.elementWithTypeToString(boundTypes[0], false) + " referenced from a static context: ");
         }
         if (bindingDirection != null && bindingDirection.doReverse() && arguments != null) {
            int last = sz-1;
            // Bi-directional bindings work differently...
            if (bindingDirection.doForward() && boundTypes[last] != null && (ModelUtil.getAnnotation(boundTypes[last], "BindSettings") == null))
               displayTypeError("Reverse binding defined for method expression where the method: " + idents.get(last).toString() + " does not have BindSettings(reverseMethod) set on it");
         }

         propagateInferredTypes();
      }

      } finally {
         PerfMon.end("resolveIdentifierExpression");
      }
   }

   static Object parameterizeMethod(Expression rootExpr, Object foundMeth, Object currentType, Object inferredType, List<Expression> arguments, List<JavaType> methodTypeArgs) {
      if (foundMeth != null) {
         if (!(foundMeth instanceof ParamTypedMethod) && (ModelUtil.isMethod(foundMeth) || ModelUtil.isConstructor(foundMeth)) && (ModelUtil.hasMethodUnboundTypeParameters(foundMeth) || currentType instanceof ITypeParamContext || methodTypeArgs != null)) {
            Object definedInType = rootExpr.getEnclosingType();
            if (definedInType == null) {
               // This happens for the tag expressions inside of Element objects.  We really just need a layered system and a layer
               // to resolve this reference so no need to find the accurate tag object.
               definedInType = rootExpr.getJavaModel().getModelType();
               if (definedInType == null) // Use getModelType for parsing templates which use a class
                  System.err.println("*** Unable to parameterize reference - no enclosing type");
            }
            ParamTypedMethod ptm = new ParamTypedMethod(rootExpr.getLayeredSystem(), foundMeth, currentType instanceof ITypeParamContext ? (ITypeParamContext) currentType : null, definedInType, arguments, inferredType, methodTypeArgs);
            foundMeth = ptm;
         }
         if (inferredType != null) {
            if (foundMeth instanceof ParamTypedMethod) {
               ((ParamTypedMethod) foundMeth).setInferredType(inferredType);
            }
         }
      }
      return foundMeth;
   }

   static void propagateInferredArgs(Expression rootExpr, Object meth, List<Expression> arguments) {
      Object[] paramTypes = ModelUtil.getActualParameterTypes(meth, true);
      int plen = paramTypes == null ? 0 : paramTypes.length;
      int last = plen - 1;
      int argLen = arguments.size();
      if (plen != argLen) {
         // If the last guy is not a repeating parameter, it can't match
         if (last < 0 || !ModelUtil.isVarArgs(meth) || /* !ModelUtil.isArray(paramTypes[last]) || */ argLen < last) {
            rootExpr.displayTypeError("Mismatching parameter types to method invocation.");
            return;
         }
      }

      if (paramTypes != null) {

         doPropagateInferredArgs(rootExpr, meth, arguments, paramTypes);

         // Need to do this once again after we've propagated all of the argument type
         if (meth instanceof ParamTypedMethod) {
            ParamTypedMethod pmeth = (ParamTypedMethod) meth;
            doPropagateInferredArgs(rootExpr, meth, arguments, paramTypes);

            // And need to rebind the boundJavaTypes in here
            pmeth.rebindArguments(arguments);
         }
      }
   }

   static private void doPropagateInferredArgs(Expression rootExpr, Object meth, List<Expression> arguments, Object[] paramTypes) {
      int i = 0;
      int plen = paramTypes.length;
      int last = plen - 1;
      for (Expression arg : arguments) {
         Object pType = i >= plen ? paramTypes[last] : paramTypes[i];
         Object useType = pType;
         // Handling repeating parameters - it's a varargs method and the last element is an array
         if (i >= last && ModelUtil.isVarArgs(meth)) {
            if (ModelUtil.isArray(pType)) {
               // If we are supplying an array, leave the type as an array
               if (!ModelUtil.isArray(arg.getTypeDeclaration()))
                  useType = ModelUtil.getArrayComponentType(pType);
            }
         }
         if (ModelUtil.isTypeVariable(useType))
            useType = ModelUtil.getTypeParameterDefault(useType);
         boolean changed = arg.setInferredType(useType, rootExpr.isInferredFinal());

         if (meth instanceof ParamTypedMethod && changed) {
            ParamTypedMethod pmeth = (ParamTypedMethod) meth;

            // The subsequent parameter types might need to change.
            // TODO: we should probably also go and update 0 through i-1 as well.
            paramTypes = pmeth.resolveParameterTypes(true, pmeth.boundTypes, i+1, true, false);
         }
         i++;
      }

   }

   private void propagateInferredTypes() {
      List<IString> idents = getAllIdentifiers();
      if (idents == null || this instanceof NewExpression || arguments == null)
         return;

      // Can't propagate our inferred types until this expression's inferred type has been set
      if (inferredType == null && hasInferredType())
         return;

      int len = idents.size();

      Object boundType = boundTypes[len-1];
      if (boundType != null && arguments != null) {
         // This saves some time and is important for the case where boundType is a VariableDefinition - during transform we may
         // return the VariableDefinition until we've created the getX method - but it looks like a method call.
         if (arguments.size() > 0)
            propagateInferredArgs(this, boundType, arguments);
      }
   }

   public void start() {
      if (started || inactive) return;

      // No need to start if we've been replaced - e.g. a Template may copy this guy into the output method.
      if (replacedByStatement != null)
         return;

      super.start();

      resolveTypeReference();
   }

   public void validate() {
      if (validated) return;

      super.validate();

      List<IString> idents = getAllIdentifiers();

      if (idents == null || idTypes == null)
         return;

      // No need to validate if we've been replaced - e.g. a Template may copy this guy into the output method.
      if (replacedByStatement != null)
         return;

      int sz = idents.size();

      for (int i = 0; i < sz; i++) {
         if (idTypes[i] == null)
            continue;

         // Remote methods must be in a binding expression
         if (idTypes[i] == IdentifierType.RemoteMethodInvocation) {
            // When we validate the expressions inside of the tag itself, we won't have set up binding so just defer this error to the other version which is in the tag object.
            if (bindingDirection == null && getEnclosingTag() == null) {
               LayeredSystem sys = getLayeredSystem();
               Layer remoteLayer = boundTypes == null ? null : ModelUtil.getLayerForMember(sys, boundTypes[i]);
               String remoteIdent;
               if (remoteLayer == null)
                  remoteIdent = "<compiled class>";
               else
                  remoteIdent = remoteLayer.getLayeredSystem().getProcessIdent();
               displayTypeError("Method call to remote method - from: " + sys.getProcessIdent() + " to: " + remoteIdent + " - only allowed in binding expression: ");
            }
         }

         String ident = idents.get(i).toString();

         // To determine if a property is bindable, we need to be able to walk backwards in the type tree to
         // look for bindable annotations.  This means we cant' do this in the start process cause things back
         // there are still starting up.
         checkForBindableField(this, ident, idTypes, boundTypes, arguments, bindingDirection, i, null, inferredType);
      }
   }

   public static IdentifierType getIdentifierTypeFromType(Object boundType) {
      IdentifierType ret;
      if (boundType instanceof PropertyAssignment)
         return getIdentifierTypeFromType(((PropertyAssignment)boundType).getAssignedProperty());
      if (ModelUtil.isPropertyGetSet(boundType))
         ret = ModelUtil.isPropertyIs(boundType) ? IdentifierType.IsVariable : IdentifierType.GetVariable;
      else if (ModelUtil.isField(boundType))
         ret = IdentifierType.FieldName;
      else if (ModelUtil.isEnum(boundType))
         ret = IdentifierType.EnumName;
      else if (boundType instanceof ITypeDeclaration) {
         if (((ITypeDeclaration)boundType).getDeclarationType() == DeclarationType.OBJECT)
            ret = IdentifierType.BoundObjectName;
         else
            ret = IdentifierType.BoundTypeName;
      }
      else if (ModelUtil.isMethod(boundType))
         ret = IdentifierType.MethodInvocation;
      else if (boundType instanceof ParamTypedMember)
         return getIdentifierTypeFromType(((ParamTypedMember) boundType).getMemberObject());
      else if (boundType == null)
         return null;
      else {
         System.err.println("*** Unrecognized type of identifier: " + boundType);
         ret = null;
      }
      return ret;
   }

   /** Are we part of a "this" expression - in that case, we do map to a class */
   private boolean allowClassBinding() {
      // If we are the ID expression before a "this" expression.  This can happen either as the prefix for a selector or ++ expression
      return  parentNode instanceof SelectorExpression || parentNode instanceof PostfixUnaryExpression;
   }

   public void visitTypeReferences(CycleInfo info, TypeContext ctx) {
      Object[] inits = null;

      // Create a new type context.  If we traverse to a sub-class while resolving a reference, this context
      // stores a reference back to this original base type.  Subsequent member lookups will make their way back
      // to this root from the sub-class so they get the most specific version during the lookup.
      if (boundTypes != null) {
         for (int i = 0; i < boundTypes.length; i++) {
            // We visit each type in the path name to see if it forms a cycle.  But only leave the reference
            // there if it's the leaf node.  Otherwise, one expression involving prev.x and prev.y will cause a cycle on prev.
            boolean remove = i != boundTypes.length - 1;

            Object valueInitializer;
            // To property detect cycles, we need to visit the most specific definition of our value, not just the type
            if ((valueInitializer = getValueInitializer(i, ctx)) != null && valueInitializer != boundTypes[i]) {
               if (inits == null)
                  inits = new Object[boundTypes.length];
               inits[i] = valueInitializer;
               if (i > 0) {
                  CycleInfo.ThisContext thisCtx = new CycleInfo.ThisContext(getEnclosingType(), boundTypes[i-1]);
                  info.visit(thisCtx, valueInitializer, ctx, remove);
               }
               else {
                  CycleInfo.ThisContext thisCtx = new CycleInfo.ThisContext(ModelUtil.getEnclosingType(boundTypes[0]), null);
                  info.visit(thisCtx, valueInitializer, ctx, remove);
               }
            }
            else {
               if (idTypes == null || idTypes[i] == null)
                  return;
               switch (idTypes[i]) {
                  case GetVariable:
                  case FieldName:
                  case IsVariable:
                     if (i > 0) {
                        CycleInfo.ThisContext thisCtx = new CycleInfo.ThisContext(getEnclosingType(), boundTypes[i-1]);
                        info.visit(thisCtx, boundTypes[i], ctx, remove);
                     }
                     else
                        info.visit(boundTypes[i], ctx, remove);
                     break;
                  case BoundObjectName:
                  case ResolvedObjectName:
                     // Don't visit objects that are either part of static references or method invocations
                     if (i == boundTypes.length - 1 || (!isStaticTarget(i+1) && (arguments == null || i != boundTypes.length - 2))) {
                        if (i > 0) {
                           CycleInfo.ThisContext thisCtx = new CycleInfo.ThisContext(getEnclosingType(), boundTypes[i-1]);
                           info.visit(thisCtx, boundTypes[i], ctx, remove);
                        }
                        else
                           info.visit(boundTypes[i], ctx, remove);
                     }
                     break;
               }
            }
         }
      }

      // First we remove the visit references to any previous types, then visit the arguments.  Once we've resolved
      // the method, we are set.  e.g. children.get(children.size()-1)
      if (boundTypes != null) {
         for (int i = 0; i < boundTypes.length; i++) {
            info.remove(inits == null || inits[i] == null ? boundTypes[i] : inits[i]);
         }
      }

      // For reverse only method bindings, we never actually go to the arguments in the "get" so this is not a cycle
      if (bindingDirection == null || bindingDirection.doForward())
         info.visitList(arguments, ctx);
   }


   private Object getValueInitializer(int ix, TypeContext ctx) {
      ensureValidated();
      resolve();

      if (arguments != null || idTypes == null || idTypes[ix] == null)
         return null;

      List<IString> idents = getAllIdentifiers();

      switch (idTypes[ix]) {
         case GetVariable:
         case FieldName:
         case IsVariable:
            String pname = idents.get(ix).toString();
            if (ix > 0) {
               Object parentType = getTypeForIdentifier(ix-1);
               if (parentType == null)
                  return null;
               return ModelUtil.definesMember(parentType, pname, MemberType.PropertyAssignmentSet, getEnclosingIType(), ctx);
            }
            else {
               return findMember(pname, MemberType.PropertyAssignmentSet, this, getEnclosingIType(), ctx, false);
            }
         case BoundObjectName:
         case ResolvedObjectName:
            String oname = idents.get(ix).toString();
            return findType(oname, getEnclosingIType(), ctx);
      }
      return null;
   }

   public Object getAssignedProperty() {
      if (idTypes == null)
         return null;

      List<IString> idents = getAllIdentifiers();
      int last = idents.size()-1;
      if (idTypes[last] == null)
         return null;
      switch (idTypes[last]) {
         case FieldName:
         case GetVariable:
         case IsVariable:
         case SetVariable:
         case GetSetMethodInvocation:
            return boundTypes[last];
      }
      return null;
   }

   public Object getBoundType(int ix) {
      return boundTypes == null || boundTypes.length <= ix ? null : boundTypes[ix];
   }

   public Object getBoundTypeNoParamType(int ix) {
      Object boundType = getBoundType(ix);
      // First, need to unwrap any parameterization that has been wrapped onto the real type.
      if (boundType instanceof ParamTypedMember)
         boundType = ((ParamTypedMember) boundType).getMemberObject();
      if (boundType instanceof ParamTypedMethod)
         boundType = ((ParamTypedMethod) boundType).method;
      if (boundType instanceof ParamTypeDeclaration)
         boundType = ((ParamTypeDeclaration) boundType).getBaseType();
      if (boundType instanceof JavaSemanticNode) {
         boundType = ((JavaSemanticNode) boundType).refreshNode();
      }
      return boundType;
   }

   /** Returns true for a static field or method at the specific location in the indentifiers list */
   private boolean isStaticTarget(int ix) {
      if (idTypes == null || idTypes[ix] == null)
         return false;

      switch (idTypes[ix]) {
         case FieldName:
         case MethodInvocation:
         case RemoteMethodInvocation:
         case GetVariable:
         case IsVariable:
         case SetVariable:
         case GetSetMethodInvocation:
         case GetObjectMethodInvocation:
            Object type = boundTypes[ix];
            if (type == ArrayTypeDeclaration.LENGTH_FIELD) // We use this special case object since this length method is not available via reflection
               return false;
            if (type instanceof PropertyAssignment)
               type = ((PropertyAssignment) type).getPropertyDefinition();
            return type != null && ModelUtil.hasModifier(type, "static");
         case EnumName:
            return true;
         case BoundName:
            return true;
         case BoundObjectName:
         case ResolvedObjectName:
            if (boundTypes[ix] != null) {
               if (ModelUtil.hasModifier(boundTypes[ix], "static"))
                  return true;
               return boundTypes[ix] instanceof TypeDeclaration && ((TypeDeclaration) boundTypes[ix]).isStaticObject();
            }
            break;
      }
      return !isThisExpression();
   }

   /** Returns true for class definitions at the specific location */
   private boolean isStaticContext(int ix) {
      if (idTypes == null || idTypes[ix] == null)
         return false;

      switch (idTypes[ix]) {
         case FieldName:
         case MethodInvocation:
         case RemoteMethodInvocation:
         case GetVariable:
         case IsVariable:
         case SetVariable:
         case GetSetMethodInvocation:
         case GetObjectMethodInvocation:
         case BoundName:
         case EnumName: // Since you don't tag enum methods as static, it should not generate static errors
         case ResolvedObjectName:
            return false;
         case BoundTypeName:
            return true;
      }
      return false;
   }

   static private boolean isGetOrIs(IdentifierType type) {
      return type == IdentifierType.GetVariable || type == IdentifierType.IsVariable;
   }

   /**
    * Checks if the given property is bindable already.  If not, it will add the property to the list of those to inject bindability to during class transformation.  If "makeBindable" is true
    * this is called from the makeBindable method which has already deteremined we need to make it bindable.  If not, we look for any bindable annotation in the chain and abort the binding.
    */
   static private void checkForBindableCompiledField(String propertyName, Object boundType, Object annotType, Object referenceType, Statement fromExpr, boolean referenceOnly, boolean checkAnnotations) {
      TypeDeclaration referenceTD;
      LayeredSystem sys;

      if (!(referenceType instanceof BodyTypeDeclaration)) {
         if (!referenceOnly && ModelUtil.hasSetMethod(boundType) && ModelUtil.getPropertyAnnotation(annotType, "sc.bind.Bindable") == null) {
            fromExpr.displayWarning("Binding to unbindable compiled type: " + referenceType + " for property: " + propertyName + " in: ");
            ModelUtil.getPropertyAnnotation(annotType, "sc.bind.Bindable");
         }

         if (!(sys = fromExpr.getLayeredSystem()).useRuntimeReflection) {
            sys.buildInfo.addExternalDynProp(referenceType, propertyName, fromExpr.getJavaModel(), referenceOnly);
         }
      }
      else {
         referenceTD = (TypeDeclaration) referenceType;

         if (ModelUtil.hasSetMethod(boundType) && ModelUtil.getPropertyAnnotation(annotType, "sc.bind.Bindable") == null) {
            Object nextType;
            TypeDeclaration nextTD;
            boolean addBindable = true;

            // We'll already be making the property in the reference TD bindable which should be enough
            Object member = referenceTD.definesMember(propertyName, MemberType.PropertyAnySet, null, null);
            if (checkAnnotations && member != null && ModelUtil.getAnnotation(member, "sc.bind.Bindable") != null)
                return;

            Layer nextTDLayer = null;
            // Find the most specific class/object type which defines this property skipping annotation layers which are
            // not generated
            while ((nextType = referenceTD.getDerivedTypeDeclaration()) instanceof TypeDeclaration &&
                    (nextTD = (TypeDeclaration) nextType).isClassOrObjectType() && ((nextTDLayer = nextTD.getLayer()) == null || !nextTDLayer.annotationLayer)) {

               member = nextTD.definesMember(propertyName, MemberType.PropertyAnySet, null, null);
               // Make sure this next type actually defines the property before we try to make it bindable
               if (member == null)
                  break;

               // If we are derived from a type which implements the bindable contract, we do not need to make this one bindable as well.
               if (checkAnnotations && (ModelUtil.getAnnotation(member, "sc.bind.Bindable") != null || ModelUtil.isConstant(member))) {
                  addBindable = false;
                  break;
               }
               referenceTD = nextTD;
            }

            if (!(nextType instanceof TypeDeclaration)) {
               if (!(sys = fromExpr.getLayeredSystem()).useRuntimeReflection) {
                  sys.buildInfo.addExternalDynProp(nextType, propertyName, fromExpr.getJavaModel(), referenceOnly);
               }
            }

            if (member != null && (ModelUtil.isConstant(member) || (boundType != member && ModelUtil.isConstant(boundType))))
               addBindable = false;

            // But then need to resolve it back to the most specific modified type
            referenceTD = (TypeDeclaration) referenceTD.resolve(true);

            if (addBindable) {
               referenceTD.addPropertyToMakeBindable(propertyName, boundType, fromExpr.getJavaModel(), referenceOnly);
            }
         }
         // TODO: else read-only property?
      }
      // Get the set method and print a warning if it is not bindable
   }

   /**
    * Utility method for IdentifierExpr and SelectExpr to apply the data binding contract to a given property of the
    * referenced object.   When referenceOnly = true, this is a getX reference only - not requiring that the referred
    * property be made bindable.  This method both ensures the property is registered with it's parent type and
    * adds a reference to that type in ReverseDependencies - reflecting the depenpendcy from the type which needs
    * this property bindable.  When checkAnnotations is true, referenceOnly starts out false but then turns to 'true' if
    * this property is marked as @Bindable so that we inject it during the code-gen phase (i.e. not manual bindable).
    */
   public static void makeBindable(Statement expr, String propertyName, IdentifierType idType, Object boundType,
                                   Object typeForIdentifier, Object referenceType, boolean referenceOnly, boolean checkAnnotations) {

      // Once we've been transformed, no need to add these dependencies
      if (boundType == null || typeForIdentifier == null || expr.getTransformed())
         return;

      // A lot of times the boundType is in some super-type of an annotated type in the system so we need
      // to get the most specific reference before we start looking for annotations.
      // No longer necessary because we do the resolve for assignments anyway?
      //Object annotType = ModelUtil.resolveAnnotationReference(expr.getJavaModel(), boundType);
      Object annotType = boundType;
      if (boundType instanceof PropertyAssignment)
         boundType = ((PropertyAssignment) boundType).getAssignedProperty();
      if (idType == IdentifierType.FieldName || idType == IdentifierType.GetSetMethodInvocation) {
         if (!ModelUtil.isConstant(annotType)) {
            if (boundType instanceof ParamTypedMember)
               boundType = ((ParamTypedMember) boundType).getMemberObject();
            if (boundType instanceof VariableDefinition) {
               VariableDefinition varDef = (VariableDefinition) boundType;
               varDef.makeBindable(referenceOnly);
               JavaModel fromModel = expr.getJavaModel();
               if (fromModel != null) {
                  if (referenceType instanceof TypeDeclaration) {
                     TypeDeclaration toType = varDef.getEnclosingType();
                     JavaModel toModel = toType.getJavaModel();
                     if (toModel != null)
                        toModel.addBindDependency(toType, propertyName, fromModel.getModelTypeDeclaration(), referenceOnly);
                  }
                  else if (referenceType != null)
                     System.err.println("*** Compiled type used to inject binding dependency?");
               }
            }
            else if (boundType instanceof Field) {
               // TODO: need to add logic to make a compiled field bindable in a subclass, more like below
               System.out.println("*** Warning; binding expression involving non-bindable field: " +
                       boundType + " in expression: " + expr.toDefinitionString());
            }
            else if (boundType instanceof IBeanMapper)
               checkForBindableCompiledField(propertyName, boundType, annotType, referenceType, expr, referenceOnly, checkAnnotations);
         }
      }
      else if (isGetOrIs(idType) && ModelUtil.getBindableAnnotation(annotType) == null && !ModelUtil.isConstant(annotType)) {
         TypeDeclaration referenceTD;

         // If we have a method, it means we can inject bindability by renaming the old method
         if (boundType instanceof IMethodDefinition) {
            IMethodDefinition getMethod = ((IMethodDefinition) boundType);
            Object encType = getMethod.getDeclaringType();
            if (encType instanceof TypeDeclaration) {
               String getName = ModelUtil.getMethodName(getMethod);
               referenceTD = (TypeDeclaration) encType;
               String setName = "set" + (getName.startsWith("is") ? getName.substring(2) : getName.substring(3));
               Object setMethod = referenceTD.definesMethod(setName, Collections.singletonList(typeForIdentifier), null, null, referenceTD.isTransformedType(), false, null, null);
               if (setMethod != null) {
                  // Do dynamic access only if the property is marked as manually bindable.
                  referenceTD.addPropertyToMakeBindable(propertyName, boundType, expr.getJavaModel(), ModelUtil.isManualBindable(setMethod));
               }
            }
            else if (encType instanceof CFClass) {
               checkForBindableCompiledField(propertyName, boundType, annotType, referenceType, expr, referenceOnly, checkAnnotations);
            }
            else
               System.err.println("*** Can't make non-class types bindable " + encType + " for: " + propertyName);

            // TODO: else read-only property?  Or in this case, do we need to check the MemberOwner again?
         }
         else if (boundType instanceof IBeanMapper) {
            checkForBindableCompiledField(propertyName, boundType, annotType, referenceType, expr, referenceOnly, checkAnnotations);
         }
      }
      else if (idType == IdentifierExpression.IdentifierType.MethodInvocation) {
         checkForDynMethod(boundType, referenceType, (Expression) expr);
      }
      else if (idType == IdentifierExpression.IdentifierType.BoundObjectName) {
         if (boundType instanceof BodyTypeDeclaration)
            ModelUtil.markNeedsDynAccess(boundType);
         // We can have an object which is in compiled form, not anything we need to do
      }
   }

   /**
    * This method looks for fields or get/set properties which need to be made bindable for this expression
    * to succeed.  We'll inject bindability by first finding the parent type of this expression, then walk from
    * that type to where the field/get-set are defined and add the proper get set as needed.
    */
   static private void checkForBindableField(Expression expr, String propertyName, IdentifierType[] idTypes, Object[] boundTypes,
                                             List<Expression> arguments, BindingDirection bindingDirection, int ix, Object rootType, Object inferredType) {

      if (bindingDirection != null) {
         Object referenceType;
         if (ix == 0)
            referenceType = expr.findMemberOwner(propertyName, MemberType.PropertyGetSet);
         else
            referenceType = getTypeForIdentifier(idTypes, boundTypes, arguments, ix-1, expr.getJavaModel(), rootType, inferredType, expr.getEnclosingType());

         if (expr.getJavaModel() == null) {
            // Happens for partial expression parsing and completion constructs in the editor
            return;
         }
         // Any binding requires dynamic access to the member but if it's a forward binding, its a real binding, not referenceOnly=true
         makeBindable(expr, propertyName, idTypes[ix], boundTypes[ix], getTypeForIdentifier(idTypes, boundTypes, arguments, ix, expr.getJavaModel(), null, inferredType, expr.getEnclosingType()),
                      referenceType, !bindingDirection.doForward(), true);
      }
   }

   /**
    * We have an identifier expression which points to a method invocation.  If this is a binding, that requires
    * marking that method as needing dynamic invocation if we are not able to use runtime reflection.
    */
   static void checkForDynMethod(Object methodType, Object referenceTypeObj, Expression expr) {
      if (expr.bindingDirection != null) {

         TypeDeclaration referenceTD;
         LayeredSystem sys = expr.getLayeredSystem();
         if (referenceTypeObj instanceof TypeDeclaration) {
            if (!(referenceTD = (TypeDeclaration) referenceTypeObj).getUseRuntimeReflection()) {
               if (methodType instanceof ParamTypedMethod)
                  methodType = ((ParamTypedMethod) methodType).method;

               if (referenceTD.useExternalDynType)
                  sys.buildInfo.addExternalDynMethod(referenceTypeObj, methodType, expr.getJavaModel());
               else if (methodType instanceof AbstractMethodDefinition)
                  ((AbstractMethodDefinition) methodType).needsDynInvoke = true;
               else {
                  referenceTD.addDynInvokeMethod(methodType, expr.getJavaModel());
               }
            }
            // else - no need to do runtime reflection for this type
         }
         else if (sys != null && !sys.useRuntimeReflection) {
            sys.buildInfo.addExternalDynMethod(referenceTypeObj, methodType, expr.getJavaModel());
         }
      }
   }

   public boolean isSimpleReference() {
      List<IString> idents = getAllIdentifiers();
      return idents != null && idents.size() == 1 && arguments == null;
   }

   /** Returns the type which is referring to the value of this expression */
   public Object getReferenceType() {
      List<IString> idents = getAllIdentifiers();
      int last = idents.size()-2;
      if (last == -1)
         return findMemberOwner(idents.get(0).toString(), MemberType.PropertyGetSet);
      else
         return getTypeForIdentifier(idTypes, boundTypes, arguments, last, getJavaModel(), null, inferredType, getEnclosingType());
   }

   /** Returns the type which is referring to the value of this expression */
   public Object getParentReferenceType() {
      List<IString> idents = getAllIdentifiers();
      int sz = idents.size();
      if (idents == null || sz == 1)
         return null;
      int last = sz - 2;
      return getTypeForIdentifier(idTypes, boundTypes, arguments, last, getJavaModel(), null, inferredType, getEnclosingType());
   }

   /** For "a.b.c", returns an expression which evaluates "a.b" */
   public Expression getParentReferenceTypeExpression() {
      int sz;
      List<IString> idents = getAllIdentifiers();
      if (idents == null || (sz = idents.size()) == 1) {
         if (idTypes == null)
            return IdentifierExpression.create("this");
         // The srcObj parameter
         Object srcType;
         Expression srcObj;
         TypeDeclaration type = getEnclosingType();
         switch (idTypes[0]) {
            case VariableName:
               throw new UnsupportedOperationException();
            case IsVariable:
            case GetVariable:
            case SetVariable:
            case FieldName:
            case GetSetMethodInvocation:
            case BoundObjectName:
            case ResolvedObjectName:
               Object srcField = boundTypes[0];
               if (srcField instanceof VariableDefinition) {
                  srcField = ((VariableDefinition) srcField).getDefinition();
               }
               if (srcField instanceof IVariable || srcField instanceof FieldDefinition || srcField instanceof ITypeDeclaration)
                  srcType = ModelUtil.getEnclosingType(srcField);
               else
                  srcType = RTypeUtil.getPropertyDeclaringClass(srcField);
               if (ModelUtil.hasModifier(srcField, "static")) {
                  srcObj = ClassValueExpression.create(ModelUtil.getTypeName(srcType));
               }
               else {
                  // TODO: need to get the base class relative to "type" from which we lookup srcType
                  if (ModelUtil.isAssignableFrom(srcType, type)) {
                     srcObj = IdentifierExpression.create("this");
                     srcType = type;
                  }
                  // Must be an enclosing type for us to refer to a field with a single name so just need to qualify which "this"
                  else {
                     TypeDeclaration pType = type.getEnclosingType();
                     while (pType != null && !ModelUtil.isAssignableFrom(srcType, pType)) {
                        pType = pType.getEnclosingType();
                     }
                     if (pType != null)
                        srcType = pType;
                     srcObj = IdentifierExpression.create(ModelUtil.getTypeName(srcType), "this");
                  }
               }
               return srcObj;
         }
         return IdentifierExpression.create("this");
      }

      IString[] ids = idents.subList(0, sz-1).toArray(new IString[sz-1]);
      return create(ids);
   }

   public String getReferencePropertyName() {
      List<IString> idents = getAllIdentifiers();
      if (idents == null || arguments != null)
         return null;

      return idents.get(idents.size()-1).toString();
   }

   static Object checkRemoteMethod(Expression expr, Object currentType, String methodName, int ix, IdentifierType[] idTypes, Object[] boundTypes,
                                   SemanticNodeList<Expression> arguments, boolean isStatic, Object inferredType) {

      boolean findType = false;
      ITypeDeclaration enclType = expr.getEnclosingIType();
      if (enclType == null)
         return null;
      if (currentType == null) {
         currentType = enclType;
         findType = true;
      }

      LayeredSystem sys = expr.getLayeredSystem();

      JavaModel exprModel = enclType.getJavaModel();
      // For those expressions defined inside of the tag hierarchy... do we need to start these at all?
      if (exprModel == null && enclType instanceof Template)
         exprModel = (Template) enclType;

      // Once we've finished processing, during the transform don't bother checking for remote methods.  Sometimes
      // we find them due to the problem that we don't transform getX/setX until after we've transformed the reference.
      // Remote methods should be resolved before the transform anyway.
      if (expr.isProcessed() || exprModel == null || exprModel.inTransform || exprModel.isLayerModel || exprModel.disableTypeErrors)
         return null;

      List<LayeredSystem> syncSystems = sys.getSyncSystems();
      if (syncSystems == null)
         return null;

      String typeName = ModelUtil.getTypeName(currentType);
      for (LayeredSystem syncSys:syncSystems) {
         if (!syncSys.enableRemoteMethods)
            continue;
         Layer syncRefLayer = syncSys.getPeerLayerFromRemote(exprModel.getLayer());
         Object peerType = syncSys.getTypeDeclaration(typeName, false, syncRefLayer, false);
         Object enclPeerType = enclType == peerType ? peerType : syncSys.getTypeDeclaration(enclType.getFullTypeName(), false, syncRefLayer, false);
         Object meth;
         ModelUtil.ensureStarted(peerType, false);
         if (enclPeerType != peerType)
            ModelUtil.ensureStarted(enclPeerType, false);
         if (findType && peerType instanceof BodyTypeDeclaration) {
            meth = ((BodyTypeDeclaration) peerType).findMethod(methodName, arguments, expr, peerType, isStatic, inferredType);
         }
         else
            meth = ModelUtil.definesMethod(peerType, methodName, arguments, null, enclPeerType, false, isStatic, inferredType, expr.getMethodTypeArguments(), expr.getLayeredSystem());
         if (meth != null) {
            boundTypes[ix] = meth;
            idTypes[ix] = IdentifierType.RemoteMethodInvocation;
            return meth;
         }
      }
      return null;
   }

   static void bindNextIdentifier(Expression expr, Object currentType, String nextName, int i, IdentifierType[] idTypes, Object[] boundTypes,
                                  boolean setLast, boolean isMethod, SemanticNodeList<Expression> arguments, List<JavaType> methodTypeArgs,
                                  BindingDirection bindingDirection, boolean isStatic, Object inferredType) {

      JavaModel model = expr.getJavaModel();

      if (currentType instanceof PropertyAssignment)
         currentType = ((PropertyAssignment) currentType).getAssignedProperty();

      // Bind this to the most specific type we can at this point
      if (ModelUtil.isTypeVariable(currentType))
         currentType = ModelUtil.getTypeParameterDefault(currentType);

      // Do not use isParameterizedType here - we want ParamTypeDeclaration to be treated as an ITypeDeclaration
      if (currentType instanceof ParameterizedType)
         currentType = ModelUtil.getParamTypeBaseType(currentType);

      ITypeDeclaration enclosingType = expr.getEnclosingIType();

      if (currentType instanceof ITypeDeclaration) {
         ITypeDeclaration currentTypeDecl = (ITypeDeclaration) currentType;

         if (isMethod) {
            Object methVar = currentTypeDecl.definesMethod(nextName, arguments, null, enclosingType, enclosingType != null && enclosingType.isTransformedType(), isStatic, inferredType, methodTypeArgs);
            if (methVar != null) {
               // getX() can return a ClassDeclaration in some cases
               if (methVar instanceof ITypeDeclaration) {
                  idTypes[i] = IdentifierType.BoundObjectName;
               }
               else {
                  methVar = parameterizeMethod(expr, methVar, currentTypeDecl, inferredType, arguments, methodTypeArgs);
                  idTypes[i] = IdentifierType.MethodInvocation;
               }
               boundTypes[i] = methVar;
               // Also need to map the super type here, just like below when we can't resolve the method on the type.
               if (i > 0 && idTypes[i-1] == IdentifierType.SuperExpression && enclosingType != null)
                  boundTypes[i-1] = ModelUtil.getEnclosingType(methVar);
            }
            else {
               // For a super, when it's a modify that also has an extends, we need to try the extends if the modify failed
               if (i > 0 && idTypes[i-1] == IdentifierType.SuperExpression && enclosingType != null) {
                  Object newCurrentType = ModelUtil.getExtendsClass(enclosingType);
                  if (newCurrentType != null && newCurrentType != currentType) {
                     methVar = ModelUtil.definesMethod(newCurrentType, nextName, arguments, null, enclosingType, enclosingType != null && enclosingType.isTransformedType(), isStatic, inferredType, methodTypeArgs, enclosingType.getLayeredSystem());
                     if (methVar != null) {
                        idTypes[i] = methVar instanceof ITypeDeclaration ? IdentifierType.BoundObjectName : IdentifierType.MethodInvocation;
                        boundTypes[i] = methVar;
                        // Now the "super" really refers to this type.  This is important to get right for JS conversion, so it points to the right type.
                        boundTypes[i-1] = ModelUtil.getEnclosingType(methVar);
                     }
                  }
               }
               if (methVar == null) {
                  methVar = checkRemoteMethod(expr, currentType, nextName, i, idTypes, boundTypes, arguments, isStatic, inferredType);
               }
               if (methVar == null) {
                  idTypes[i] = IdentifierType.UnboundMethodName;
                  if (model != null && !model.disableTypeErrors && expr.isInferredSet() && expr.isInferredFinal()) {
                     String otherMessage = getOtherMethodsMessage(currentType, nextName);
                     expr.displayRangeError(i, i, "No method: ", nextName, ModelUtil.argumentsToString(arguments), " in type: ", ModelUtil.getTypeName(currentTypeDecl),otherMessage == null ? "" : otherMessage.toString(),  " for ");
                     methVar = currentTypeDecl.definesMethod(nextName, arguments, null, enclosingType, enclosingType != null && enclosingType.isTransformedType(), isStatic, inferredType, methodTypeArgs);
                  }
               }
            }
         }
         else {
            // The parser won't generate these where 'this' is not in the first position but we do
            // sometimes create them programatically since it is easier than doing the selector expression
            if (nextName.equals("this"))
               idTypes[i] = IdentifierType.ThisExpression;
            else if (nextName.equals("super"))
               idTypes[i] = IdentifierType.SuperExpression;
            else {
               Object var = currentTypeDecl.definesMember(nextName, MemberType.VariableSet, null, null);
               if (var != null) {
                  idTypes[i] = IdentifierType.VariableName;
                  boundTypes[i] = var;
               }
               else {
                  EnumSet<MemberType> toFind = model != null && model.enableExtensions() ?
                          (setLast ? MemberType.PropertySetSet : MemberType.PropertyAssignmentSet) :
                          MemberType.FieldEnumSet;
                  Object methPropVar = currentTypeDecl.definesMember(nextName, toFind, null, null);
                  if (methPropVar != null) {
                     boundTypes[i] = methPropVar;
                     // If we are in an assignment, only look for the setX method.  If reading the value, look for a getX method
                     boolean isProperty = setLast ? ModelUtil.hasSetMethod(methPropVar) : ModelUtil.hasGetMethod(methPropVar);
                     if (model != null && model.enableExtensions() && isProperty)
                        idTypes[i] = setLast ? IdentifierType.SetVariable :
                                ModelUtil.isPropertyIs(methPropVar) ? IdentifierType.IsVariable :
                                                                      IdentifierType.GetVariable;
                     else if (ModelUtil.isEnum(methPropVar)) {
                        idTypes[i] = IdentifierType.EnumName;
                     }
                     else
                        idTypes[i] = IdentifierType.FieldName;
                  }
                  else {
                     if ((boundTypes[i] = currentTypeDecl.getInnerType(nextName, null)) != null) {
                        if (ModelUtil.getDeclarationType(boundTypes[i]) == DeclarationType.OBJECT)
                           idTypes[i] = IdentifierType.BoundObjectName;
                        else
                           idTypes[i] = IdentifierType.BoundTypeName;
                     }
                     else {
                        idTypes[i] = IdentifierType.UnboundName;
                        String message = "No nested property: ";
                        // If we are missing the setX method, provide a more accurate message
                        if (setLast && model != null && model.enableExtensions() && currentTypeDecl.definesMember(nextName, MemberType.PropertyAssignmentSet, null, null) != null)
                           message = "Unable to assign read-only property: ";
                        expr.displayRangeError(i, i, message, nextName, " in type: ", ModelUtil.getTypeName(currentTypeDecl), " for ");
                     }
                  }
               }
            }
         }
      }
      else if (currentType instanceof Class) {
         Class currentClass = (Class) currentType;
         if (isMethod) {
            LayeredSystem sys = expr.getLayeredSystem();
            Object methObj = ModelUtil.definesMethod(currentClass, nextName, arguments, null, enclosingType, enclosingType != null && enclosingType.isTransformedType(), isStatic, inferredType, methodTypeArgs, sys);
            if (methObj != null) {
               Object meth = parameterizeMethod(expr, methObj, currentClass, inferredType, arguments, methodTypeArgs);
               idTypes[i] = IdentifierType.MethodInvocation;
               boundTypes[i] = meth;
            }
            else {
               Object remoteMeth = checkRemoteMethod(expr, currentType, nextName, i, idTypes, boundTypes, arguments, isStatic, inferredType);
               if (methObj == null && remoteMeth == null) {
                  idTypes[i] = IdentifierType.UnboundMethodName;
                  if (model != null && !model.disableTypeErrors && expr.isInferredFinal() && expr.isInferredSet()) {
                     String otherMethods = getOtherMethodsMessage(currentClass, nextName);
                     expr.displayRangeError(i, i, "No method: ", nextName, ModelUtil.argumentsToString(arguments), " in type: ", ModelUtil.getTypeName(currentClass), otherMethods, " for ");
                     methObj = ModelUtil.definesMethod(currentClass, nextName, arguments, null, enclosingType, enclosingType != null && enclosingType.isTransformedType(), isStatic, inferredType, methodTypeArgs, sys); // TODO: remove - for debugging only
                  }
               }
            }
         }
         else {
            // The parser won't generate these where 'this' is not in the first position but we do
            // sometimes create them programatically since it is easier than doing the selector expression
            if (nextName.equals("this"))
               idTypes[i] = IdentifierType.ThisExpression;
            else {
               IBeanMapper mapper = TypeUtil.getPropertyMapping(currentClass, nextName, null, null);
               boolean found = false;
               if (mapper != null) {
                  boundTypes[i] = mapper;
                  if (model != null && model.enableExtensions()) {
                     if (setLast && mapper.hasSetterMethod())
                        idTypes[i] = IdentifierType.SetVariable;
                     else if (!setLast && mapper.hasAccessorMethod()) {
                        if (mapper.isPropertyIs())
                           idTypes[i] = IdentifierType.IsVariable;
                        else
                           idTypes[i] = IdentifierType.GetVariable;
                     }
                     else {
                        idTypes[i] = IdentifierType.FieldName;
                     }
                     found = true;
                  }
                  else if (mapper.getField() != null) {
                     idTypes[i] = IdentifierType.FieldName;
                     found = true;
                  }
                  else {
                     found = false;
                  }

                  // At runtime we can end up with the field that is marked as an enum
                  if (found && idTypes[i] == IdentifierType.FieldName && ModelUtil.isEnum(mapper))
                     idTypes[i] = IdentifierType.EnumName;
               }
               // If we found a mapper but it did not map, it could be an inner class reference we need to resolve
               if (!found) {
                  if ((boundTypes[i] = RTypeUtil.getInnerClass(currentClass, nextName)) != null)
                     idTypes[i] = IdentifierType.BoundTypeName;
                  else if ((boundTypes[i] = ModelUtil.getEnum(currentClass, nextName)) != null)
                     idTypes[i] = IdentifierType.EnumName;
                  else {
                     idTypes[i] = IdentifierType.UnboundName;
                     expr.displayRangeError(i, i, "No property: ", nextName, " in type: ", ModelUtil.getTypeName(currentClass), " for ");
                  }
               }
            }
         }
      }
      else if (currentType != null)
         System.err.println("*** Unrecognized type in bindNextIdentifier: " + currentType);
      if (isStatic && boundTypes[i] != null && idTypes[i] != IdentifierType.BoundTypeName && idTypes[i] != IdentifierType.EnumName && !ModelUtil.hasModifier(getMemberForIdentifier(expr, i, idTypes, boundTypes, model), "static")) {
         expr.displayRangeError(i, i, "Non static ", idTypes[i] == IdentifierType.MethodInvocation ? "method " : "property ", nextName, " accessed from a static context in : ", nextName, " for ");
         ModelUtil.hasModifier(getMemberForIdentifier(expr, i, idTypes, boundTypes, model), "static");
      }
   }

   /**
    * Once language difference we need to account for is when the getX method differs from the field.  In Java, we need to use the
    * field, not the property type.
    */
   static Object getMemberForIdentifier(Expression expr, int ix, IdentifierType[] idTypes, Object[] boundTypes, JavaModel model) {
      IdentifierType idType = idTypes[ix];
      Object boundType = boundTypes[ix];
      if (idType == IdentifierType.FieldName && model != null && !model.enableExtensions()) {
         if (boundType instanceof IBeanMapper)
            return ((IBeanMapper) boundType).getField();
      }
      return boundType;
   }

   private static String getOtherConstructorsMessage(Object currentType, String prefix) {
      Object[] otherMethods = ModelUtil.getConstructors(currentType, null);
      StringBuilder otherMessage = null;
      if (otherMethods != null && otherMethods.length > 0) {
         otherMessage = new StringBuilder();
         otherMessage.append(prefix);
         for (Object otherMeth:otherMethods) {
            otherMessage.append("      ");
            otherMessage.append(ModelUtil.elementToString(otherMeth, false));
            otherMessage.append("\n");
         }
      }
      return otherMessage == null ? "" : otherMessage.toString();
   }

   private static String getOtherMethodsMessage(Object currentType, String nextName) {
      Object[] otherMethods = ModelUtil.getMethods(currentType, nextName, null);
      StringBuilder otherMessage = null;
      if (otherMethods != null && otherMethods.length > 0) {
         otherMessage = new StringBuilder();
         otherMessage.append("\n   Did you mean:\n");
         for (Object otherMeth:otherMethods) {
            otherMessage.append("      ");
            otherMessage.append(ModelUtil.elementToString(otherMeth, false));
            otherMessage.append("\n");
         }
      }
      return otherMessage == null ? "" : otherMessage.toString();
   }

   protected int offset() {
      return 1;
   }

   public String getIdentifierPathName(int ix) {
      StringBuffer sb = new StringBuffer();
      List<IString> idents = getAllIdentifiers();
      for (int i = 0; i < ix; i++) {
         if (i != 0)
            sb.append(".");
         sb.append(idents.get(i));
      }
      return sb.toString();
   }

   public String getQualifiedClassIdentifier() {
      List<IString> idents = getAllIdentifiers();
      return getIdentifierPathName(idents.size() - offset());
   }

   private void resolve() {
      if (boundTypes == null)
         return;
      for (int i = 0; i < boundTypes.length; i++) { // Do not skip to the most specific type for super expressions
         Object newType = ModelUtil.resolve(boundTypes[i], idTypes[i] != IdentifierType.SuperExpression);
         boundTypes[i] = newType;
      }
   }

   private boolean isDynGetMethod(int i) {
      if (idTypes[i] == IdentifierType.GetVariable && boundTypes[i] instanceof MethodDefinition && !disableGetVariable(i))
         return true;

      return false;
   }

   private boolean isDynGetVariableDef(int i) {
      // We have a VariableDefinition which we have not yet converted - but it's still invoked as a get
      if (idTypes[i] == IdentifierType.GetSetMethodInvocation && boundTypes[i] instanceof VariableDefinition && !disableGetVariable(i))
         return true;
      return false;
   }

   private Object getPropertyValueForType(Object thisObj, int i) {
      Object value;
      List<IString> idents = getAllIdentifiers();
      String varName = idents.get(i).toString();
      Object boundType = boundTypes[i];
      if (boundType != ArrayTypeDeclaration.LENGTH_FIELD && isStaticTarget(i)) {
         // This will not look at extends types so can't use the current static type here (i.e. thisObj)
         value = ModelUtil.getStaticPropertyValue(ModelUtil.getEnclosingType(boundType), varName);
      }
      else if (thisObj instanceof IDynObject) {
         if (isDynGetMethod(i)) {
            value = ((MethodDefinition) boundType).callVirtual(thisObj);
         }
         else if (isDynGetVariableDef(i)) {
            value = ((IDynObject) thisObj).getProperty(((VariableDefinition) boundType).variableName);
         }
         else
            value = ((IDynObject) thisObj).getProperty(varName);
      }
      else
         value = TypeUtil.getPropertyValue(checkNullThis(thisObj, varName), varName);
      return value;
   }

   private Object resolveCustomObj(JavaModel jmodel, int ix) {
      Object boundType = boundTypes[ix];
      if (boundType instanceof BodyTypeDeclaration && !((BodyTypeDeclaration) boundType).isStarted()) {
         ModelUtil.ensureStarted(boundType, true);
      }
      Object customObj = null;
      if (jmodel != null && jmodel.customResolver != null) {
         return jmodel.customResolver.resolveObject(jmodel.getPackagePrefix(), getIdentifierPathName(ix+1), true, true);
      }
      return null;
   }

   public Object eval(Class expectedType, ExecutionContext ctx) {
      String methodName = null;
      boolean superMethod = false;
      boolean isType = false;

      ensureValidated();
      resolve();

      if (bindingDirection != null)
         return initBinding(expectedType, ctx);

      try {
         Object value = null;
         int nextIx = 1;

         List<IString> idents = getAllIdentifiers();
         int sz = idents.size();

         JavaModel jmodel = getJavaModel();
         switch (idTypes[0]) {
            case PackageName:
               while (idTypes[nextIx] == IdentifierType.PackageName)
                  nextIx++;
               isType = true;
               if (idTypes[nextIx] == IdentifierType.BoundTypeName) {
                  value = resolveCustomObj(jmodel, nextIx);
                  if (value == null) {
                     value = ModelUtil.getRuntimeType(boundTypes[nextIx]);
                     isType = true;
                  }
                  else
                     isType = false;
                  nextIx++;
               }
               else if (idTypes[nextIx] == IdentifierType.ResolvedObjectName) {
                  jmodel = getJavaModel();
                  value = jmodel.customResolver.resolveObject(jmodel.getPackagePrefix(), getIdentifierPathName(sz), true, true);
                  isType = false;
               }
               else
                  value = boundTypes[nextIx++];
               break;

            case ThisExpression:
               value = ctx.getCurrentObject();
               isType = false;
               break;

            case SuperExpression:
               Object type = boundTypes[0];

               // If this is super(x) for a constructor the type is the enclosing type of the constructor
               if (sz == 1 && ModelUtil.isConstructor(type))
                  type = ModelUtil.getEnclosingType(type);

               BodyTypeDeclaration pendingConstructor;

               if (arguments != null) {
                  if (sz == 1 && (pendingConstructor = ctx.getPendingConstructor()) != null) {
                     // Clear this out before invoking.
                     //ctx.setPendingConstructor(null);

                     //ctx.pendingConstructorInProgress = true;
                     pendingConstructor.constructInstFromArgs(arguments, ctx, true);
                     //ctx.pendingConstructorInProgress = false;
                     return null;
                  }
                  else if (sz == 2) {
                     TypeDeclaration td = getEnclosingType();
                     // Change the name only when the super method is a compiled method.  This should match the logic in DynMethod.getNeedsSuper()...
                     if (td != null && td.isDynamicStub(false) && (!(boundTypes[1] instanceof AbstractMethodDefinition) || !(((AbstractMethodDefinition) boundTypes[1]).isDynMethod()))) {
                        methodName = "_super_" + idents.get(1).toString();
                        superMethod = true;
                     }
                     value = ctx.getCurrentObject();
                  }
                  else {
                     // When the super refers to the same type - i.e. it's a constructor modifying the base layer's constructor, we need to invoke the base layer's constructor here.
                     BodyTypeDeclaration enclType = getEnclosingType();
                     if (ModelUtil.sameTypes(enclType, type)) {
                        Object baseConstr = ModelUtil.declaresConstructor(getLayeredSystem(), type, arguments, null);
                        if (baseConstr != null)
                           ModelUtil.invokeMethod(ctx.getCurrentObject(), baseConstr, arguments, null, ctx, false, false, null);
                     }
                     // when the super refers to the base class which is a compiled type, we'll have already executed the super in the DynamicStub.  The statement in the ConstructorDefinition can be ignored since it was run first thing in the stub.
                     return null;
                  }

                  isType = false;
               }
               else {
                  if (type == null)
                     value = null;
                  else
                     value = ctx.resolveName(ModelUtil.getTypeName(type));
                  isType = true;
               }
               break;

            case BoundName:
               value = boundTypes[0];
               break;

            case UnboundName:
               // If it's not bound, check if the entire identifier expression maps to a name - if so, we are done just
               // return the customObj.
               Object customObj = resolveCustomObj(jmodel, sz-1);
               if (customObj == null) {
                  value = ctx.resolveUnboundName(idents.get(0).toString());
               }
               else
                  return customObj;
               break;

            case BoundTypeName:
               customObj = resolveCustomObj(jmodel, 0);
               if (customObj == null) {
                  value = ModelUtil.getRuntimeType(boundTypes[0]);
                  isType = true;
               }
               else
                  isType = false;
               break;

            case EnumName:
               value = ModelUtil.getRuntimeEnum(boundTypes[0]);
               isType = true;
               break;

            case VariableName:
               value = ctx.getVariable(idents.get(0).toString(), true, false);
               isType = false;
               break;

            case BoundObjectName:
               isType = false;
               if (isThisExpression()) {
                  value = ModelUtil.getRuntimeType(boundTypes[0]);
                  break;
               }
               value = evalRootObjectValue(ctx);
               if (value != null)
                  break;
               // else FALL THROUGH

            case FieldName:
               jmodel = getJavaModel();
               if (jmodel != null && jmodel.customResolver != null) {
                  value = jmodel.customResolver.resolveObject(jmodel.getPackagePrefix(), getIdentifierPathName(1), true, true);
                  if (value != null)
                     break;
               }
            case GetVariable:
            case IsVariable:
            case GetSetMethodInvocation:
               isType = false;
               Object thisObj = getRootFieldThis(this, boundTypes[0], ctx, false);
               value = getPropertyValueForType(thisObj, 0);
               break;

            case GetObjectMethodInvocation:
            case MethodInvocation:
               Object methThis;
               if (boundTypes[0] == null)
                  throw new NullPointerException("Eval unresolved method: " + idents.get(0).toString());
               if (!ModelUtil.hasModifier(boundTypes[0], "static")) {
                  methThis = getRootFieldThis(this, boundTypes[0], ctx, false);
                  methThis = checkNullThis(methThis, idents.get(0).toString());
               }
              else
                  methThis = null;
               return ModelUtil.invokeMethod(methThis, boundTypes[0], arguments, expectedType, ctx, true, true, null);

            case RemoteMethodInvocation:
               System.err.println("*** Remote method in dynamic code not yet supported");
               return null;

            case UnboundMethodName:
               Object method = ModelUtil.getMethod(ctx.getCurrentObject(), idents.get(0).toString());
               return ModelUtil.invokeMethod(ctx.getCurrentObject(), method, arguments, expectedType, ctx, true, false, null);

            case ResolvedObjectName: {
               // Need to resolve this entire name using the custom resolver, for the case where you have obj.dynObj - where dynObj is created in the resolver but not a real property of obj.
               value = jmodel.customResolver.resolveObject(jmodel.getPackagePrefix(), getIdentifierPathName(sz), true, true);
               nextIx = sz;
               break;
            }

            default:
               throw new UnsupportedOperationException();
         }

         // TODO: do we need to look for inner classes here?  Outer.Inner.variable?
         // Now that we have the types for each identifier, we can use them here to make
         // the right call.
         // Reusing this code from NewExpression via super.eval for the a.new X() case.  the arguments are set there but we just want to eval the property part.
         if (arguments == null || this instanceof NewExpression) {
            for (int i = nextIx; i < idents.size(); i++) {
               String id = idents.get(i).toString();
               checkNull(value, id);
               switch (idTypes[i]) {
                  /*
                  case BoundObjectName:
                     value = ctx.resolveName(ModelUtil.getTypeName(boundTypes[i]));
                     break;
                     */
                  case BoundObjectName:
                     if (isType)
                        value = DynUtil.getStaticProperty(value, id);
                     else
                        value = DynUtil.getPropertyValue(value, id);
                     isType = false;
                     break;
                  case FieldName:
                  case GetVariable:
                  case IsVariable:
                  case GetSetMethodInvocation:
                     // The entire path name for the identifier expression may be the object path name - e.g. like when created as
                     // outerTypeName.objectId.  In this case, the field name is not a property of the parent type or instance
                     Object customObj = resolveCustomObj(jmodel, i);
                     if (customObj != null)
                        value = customObj;
                     else {
                        if (isType)
                           value = DynUtil.getStaticProperty(value, id);
                        else {
                           //value = DynUtil.getPropertyValue(value, id);
                           value = getPropertyValueForType(value, i);
                        }
                     }
                     isType = false;
                     break;
                  case BoundTypeName:
                     customObj = resolveCustomObj(jmodel, i);
                     if (customObj == null) {
                        value = ModelUtil.getRuntimeType(boundTypes[i]);
                        isType = true;
                     }
                     else
                        isType = false;
                     break;
                  case ThisExpression:
                     value = ctx.findThisType(value);
                     if (value == null)
                        throw new IllegalArgumentException("Unable to evaluate this expression - no type: " + value + " in context");
                     isType = false;
                     break;
                  case EnumName:
                     value = ModelUtil.getRuntimeEnum(boundTypes[i]);
                     isType = false;
                     break;

                  case UnboundName:
                     value = ctx.resolveUnboundName(id);
                     break;
                  case ResolvedObjectName:
                     break; // handled above.
                  default:
                     System.err.println("*** Unhandled case in identifier expression eval ");
               }
            }
         }
         else {
            int i;
            for (i = nextIx; i < idents.size()-1; i++) {
               String id = idents.get(i).toString();
               checkNull(value, id);
               if (valueIsType(i-1))
                  value = DynUtil.getStaticProperty(value, id);
               else {
                  value = DynUtil.getPropertyValue(value, id);
               }
            }

            if (methodName == null) {
               // A GetSetMethodInvocation - looks like a method but really the get property was handled above so just return.
               if (i >= idents.size())
                  return value;
               methodName = idents.get(i).toString();
            }

            if (!isStaticTarget(i))
               checkNull(value, methodName);

            Object method;
            if (idTypes[i] == IdentifierType.MethodInvocation && !superMethod) {
               method = boundTypes[i];
               if (method instanceof AbstractMethodDefinition) {
                  Object rtMethod = ((AbstractMethodDefinition) method).getRuntimeMethod();
                  if (rtMethod != null)
                     method = rtMethod;
               }
            }
            // Need to use the compiled class to resolve the _super_x method
            else if (superMethod) {
               LayeredSystem sys = getLayeredSystem();
               method = ModelUtil.definesMethod(ModelUtil.getCompiledClass(DynUtil.getType(value)), methodName, arguments, null, null, false, false, null, getMethodTypeArguments(), sys);
               // Stub did not generate an _super method so just get the method itself.  Maybe the super.x() should force method x to be
               // included as a dynamic method in the stub?  Is there a case here where we will not get the real super method?
               if (method == null && methodName.startsWith("_super")) {
                  methodName = methodName.substring("_super_".length());
                  method = ModelUtil.definesMethod(ModelUtil.getCompiledClass(DynUtil.getType(value)), methodName, arguments, null, null, false, false, null, getMethodTypeArguments(), sys);
               }
            }
            else
               method = ModelUtil.definesMethod(DynUtil.getType(value), methodName, arguments, null, null, false, false, null, getMethodTypeArguments(), getLayeredSystem());

            /*
              Java ignores anyway?
            if (i != 0 && idTypes[i-1] == IdentifierType.BoundTypeName)
               value = null;
            */
            return ModelUtil.invokeMethod(value, method, arguments, expectedType, ctx, true, i == 0 || idTypes[i-1] != IdentifierType.SuperExpression, null);
         }
         return value;
      }
      catch (IllegalArgumentException exc) {
         displayError("Eval failed - illegal argument: " + exc + " for ");
         throw exc;
      }
   }

   public void setValue(Object valueToSet, ExecutionContext ctx) {
      assert isAssignment && arguments == null;
      List<IString> idents = getAllIdentifiers();
      int sz = idents.size();

      if (sz == 1) {
         String firstIdentifier = idents.get(0).toString();
         switch (idTypes[0]) {
            case ThisExpression:
               throw new IllegalArgumentException("Unable to assign value to 'this' expression: " + toDefinitionString());

            case SuperExpression:
               throw new IllegalArgumentException("Illegal assignment to 'super' keyword " + toDefinitionString());

            case UnboundName:
               ctx.setVariable(firstIdentifier, valueToSet);
               break;

            case BoundTypeName:
               throw new IllegalArgumentException("Illegal set value to an identifier expression bound to a type: " + toDefinitionString());

            case VariableName:
               ctx.setVariable(firstIdentifier, valueToSet);
               break;

            case FieldName:
            case GetVariable:
            case IsVariable:
            case SetVariable:
            case GetSetMethodInvocation:
               Object obj = getRootFieldThis(this, boundTypes[0], ctx, false);
               checkNullThis(obj, firstIdentifier);
               boolean handled = false;
               // Set method needs to override the field even in dynamic models.
               if (boundTypes[0] instanceof MethodDefinition && needsSetMethod()) {
                  MethodDefinition setMethod = (MethodDefinition) boundTypes[0];
                  if (setMethod.propertyMethodType == PropertyMethodType.Set) {
                     setMethod.callVirtual(obj, valueToSet);
                     handled = true;
                  }
               }
               // For the dynamic object, we need to tell it to set the field here since we would have tried the setX method
               // above if we needed to set the property that way.
               if (!handled)
                  DynUtil.setProperty(obj, firstIdentifier, valueToSet, true);
               break;
            default:
               throw new UnsupportedOperationException();
         }
         return;
      }

      Object obj = evalRootValue(ctx);
      String id = idents.get(sz-1).toString();
      checkNull(obj, id);
      if (valueIsType(sz-2))
         DynUtil.setStaticProperty(obj, id, valueToSet);
      else
         DynUtil.setPropertyValue(obj, id, valueToSet);
   }

   public ExecResult exec(ExecutionContext ctx) {
      // just eval and return
      eval(null, ctx);
      return ExecResult.Next;
   }

   public boolean isStaticTarget() {
      return isStaticTarget(0);
   }

   private boolean valueIsType(int ix) {
      if (ix == -1)
         return isStaticTarget(0);

      switch (idTypes[ix]) {
         case PackageName:
            return true;

         case ThisExpression:
            return false;

         // Make sure to skip up to an enclosing class if necessary
         case MethodInvocation:
         case RemoteMethodInvocation:
            return false;

         case UnboundName:
         case SuperExpression:
            return false;

         case BoundTypeName:
            return true;

         case EnumName:
            return false;

         case VariableName:
            return false;

         case BoundObjectName:
         case ResolvedObjectName:
            return false;

         case BoundName:
         case FieldName:
         case GetVariable:
         case IsVariable:
         case SetVariable:
         case GetSetMethodInvocation:
            return false;

         default:
            System.err.println("*** unrecognized type");
      }
      return true;
   }

   Object evalRootValue(ExecutionContext ctx) {
      List<IString> idents = getAllIdentifiers();
      if (idents == null || idents.size() == 0)
         return null;
      String firstIdentifier = idents.get(0).toString();

      Object obj = null;

      int nextIx = 1;
      int sz = idents.size();

      switch (idTypes[0]) {
         case PackageName:
            nextIx = sz-offset();
            obj = boundTypes[nextIx-1];
            break;

         case ThisExpression:
            obj = ctx.getCurrentObject();
            break;

         // Make sure to skip up to an enclosing class if necessary
         case MethodInvocation:
         case RemoteMethodInvocation:
            obj = getRootMethodThis(this, boundTypes[0], ctx);
            break;

         case SuperExpression:
            if (isStaticTarget(sz == 1 ? 0 : 1)) // super.x() for a class refers to the super type.  If the x is a static thing use the static type, otherwise instance
               obj = ctx.getCurrentStaticType();
            else
               obj = ctx.getCurrentObject();
            break;

         case UnboundName:
            obj = ctx.resolveUnboundName(firstIdentifier);
            break;

         case BoundTypeName:
            obj = ModelUtil.getRuntimeType(boundTypes[0]);
            break;

         case EnumName:
            obj = ModelUtil.getRuntimeEnum(boundTypes[0]);
            break;

         case VariableName:
            obj = ctx.getVariable(firstIdentifier, true, false);
            break;

         case BoundObjectName:
            obj = evalRootObjectValue(ctx);
            if (obj != null)
               break;
            // else FALL THROUGH

         case FieldName:
         case GetVariable:
         case IsVariable:
         case SetVariable:
         case GetSetMethodInvocation:
            obj = getRootFieldThis(this, boundTypes[0], ctx, false);

            // In this case, we want the object which stores the field
            if (sz == 1)
                return obj;

            checkNullThis(obj, firstIdentifier);
            if (isStaticTarget(0)) {
               obj = ModelUtil.getStaticPropertyValue(obj, firstIdentifier);
            }
            else {
               obj = TypeUtil.getPropertyValue(obj, firstIdentifier);
            }
            break;

         case BoundName:
            obj = boundTypes[0];
            break;
         case ResolvedObjectName:
            throw new UnsupportedOperationException();

      }

      int i;
      String id;

      for (i = nextIx; i < sz-1; i++) {
         id = idents.get(i).toString();
         if (obj == null)
            return null;  // Don't do checkNull here - happens routinely from suggestCompletions
         if (valueIsType(i-1))
            obj = DynUtil.getStaticProperty(obj, id);
         else
            obj = DynUtil.getPropertyValue(obj, id);
      }
      return obj;
   }

   void checkNull(Object value, String name) {
      if (value == null)
         throw new NullPointerException("Null value encountered deferencing: " + name + " in: " + toDefinitionString());
   }

   private Object checkNullThis(Object value, String name) {
      if (value == null)
         throw new NullPointerException("Attempt to access: " + name + " without 'this'.  No current object: in class context");
      return value;
   }

   public static boolean needsGetSet(IdentifierType idType, Object boundType) {
      switch (idType) {
         case FieldName:
             if (!ModelUtil.needsGetSet(boundType))
                return false;
         case GetVariable:
         case IsVariable:
         case BoundObjectName:
            return true;
      }
      return false;
   }

   private boolean disableGetVariable(int i) {
      List<IString> idents = getAllIdentifiers();
      int sz = idents.size();
      String identifier = idents.get(i).toString();
      return (sz == 1 && inNamedPropertyMethod(identifier)) || isThisExpression() || isSuperExpression() || !getJavaModel().enableExtensions() || isManualGetSet() || (isAssignment && i == sz-1);
   }

   private boolean isManualGetSet() {
      AbstractMethodDefinition enclMeth = getEnclosingMethod();
      if (enclMeth != null) {
         Object annotObj = enclMeth.getAnnotation("sc.obj.ManualGetSet");
         if (annotObj != null) {
            Object manualObj = ModelUtil.getAnnotationValue(annotObj, "value");
            return manualObj == null || !(manualObj instanceof Boolean) || ((Boolean) manualObj);
         }
      }
      return false;
   }

   public boolean needsTransform() {
      List<IString> idents = getAllIdentifiers();
      if (idents == null)
         return super.needsTransform();

      // May not have been started if we were replaced.
      if (replacedByStatement != null)
         return true;

      int sz = idents.size();
      for (int i = 0; i < sz; i++) {
         if (idTypes == null) {
            System.out.println("*** Uninitialized expression during transform - expression was not started");
            return false;
         }
         switch (idTypes[i]) {
            case BoundObjectName:
            case ResolvedObjectName:
               return true;
            case FieldName:
            case GetVariable:
            case IsVariable:
               if (needsGetMethod(i))
                  return true;
         }
      }
      return false;
   }

   public boolean transform(ILanguageModel.RuntimeType runtime) {
      if (transformed)
         return false;
      List<IString> idents = getAllIdentifiers();
      if (idents == null)
         return super.transform(runtime);

      // possibly never started
      if (replacedByStatement != null)
         return false;

      boolean any = false;
      if (super.transform(runtime))
        any = true;

      // If we are transforming a template into an interpreted StrataCode type, just return at this point.
      // The types won't have the getTypedProperty etc. methods needed for at least the dynamic transformation
      // and we interpret the StrataCode code.
      if (runtime == ILanguageModel.RuntimeType.STRATACODE)
         return any;

      // Cases:
      // 1: objectVariableName:   name -> type.accessClass.getObjectVariableName
      // 2: Type.objectVariableName:   Type.getObjectVariableName()
      // 2b: type.Type.objectVariableName: ...
      // 3: Type.objectVariable.property:  Type.getobjectVariable().property
      // 3b: objectVariable.property: <type.accessClass>.getObjectVariable().property
      int sz = idents.size();
      int incr = 0;
      boolean inSuperExpr = false;

      for (int i = 0; i < sz; i++) {
         String identifier = idents.get(i+incr).toString();
         if (idTypes == null)
            System.out.println("*** Error identifier expression not initialized!");
         if (idTypes[i] == null)
            continue;

         switch (idTypes[i]) {
            case UnboundName:
               boundTypes[i] = getJavaModel().findTypeDeclaration(identifier, true);
               if (boundTypes[i] == null) {
                  break;
               }
               if (boundTypes[i] instanceof TypeDeclaration) {
                  TypeDeclaration bt = (TypeDeclaration) boundTypes[i];
                  if (bt.getDeclarationType() != DeclarationType.OBJECT)
                     break;
               }
               // TODO: This is a class - no way to detect object definition from classes right now...
               else {
                  break;
               }
            // Fall THRU for newly bound objects
            case BoundObjectName:
               {
                  // Already bound to a method - no need to transform
                  if (ModelUtil.isMethod(boundTypes[i]))
                     break;

                  Object type = boundTypes[i];

                  // Don't convert ObjectName.this since in that case, it is a class or this reference
                  // Also don't convert if this is the lhs of an AssignmentExpression since that will
                  // go through convertToSet.  That only applies to the last component of the identifier expression of course
                  if (isThisExpression() || isSuperExpression() || !getJavaModel().enableExtensions() || (isAssignment && i == sz-1) || isGetSetConversionDisabled(i))
                     break;

                  // For ObjectName.staticVar, do not convert this to getObjectName().staticVar!
                  if (i == 0 && sz > 1 && isStaticTarget(i+1)) {
                     break;
                  }

                  if (i == 0 || (i == 1 && idTypes[0] == IdentifierType.ThisExpression)) {
                     // We skip converting x when it's in the getX method unless the 'x' field is not accessible.
                     if ((idents.size() == 1 && inNamedPropertyMethod(identifier))) {
                        break;
                     }

                     Object outer = ModelUtil.getEnclosingType(type);

                     Object accessClass = outer != null ? outer : type;

                     /*
                      * Skip this if this is an instance type and it is in the same file.  Not sure this is exactly
                      * the right test but this fails if we have a non-static inner class reference and we prepend
                      * the containing classes identifier.
                     */
                     if (!enclosingTypeExtends(accessClass)) {
                        addIdentifier(i, ModelUtil.getClassName(accessClass), IdentifierType.BoundObjectName, type);
                        sz++;
                        i++;
                     }
                  }
                  any = true;

                  // This converts position "i" to a get method.  It will either process all of the elements or
                  // we are on the last element anyway.
                  if (bindingStatement == null) { // for bindings we've already removed this expression so no need to transform it further
                     if (convertToGetMethod(identifier, i, sz, incr, false))
                        return true;
                  }
               }

               break;

            case FieldName:
               if (!ModelUtil.needsGetSet(boundTypes[i]) || isGetSetConversionDisabled(i))
                  break;
               // FALL THROUGH
            case GetVariable:
            case IsVariable:
               if (disableGetVariable(i))
                  break;

               // This converts position "i" to a get method.  It will either process all of the elements or
               // we are on the last element anyway.
               if (bindingStatement == null) {
                  if (convertToGetMethod(identifier, i, sz, incr, idTypes[i] == IdentifierType.IsVariable))
                     return true;
               }

               break;

            case MethodInvocation:
               if (ModelUtil.isDynamicType(boundTypes[i]) && !inSuperExpr) {
                  convertToDynamicMethod(identifier, i, sz, incr);
               }
               break;

            case GetSetMethodInvocation:
               // No need to transform but do print an error if we bound to a get/set method and then did not convert this field to a get/set
               VariableDefinition varDef = (VariableDefinition) boundTypes[i];
               if (!varDef.convertGetSet) {
                  // Happens during transform if we hit a setX of a non-bindable property.
                  displayRangeError(i, i, "Reference to field ", varDef.toDefinitionString(), " as a get/set without a get/set conversion from: ");
               }
               break;

            case ThisExpression:
               for (int j = 1; j < sz; j++) {
                  switch (idTypes[j]) {
                     case GetVariable:
                     case IsVariable:
                        if (convertToGetMethod(idents.get(j).toString(), j, sz, incr, idTypes[i] == IdentifierType.IsVariable)) {
                           return true;
                        }
                        break;
                  }
               }
               break;

            case SuperExpression:

               inSuperExpr = true;
               if (arguments != null) {
                  AbstractMethodDefinition enclMethod = getEnclosingMethod();

                  // For templates, a super needs to get mapped to the output method inside
                  if (enclMethod == null) {
                     ITypeDeclaration encType = getEnclosingIType();
                     if (encType instanceof Template)
                        enclMethod = ((Template) encType).getOutputMethod();
                  }
                  
                  // If this super method expression is inside of a modified method, we need to remap it to a new expression as
                  // we simulate the super construct with modified methods.
                  //
                  // If this layer did not override the super method, we just replace super with this.  If we did
                  // override that method in this layer, we replace super with _super_layerName.
                  //
                  // Note - there is some question as to whether we should map super to 'this' or '_super_xxx" when you are in a method which is not modifying another method.
                  // we used to do this (see multiComponentTest/sub/BaseClass.sc for an example) so that you could call the method in a base-layer even if you were not overriding that
                  // method.  I think that for constructors in particular, there is some abiguity where that had to change and so we had to modify those tests to use 'this' instead.  That means
                  // you can only call the modified method from within an overriding method.
                  //
                  // A third alternative is to just use "modify" as an explicit keyword.  Maybe we use it as a replacement to class and also in the super/this case as an identifier.  That will make everything explicit.
                  if (enclMethod != null && enclMethod.modified) {
                     // Get the enclosing type again - we really don't want the class's type here which is what
                     // we bound to in the init method.
                     ITypeDeclaration itype = getEnclosingIType();

                     Object refMethObj;

                     // super(x, y, z) - i.e. refer to the constructor.
                     if (sz == 1) {
                        refMethObj = itype.definesMethod(itype.getTypeName(), arguments, null, null, itype.isTransformedType(), false, null, getMethodTypeArguments());
                     }
                     // super.method(x,y,z)
                     else if (sz > 1) {
                         refMethObj = itype.definesMethod(idents.get(1).toString(), arguments, null, null, itype.isTransformedType(), false, null, getMethodTypeArguments());
                     }
                     else {
                         break; // Not sure why we'd get here
                     }


                     JavaModel model = getJavaModel();

                     Layer overrideLayer = model.getLayeredSystem().getLayerByName(enclMethod.overriddenLayer);

                     if (refMethObj instanceof AbstractMethodDefinition) {
                        AbstractMethodDefinition refMeth = (AbstractMethodDefinition) refMethObj;
                        String refMethName = refMeth.overriddenMethodName;
                        // If this is a constructor, fall back to "this" since there's no other class.
                        // For a regular method, we should have renamed that method already so it's name should be right
                        if (refMethName == null)
                           refMethName = sz == 1 ? "this" : refMeth.name;
                        else {
                           /*
                            * The refMeth is right now the main method.  Since this particular method was defined
                            * in some layer, not necessarily the latest, we need to advance layer by layer till we
                            * find the first version of the method defined in a layer after this method so its super
                            * gets bound to the right instance method.
                            */
                           while (true) {
                              Layer methLayer = model.getLayeredSystem().getLayerByName(refMeth.overriddenLayer);
                              if (methLayer.getLayerPosition() > overrideLayer.getLayerPosition()) {
                                 refMethObj = itype.definesMethod(refMeth.overriddenMethodName, arguments, null, null, itype.isTransformedType(), false, null, getMethodTypeArguments());
                                 if (refMethObj != null && refMethObj instanceof AbstractMethodDefinition) {
                                    refMeth = (AbstractMethodDefinition) refMethObj;
                                    if (refMeth.overriddenMethodName == null) {
                                       refMethName = "this";
                                       break;
                                    }
                                    else {
                                       refMethName = refMeth.overriddenMethodName;
                                    }
                                 }
                                 else {
                                    refMeth = null;
                                    break;
                                 }
                              }
                              else
                                 break;
                           }
                        }
                        if (refMethName != null) {
                           if (!refMethName.equals("this") && idents.size() > 1) {
                              idents.remove(1);
                           }
                           idents.set(0, PString.toIString(refMethName));
                           ParseUtil.restartComponent(this);
                           return true;
                        }
                        else
                           System.err.println("*** Unable to find overridden method for: " + toDefinitionString());
                     }
                  }
               }
               break;
            // Gets handled now in transformToJS - could do it here though?
            case ResolvedObjectName:
               break;
            case RemoteMethodInvocation:
               // This should be a binding expression and gets handled in transforming the binding args
               break;
         }
      }
      return any;
   }

   /**
    * To decide if we need to prepend the type: if any of the parent types of this reference extends that type
    * we do not need to prepend the type.
    */
   private boolean enclosingTypeExtends(Object other) {
      JavaSemanticNode node = this;
      TypeDeclaration decl;
      do {
         decl = node.getEnclosingType();
         if (decl != null)
            if (ModelUtil.isAssignableFrom(other, decl))
               return true;
         node = decl;
      } while (decl != null);
      return false;
   }

   /**
    * If we are part of an assignment and our last value points to a field with a set method and we are not in
    * the set method itself, we need to be transformed into a setX expression.
    */
   public boolean needsSetMethod() {
      int last = idTypes.length-1;
      IdentifierType idType = idTypes[last];
      // If the type started out as a set, or it started out as a field but needs conversion and we are not
      // in a property method we do the set conversion.
      return (idType == IdentifierType.SetVariable ||
             (idType == IdentifierType.FieldName && ModelUtil.needsSet(boundTypes[last]))) &&
             !inPropertyMethodForDef(boundTypes[last]) && !isManualGetSet();
   }

   public boolean needsGetMethod(int ix) {
      List<IString> idents = getAllIdentifiers();
      switch (idTypes[ix]) {
         case FieldName:
            return ModelUtil.needsGetSet(boundTypes[ix]) && !isGetSetConversionDisabled(ix);
         case GetVariable:
         case IsVariable:
            return !((idents.size() == 1 && inPropertyMethodForDef(boundTypes[ix])) ||
                   isThisExpression() || isSuperExpression() || !getJavaModel().enableExtensions());
      }
      return false;
   }

   protected Expression doCastOnConvert(Expression arg) {
      List<IString> idents = getAllIdentifiers();
      int ix = idents.size()-1;
      VariableDefinition vdef;

      // For short s;  a.s = 3; when we do a.setS(3) we need to convert that to a.setS((short) 3).
      // Java's got type different conversion rules for assignments and parameters
      if (idTypes[ix] == IdentifierType.FieldName && boundTypes[ix] instanceof VariableDefinition &&
              (vdef = (VariableDefinition) boundTypes[ix]).needsCastOnConvert) {
         // Need a paren expression to properly wrap an arithmetic expression or the grammar barfs
         if (!(arg instanceof IdentifierExpression))
            arg = ParenExpression.create(arg);
         arg = CastExpression.create(ModelUtil.getTypeName(vdef.getTypeDeclaration()), arg);
      }
      return arg;
   }

   public static String convertPropertyToSetName(String propertyName) {
      return "set" + CTypeUtil.capitalizePropertyName(propertyName);
   }

   protected String convertPropertyToGetName(int ix, String propertyName) {
      return (idTypes[ix] == IdentifierType.IsVariable ? "is" : "get") + CTypeUtil.capitalizePropertyName(propertyName);
   }

   public void convertToSetMethod(Expression arg) {
      assert arguments == null;
      List<IString> idents = getAllIdentifiers();
      int ix = idents.size()-1;
      String propertyName = idents.get(ix).toString();

      Object origType = boundTypes[ix];

      SemanticNodeList<Expression> newArgs = new SemanticNodeList<Expression>(1);
      if (ModelUtil.isDynamicType(origType)) {
         // a.b = 3 -> setProperty("name", 3)
         newArgs.add(StringLiteral.create(propertyName));
         newArgs.add(arg);

         idents.set(ix, PString.toIString(IDynObject.SET_PROPERTY_NAME));
      }
      else {
         arg = doCastOnConvert(arg);
         newArgs.add(arg);

         idents.set(ix, PString.toIString(convertPropertyToSetName(propertyName)));
      }
      setProperty("arguments", newArgs);

      ParseUtil.restartComponent(this);

      Object newType = boundTypes[ix];
      if (newType != null && origType != null) {
         // Did the setX resolve to a different setX - because we may not have transformed it yet?  If so, null it out so we can fix it later.
         if (!ModelUtil.sameTypes(ModelUtil.getEnclosingType(newType), ModelUtil.getEnclosingType(origType))) {
            boundTypes[ix] = origType;
            idTypes[ix] = IdentifierType.GetSetMethodInvocation;
         }
      }
   }

   /**
    * This returns true when this identifier expression is modified by a "this" operator - i.e. "ClassA.this"
    * An identifier expression will always start with "this" and can't have "this" as a second or subsequent
    * element.  Instead, the "this" is put into a VariableSelector... The grammar dictates this structure
    */
   private boolean isThisExpression() {
      if (parentNode instanceof SelectorExpression) {
         if (((SelectorExpression) parentNode).isThisExpression())
            return true;
      }
      if (idTypes == null) {
         return false;
      }

      // This does actually occur in some cases... I think maybe when we build them in code?
      for (IdentifierType type:idTypes)
         if (type == IdentifierType.ThisExpression)
            return true;
      return false;
   }

   private boolean isSuperExpression() {
      if (parentNode instanceof SelectorExpression) {
         if (((SelectorExpression) parentNode).isSuperExpression())
            return true;
      }
      // This does actually occur in some cases... I think maybe when we build them in code?
      for (IdentifierType type:idTypes)
         if (type == IdentifierType.SuperExpression)
            return true;
      return false;
   }

   private void convertToDynamicMethod(String identifier, int i, int sz, int incr) {
      boolean isStatic = isStaticTarget(i);

      int ix = i + incr;

      boolean enclosingIsDynamic = isDynamicType();

      IdentifierExpression newExpr = IdentifierExpression.create(isStatic ? "sc.lang.DynObject.invokeStatic" : (enclosingIsDynamic ? "invoke" : "sc.lang.DynObject.invokeInst"));
      SemanticNodeList<Expression> args = new SemanticNodeList<Expression>();
      if (i == 0) {
         // static: DynObject.invokeStatic(encType(a), "a", "asig")
         // instance: a() -> invoke("a", asig")
         if (isStatic)
             args.add(StringLiteral.create(ModelUtil.getTypeName(ModelUtil.getEnclosingType(boundTypes[ix]))));
         else if (!enclosingIsDynamic)
            args.add(IdentifierExpression.create("this"));
      }
      else {
         // static: a.b.c()  -> DynObject.invokeStatic("a.b", "c", "csig")
         // instance: a.b.c() -> a.b.invoke("c", "csig")
         if (isStatic)
            args.add(StringLiteral.create(ModelUtil.getTypeName(getTypeForIdentifier(i-1))));
         else if (i > 0) {
            args.add(IdentifierExpression.create(getIdentifierPathName(i)));
         }

         // TODO: need to convert this to a static method when "a.b" is not known to be dynamic
      }
      args.add(StringLiteral.create(identifier));
      String sig = ModelUtil.getTypeSignature(boundTypes[ix]);
      if (sig == null)
         args.add(NullLiteral.create());
      else
         args.add(StringLiteral.create(sig));
      for (Expression expr:arguments) {
         args.add(expr);
      }
      newExpr.setProperty("arguments", args);

      parentNode.replaceChild(this, newExpr);
   }

   private boolean convertToGetMethod(String identifier, int i, int sz, int incr, boolean isIs) {
      boolean selExprParent = false;
      List<IString> idents = getAllIdentifiers();

      // We may have found an object type even if we were declared with a getX method.  If we search for getX we'll return object X.  In this case,
      // our identifier needs to match the property name of the object.  Otherwise, properties with getGet would break.
      String identsProperty = ModelUtil.convertGetMethodName(identifier);
      if (identsProperty != null) {
         if (identsProperty.equals(ModelUtil.getPropertyName(boundTypes[i])))
            return false;
      }

      if (!referenceInitializer) {
         AbstractMethodDefinition def = getEnclosingMethod();
         if (def != null && def instanceof MethodDefinition) {
            MethodDefinition mdef = (MethodDefinition) def;
            referenceInitializer = ModelUtil.isChainedReferenceInitializer(mdef);
         }
      }

      int ix = i + incr;

      Object boundType = boundTypes[ix];
      Object encType = ModelUtil.getEnclosingType(boundType);
      boolean dynamicType = ModelUtil.isDynamicProperty(boundType);
      Expression thisExpr = null;
      IString getStr;
      Expression varDynType = null;
      if (dynamicType) {
         // Need to use the compiled class name here since the class may not have been compiled yet
         varDynType = ClassValueExpression.create(ClassType.getWrapperClassFromTypeName(ModelUtil.getCompiledClassName(getTypeForIdentifier(ix))));
         if (isStaticTarget(ix)) {
            // staticVar -> DynObject.getTypedStaticProperty("varEnclTypeName", "varName", VarType.class)
            // staticVar.c.d(y) -> ((VarType) DynObject.getTypedStaticProperty("varEnclTypeName", "varName")).c.d(y)

            // For Root objects, we use resolveName.  Otherwise getTypedStaticProperty
            SemanticNodeList<Expression> args = new SemanticNodeList<Expression>();
            thisExpr = IdentifierExpression.create("sc.lang.DynObject." + (encType != null ? "getTypedStaticProperty" : "resolveName"));
            Object rootType;
            if (encType != null)
               rootType = encType;
            else
               rootType = boundType;
            args.add(StringLiteral.create(ModelUtil.getTypeName(rootType)));
            if (encType != null)
               args.add(StringLiteral.create(identifier));
            args.add(varDynType);
            thisExpr.setProperty("arguments", args);
            getStr = null;
         }
         else {
            if (ModelUtil.isPrimitiveNumberType(varDynType)) {
               SemanticNodeList<Expression> args = new SemanticNodeList<Expression>();
               // TODO: this only deals with i == 0 right?
               args.add(IdentifierExpression.create("this"));
               args.add(StringLiteral.create(identifier));
               thisExpr = IdentifierExpression.create("sc.dyn.DynUtil", ModelUtil.getNumberPrefixFromType(varDynType) + "PropertyValue");
               getStr = null;
            }
            else {
               // TODO: if this is a primitive number type, we should turn this into call to TypeUtil.intValue etc.
               // TODO: handle outer class variable references - prepend the selector for "this"
               // instVar -> InstVarClass.this.getProperty("varName", varClass.class)
               getStr = PString.toIString(IDynObject.GET_TYPED_PROPERTY_NAME);
            }
         }
      }
      else {
         // assert identifier = identifiers.get(I+incr).toString()
         getStr = PString.toIString((isIs ? "is" : "get") + CTypeUtil.capitalizePropertyName(identifier));
      }

      // If we are mapping "a.b.c.Obj" we need: a.b.c.Obj.getObj() unless Obj is an inner class"
      if (getStr != null) {
         if (ix > 0 && idTypes[ix-1] == IdentifierType.PackageName && encType == null) {
            idents.add(i+incr+1, getStr);
            incr++;
         }
         else
            idents.set(i+incr, getStr);
      }

      // Can't combine arguments and the arrayDimensions from the array element expression into an IdentifierExpression
      if (i == sz - 1 && !(this instanceof ArrayElementExpression)) {
         // Had to replace this expression above
         if (thisExpr != null) {
            if (parentNode.replaceChild(this, thisExpr) == -1)
               System.out.println("*** failed to replace identifier expression during transform");
            return false;
         }

         assert arguments == null;
         SemanticNodeList<Expression> args = new SemanticNodeList<Expression>(1);
         if (referenceInitializer && boundTypes[i] != null && ModelUtil.isComponentType(boundTypes[i]) && !dynamicType)
            args.add(BooleanLiteral.create(false));
         else if (dynamicType) {
            args.add(StringLiteral.create(identifier));
            args.add(varDynType);
            // TODO: need to pass in "false" here for the "doInit" method if referenceInitializer is true.  Need to add this concept to the IDynObject interface.
         }
         setProperty("arguments", args); // Turn this into an empty method
         ParseUtil.restartComponent(this);
         return false;
      }
      else {
         // If we need to turn an intermediate node into a method call, we can't represent the
         // new expression with the same old expression.
         // Create a selector expression with this guy as the expression.
         // convert identifiers from i+1 into variable selectors... for the last one,
         // copy the arguments over.  Also need to transform those nodes.
         SelectorExpression se;

         int addIx = -1;
         if (parentNode instanceof SelectorExpression) {
            se = (SelectorExpression) parentNode;
            addIx = 0;
            selExprParent = true;
         }
         else {
            se = new SelectorExpression();
            se.setProperty("selectors", new SemanticNodeList<Selector>(sz));
            if (fromStatement != null)
               se.fromStatement = fromStatement; // Track for debugging line number registration
         }

         boolean xformThis = false;
         ISemanticNode oldParent = this.parentNode;

         // These are the arguments to add to the ith identifier - we may use this list in a variety of ways based
         // on what follows.
         SemanticNodeList<Expression> args = new SemanticNodeList<Expression>();
         if (dynamicType) {
            args.add(StringLiteral.create(identifier));
            args.add(varDynType);
         }

         boolean dynProperty = false;

         for (int j = i+1; j < sz; j++) {
            VariableSelector v = new VariableSelector();

            String subIdent = idents.get(j+incr).toString();

            if (j == sz - 1 && arguments != null) {
               if (idTypes[j] == IdentifierType.MethodInvocation && ModelUtil.isDynamicType(boundTypes[j])) {
                  // a.getB().dynC(x) -> a.getB().invoke("dynC", x")
                  arguments.add(0, StringLiteral.create(subIdent));
                  subIdent = "invoke";
               }
               v.setProperty("arguments", arguments);
            }
            else if (idTypes[j] == IdentifierType.BoundObjectName || idTypes[j] == IdentifierType.GetVariable ||
                     idTypes[j] == IdentifierType.IsVariable ||
                     (idTypes[j] == IdentifierType.FieldName && ModelUtil.needsGetSet(boundTypes[j]) && !isGetSetConversionDisabled(j) && (!isAssignment || j != sz-1))) {
               boolean subDynamic = ModelUtil.isDynamicProperty(boundTypes[j]);
               SemanticNodeList<Expression> subArgs = new SemanticNodeList<Expression>(1);
               if (subDynamic) {
                  Object varType = getTypeForIdentifier(j);
                  // We have a.b.c where we need to go: Type.method(a, "b").c
                  if (ModelUtil.isPrimitiveNumberType(varType)) {
                     String primValStr = "sc.dyn.DynUtil." + ModelUtil.getNumberPrefixFromType(varType) + "PropertyValue";
                     IdentifierExpression subExpr = IdentifierExpression.create(primValStr);
                     if (se.selectors.size() == 0) {
                        subArgs.add(thisExpr == null ? this : thisExpr);
                     }
                     else {
                        subArgs.add(se);
                     }
                     
                     subArgs.add(StringLiteral.create(subIdent));
                     subExpr.setProperty("arguments", subArgs);

                     // Special case - here we are converting from a selector expression to an identifier expression.
                     // Need to do most things differently in this case so we are just breaking out.
                     if (j == sz - 1) {
                        for (int k = i+1; k < sz; k++)
                           idents.remove(i+incr+1);
                        setProperty("arguments", args);
                        ParseUtil.stopComponent(this);
                        if (oldParent.replaceChild(this, subExpr) == -1)
                           System.out.println("*** failed to replace child in transform");
                        return true;
                     }
                     // If there are more selectors, we can convert back to a selector expression
                     else {
                        SelectorExpression subSel = new SelectorExpression();
                        subSel.setProperty("expression", subExpr);
                        if (fromStatement != null)
                           subSel.fromStatement = fromStatement;
                        se = subSel;
                     }
                  }
                  else {
                     // a.instProp -> a.getTypedProperty(name, class)
                     subArgs.add(StringLiteral.create(subIdent));
                     subArgs.add(ClassValueExpression.create(ModelUtil.getCompiledClassName(varType)));
                     subIdent = IDynObject.GET_TYPED_PROPERTY_NAME;
                     dynProperty = true;
                  }
               }
               else
                  subIdent = convertPropertyToGetName(j, subIdent);

               if (j == sz - 1 && referenceInitializer && ModelUtil.isComponentType(boundTypes[j]) && !dynProperty)
                  subArgs.add(BooleanLiteral.create(false));
               v.setProperty("arguments", subArgs);
            }
            v.setProperty("identifier", subIdent);

            if (!selExprParent)
               se.selectors.add(v);
            // If we are inserting into our parent's selector expression, put them in order but ahead of
            // the original selectors to preserve the order
            else
               se.selectors.add(addIx++, v);
         }
         // For ArrayElementExpressions, we also need to add the array selectors onto the
         // selector expression and we can't reuse "this" class because it is the wrong type.
         // We create a new identifier expression and copy over the identifiers.
         // TODO: move this into a method overriden in ArrayElementExpression
         if (this instanceof ArrayElementExpression) {
            ArrayElementExpression aex = (ArrayElementExpression) this;
            for (Expression arrSel:aex.arrayDimensions) {
               if (!selExprParent)
                  se.selectors.add(ArraySelector.create(arrSel));
               else
                  se.selectors.add(addIx++, ArraySelector.create(arrSel));
            }
            if (isAssignment) {
               se.setAssignment(true);
               setAssignment(false);
            }
            IdentifierExpression nx = IdentifierExpression.create(idents.subList(0,i+1).toArray(new IString[1]));
            nx.setProperty("arguments", args);
            ParseUtil.stopComponent(this);
            se.setProperty("expression", nx);
         }
         else {
            for (int j = i+1; j < sz; j++)
               idents.remove(i+incr+1);

            if (thisExpr == null) {
               setProperty("arguments", args);

               ParseUtil.stopComponent(this);

               // This changes our parent so we need to use the one above in the replace.
               se.setProperty("expression", this);
            }
            else {
               se.setProperty("expression", thisExpr);
            }

            if (isAssignment) {
               // This expression is no longer part of the assignment, but the selector expression is instead
               setAssignment(false);
               se.setAssignment(true);
            }

         }

         if (!selExprParent) {
            // Since we modified this component, we need to go through and reinitialize it after we do the
            // replace.
            ParseUtil.stopComponent(this);

            int rix = oldParent.replaceChild(this, se);
            if (rix == -1)
               System.out.println("*** Could not replace identifier expression with selector expression in convertToGet");
         }
         else
            ParseUtil.restartComponent(se);

         return true;
      }
   }

   /* 
    * This implements the contextual rule to disable field to getX conversion.  It's right now (field) where we have to explicitly exclude the if
    * and switch which are implemented using a ParenExpression.  There probably should be a cleaner way to specify this?
    */
   public boolean isGetSetConversionDisabled(int ix) {
      if (parentNode instanceof ParenExpression) {
         ParenExpression pe = (ParenExpression) parentNode;
         if (pe.parentNode instanceof IfStatement || pe.parentNode instanceof SwitchStatement || pe.parentNode instanceof WhileStatement ||
             pe.parentNode instanceof SynchronizedStatement)
            return false;

         Object boundType = getTypeForIdentifier(ix);
         if (ModelUtil.isField(boundType) || (ModelUtil.isObjectType(boundType) && !ModelUtil.isObjectSetProperty(boundType)))
            return true;
      }
      return false;
   }

   public Object getTypeForIdentifier(int ix) {
      Object type = getTypeForIdentifier(idTypes, boundTypes, arguments, ix, getJavaModel(), null, inferredType, getEnclosingType());
      // TODO: Change ComponentImpl below to some private class cause this is now a sentinel and will not work if it's an actual class people use.
      // It's used to workaround the fact that we resolve the @Component methods before the base class is transformed.  They get resolved to this
      // special class.  Now we need to map it back in these special cases.  Perhaps the preInit, etc. methods should be generated and put
      // into the hidden body at start time, then moved over during transform?
      if (type == ComponentImpl.class) {
         Object realType = getEnclosingType();
         if (idTypes[ix] == IdentifierType.SuperExpression)
            return ModelUtil.getExtendsClass(realType);
         else if (idTypes[ix] == IdentifierType.ThisExpression)
            return realType;
      }
      return type;
   }

   public Object getGenericTypeForIdentifier(int ix) {
      return getGenericTypeForIdentifier(idTypes, boundTypes, arguments, ix, getJavaModel(), null, inferredType, getEnclosingType());
   }

   public boolean getLHSAssignmentTyped() {
      if (boundTypes == null)
         return false;

      int last = boundTypes.length - 1;
      return idTypes[last] == IdentifierType.MethodInvocation && ModelUtil.isLHSTypedMethod(boundTypes[last]);
   }

   static Object getGenericTypeForIdentifier(IdentifierType[] idTypes, Object[] boundTypes, List<Expression> arguments, int ix, JavaModel model, Object rootType, Object inferredType, ITypeDeclaration definedInType) {
      if (boundTypes == null)
         return null;
      if (rootType != null && ModelUtil.isTypeVariable(rootType))
         rootType = ModelUtil.getTypeParameterDefault(rootType);
      if (idTypes[ix] != null) {
         switch (idTypes[ix]) {
            case FieldName:
               if (model != null && !model.enableExtensions()) {
                  // Do not expand the type into the property's type when we are in a .java file.  Otherwise, stuff won't compile
                  // like a case where an int field is shadowed by a double getX method.
                  if (boundTypes[ix] instanceof IBeanMapper) {
                     return resolveType(ModelUtil.getVariableGenericTypeDeclaration(((IBeanMapper) boundTypes[ix]).getField(), model), ix, idTypes);
                  }
               }
               // FALL THRU
            case VariableName:
            case GetVariable:
            case IsVariable:
            case GetSetMethodInvocation:
            case GetObjectMethodInvocation:
               return resolveType(ModelUtil.getVariableGenericTypeDeclaration(boundTypes[ix], model), ix, idTypes);
            case RemoteMethodInvocation:
            case MethodInvocation:
               Object smt = getSpecialMethodType(ix, idTypes, boundTypes, model, rootType, definedInType);
               if (smt != null)
                  return resolveType(smt, ix, idTypes);
               // If ix > 0 and ix - 1's type has type parameters (either a field like List<X> or a method List<X> get(...).
               // need to apply the method's type parameters against the ones in the previous type.
               return resolveType(ModelUtil.getMethodTypeDeclaration(rootType != null ? rootType : getTypeContext(idTypes, boundTypes, ix), boundTypes[ix], arguments, model == null ? null : model.getLayeredSystem(), model, inferredType, definedInType), ix, idTypes);
            case SetVariable:
               return resolveType(ModelUtil.getSetMethodPropertyType(boundTypes[ix], model), ix, idTypes);
            case EnumName:
               return resolveType(ModelUtil.getEnumTypeFromEnum(boundTypes[ix]), ix, idTypes);
            case BoundName:
               return resolveType(boundTypes[ix] != null ? boundTypes[ix].getClass() : null, ix, idTypes);
         }
      }
      Object type = resolveType(boundTypes[ix], ix, idTypes);
      boundTypes[ix] = type;
      if (type instanceof ITypedObject) {
         return ((ITypedObject) type).getTypeDeclaration();
      }
      return type;
   }

   static Object getClassMethod, cloneMethod;

   private static Object getRootType(int ix, IdentifierType[] idTypes, Object[] boundTypes, JavaModel model, Object rootType, ITypeDeclaration definedInType) {
      Object classType;
      if (rootType != null)
         classType = rootType;
      else {
         if (ix > 0)
            classType = getGenericTypeForIdentifier(idTypes, boundTypes, null, ix - 1, model, null, null, definedInType);
         else
            classType = definedInType;
      }
      return classType;
   }

   /** Implements the special rule for Class<?> getClass() - the type parameter is bound to the owner class */
   static Object getSpecialMethodType(int ix, IdentifierType[] idTypes, Object[] boundTypes, JavaModel model, Object rootType, ITypeDeclaration definedInType) {
      if (getClassMethod == null) {
         getClassMethod = ModelUtil.getMethod(model.getLayeredSystem(), Class.class, "getClass", null, null, null, false, null, null, (Object[]) null);
         if (getClassMethod instanceof ParamTypedMethod)
            getClassMethod = ((ParamTypedMethod) getClassMethod).method;
         cloneMethod = ModelUtil.getMethod(model.getLayeredSystem(), Class.class, "clone", null, null, null, false, null, null, (Object[]) null);
         if (cloneMethod instanceof ParamTypedMethod)
            cloneMethod = ((ParamTypedMethod) cloneMethod).method;
      }
      Object bt = boundTypes[ix];
      if (bt == getClassMethod || (bt instanceof ParamTypedMethod) && ((ParamTypedMethod) bt).method == getClassMethod) {
         Object classType = getRootType(ix, idTypes, boundTypes, model, rootType, definedInType);

         ArrayList<Object> typeDefs = new ArrayList<Object>(1);
         typeDefs.add(classType);
         return new ParamTypeDeclaration(model.getLayeredSystem(), definedInType, ModelUtil.getTypeParameters(Class.class), typeDefs, Class.class);
      }
      else if (bt == cloneMethod || (bt instanceof ParamTypedMethod) && ((ParamTypedMethod) bt).method == cloneMethod) {
         // Like the ArrayCloneMethod class, here we are implementing the rule that int[] src; int[] res = src.clone() macthes typewise
         Object classType = getRootType(ix, idTypes, boundTypes, model, rootType, definedInType);
         if (ModelUtil.isArray(classType))
            return classType;
      }
      return null;
   }

   static Object getTypeForIdentifier(IdentifierType[] idTypes, Object[] boundTypes, List<Expression> arguments, int ix, JavaModel model, Object rootType, Object inferredType, ITypeDeclaration definedInType) {
      if (boundTypes == null)
         return null;
      if (rootType != null && ModelUtil.isTypeVariable(rootType))
         rootType = ModelUtil.getTypeParameterDefault(rootType);
      if (idTypes[ix] != null) {
         switch (idTypes[ix]) {
            case FieldName:
               if (model != null && !model.enableExtensions()) {
                  // Do not expand the type into the property's type when we are in a .java file.  Otherwise, stuff won't compile
                  // like a case where an int field is shadowed by a double getX method.
                  if (boundTypes[ix] instanceof IBeanMapper) {
                     return resolveType(ModelUtil.getVariableTypeDeclaration(((IBeanMapper) boundTypes[ix]).getField()), ix, idTypes);
                  }
               }
               // FALL THRU
            case VariableName:
            case GetVariable:
            case IsVariable:
            case GetSetMethodInvocation:
            case GetObjectMethodInvocation:
               return resolveType(ModelUtil.getVariableTypeDeclaration(boundTypes[ix], model), ix, idTypes);
            case MethodInvocation:
            case RemoteMethodInvocation:
               Object smt = getSpecialMethodType(ix, idTypes, boundTypes, model, rootType, definedInType);
               if (smt != null)
                  return resolveType(smt, ix, idTypes);
               // If ix > 0 and ix - 1's type has type parameters (either a field like List<X> or a method List<X> get(...).
               // need to apply the method's type parameters against the ones in the previous type.
               return resolveType(ModelUtil.getMethodTypeDeclaration(rootType != null ? rootType : getTypeContext(idTypes, boundTypes, ix), boundTypes[ix], arguments, model == null ? null : model.getLayeredSystem(), model, inferredType, definedInType), ix, idTypes);
            case SetVariable:
               return resolveType(ModelUtil.getSetMethodPropertyType(boundTypes[ix], model), ix, idTypes);
            case EnumName:
               return resolveType(ModelUtil.getEnumTypeFromEnum(boundTypes[ix]), ix, idTypes);
            case BoundName:
               return resolveType(boundTypes[ix] != null ? boundTypes[ix].getClass() : null, ix, idTypes);
            case SuperExpression:
               // If it's a super(x) for the constructor we refer to the method not the type
               if (ix == 0 && ModelUtil.isConstructor(boundTypes[0])) {
                  return resolveType(ModelUtil.getEnclosingType(boundTypes[0]), ix, idTypes);
               }
               break;
            case ThisExpression:
               if (ModelUtil.isConstructor(boundTypes[ix]))
                  return resolveType(ModelUtil.getEnclosingType(boundTypes[ix]), ix, idTypes);
               break;
         }
      }
      Object type = resolveType(boundTypes[ix], ix, idTypes);
      boundTypes[ix] = type;
      if (type instanceof ITypedObject) {
         return ((ITypedObject) type).getTypeDeclaration();
      }
      return type;
   }

   public Object getGenericType() {
      if (!isStarted()) {
         ParseUtil.initComponent(this);
         ParseUtil.startComponent(this);
      }

      List<IString> idents = getAllIdentifiers();
      Object res = getGenericTypeForIdentifier(idents.size()-1);
      if (res instanceof IBeanMapper)
         return ((IBeanMapper) res).getGenericType();
      return res;
   }

   /**
    * Make sure we always return the most up-to-date version of the type before we return it.  The special case is for
    * super exprs.  In that case, only skip deleted types.
    */
   static Object resolveType(Object type, int ix, IdentifierType[] idTypes) {
      if (type instanceof ITypeDeclaration) {
         boolean isSuper = idTypes[ix] == IdentifierType.SuperExpression;
         ITypeDeclaration itype = (ITypeDeclaration) type;
         if (itype instanceof BodyTypeDeclaration) {
            BodyTypeDeclaration btype = (BodyTypeDeclaration) itype;
            // Don't use modified replacedBy's here when we have a super expression - we mapped to a specific type
            // in that case.  We do still have to skip replaced types of course so we pass false when isSuper=true
            if (btype.replacedByType != null && (btype.replaced || isSuper))
               return btype.resolve(!isSuper);
         }
         else
            return itype.resolve(!isSuper);
      }
      return type;
   }

   private static Object getTypeContext(IdentifierType[] idTypes, Object[] boundTypes, int ix) {
      if (ix == 0)
         return null;
      switch (idTypes[ix-1]) {
         case VariableName:
         case FieldName:
         case GetVariable:
         case IsVariable:
         case GetSetMethodInvocation:
         case GetObjectMethodInvocation:
         case MethodInvocation:
         case RemoteMethodInvocation:
            return boundTypes[ix-1];// Super
         // SuperExpression at least should not go here.
      }
      return null;
   }

   public IdentifierType getIdentifierType(int ix) {
      if (idTypes == null)
         return IdentifierType.Unknown;
      return idTypes[ix];
   }

   public Object getTypeDeclaration() {
      List<IString> idents = getAllIdentifiers();
      int sz = idents.size();
      // During start, it is possible for the reference to be accessed before we are started
      if (!isStarted() && (boundTypes == null || boundTypes[sz-1] == null)) {
          // This does not start the arguments.  When initializing templates we want to be able to get the type of the method but not actually start references.
          // Otherwise, we have to do multiple passes so we can satisfy all page object names, then create the parts of the page objects that need to be resolved during the init
          resolveTypeReference();
          //start();
      }

      return getTypeForIdentifier(sz-1);
   }

   // We are treating pretty much any named reference as a possible recursive reference and so moving everything
   // till after the constructor.  This preserves the order of any potentially order dependent assignments.
   // We'll exclude simple method calls, but even simple variable references should move (?)
   public boolean isReferenceInitializer() {
      List<IString> idents = getAllIdentifiers();
      if (idents == null)
         return false;

      if (isStatic())
          return false;
      referenceInitializer = arguments == null || idents.size() > 1 || bindingDirection != null;
      return referenceInitializer;
   }

   public void stop() {
      // If we have not been officially started but our type reference has been assigned, we need to clear it here anyway
      //if (boundTypes == null && idTypes == null) return;

      // Need to complete the full stop() on this node and our base type checks 'started'
      if (!started)
         started = true;
      super.stop();
      
      boundTypes = null;
      idTypes = null;
   }

   public void reresolveTypeReference() {
      boundTypes = null;
      idTypes = null;

      resolveTypeReference();
   }

   public void setBindingInfo(BindingDirection dir, Statement dest, boolean nested) {
      super.setBindingInfo(dir, dest, nested);
      if (arguments != null) {
         for (Expression arg:arguments) {
            BindingDirection propBD;
            // Weird case:  convert reverse only bindings to a "none" binding for the arguments.
            // We do need to reflectively evaluate the parameters but do not listen on them like
            // in a forward binding.  If we propagate "REVERSE" down the chain, we get invalid errors
            // like when doing arithmetic in a reverse expr.
            if (bindingDirection != null && bindingDirection.doReverse())
               propBD = bindingDirection.doForward() ? BindingDirection.FORWARD : BindingDirection.NONE;
            else
               propBD = bindingDirection;
            arg.setBindingInfo(propBD, bindingStatement, true);
         }
      }
      if (dir != null && !canMakeBindable())
         displayError("Binding not allowed to expression: ");
   }

   public String getBindingTypeName() {
      IdentifierType idType = idTypes[0];
      List<IString> idents = getAllIdentifiers();

      // These guys skip the first binding so use the second slot to choose the type of the first binding.
      /*
      if (idType == IdentifierType.BoundObjectName || idType == IdentifierType.BoundTypeName && identifiers.size() > 1)
         idType = idTypes[1];
      */

      // Need to skip all PackageNames and prefix types before selecting the binding type
      int i = 0;
      while ((idType == IdentifierType.PackageName || idType == IdentifierType.BoundTypeName) && i < idents.size()-1)
         idType = idTypes[++i];

      Object boundType = boundTypes[i];

      return idType == IdentifierType.MethodInvocation || idType == IdentifierType.RemoteMethodInvocation ?
              (nestedBinding ? "methodP" : "method") :
              idType == IdentifierType.EnumName || isFinal(boundType)  ?
                   (nestedBinding ? "constantP" : "constant") :
                   (nestedBinding ? "bindP" : "bind");
   }

   /** TODO: should we optimize final fields?
    * There are a bit like EnumName which means finding all of the places in
    * transformBindingArgs and evalBindingArgs that treat EnumName specially and doing something similar for fields
    * Started on this path because sometimes an enum constant gets mapped into the field found in reflection.  We needed to map this case back to
    * the runtime enum, etc but then that case turned into EnumName in the identifier expression.
    */
   private boolean isFinal(Object boundType) {
      return false;
      /*
      if (boundType == null)
         return false;
      // This is a nice optimization in any case but one weird thing is that in the compiled model, we get a bean mapper with a final field
      // for each enum constant.  Being careful here not to consider a final method as constant as that is something different.
      if (ModelUtil.isField(boundType))
         return ModelUtil.hasModifier(boundType, "final");
      return false;
      */
   }

   static Object getCurrentThisType(Expression expr, Object srcType, ExecutionContext ctx) {
      Object srcObj = ctx.getCurrentObject();
      if (srcObj == null)
         return null;

      // Do we start out here with the current object's type or the current expression's enclosing type?
      Object srcObjType = DynUtil.getType(srcObj);

      int numInstLevels = ModelUtil.getNumInnerTypeLevels(srcObjType);
      int numRefLevels = ModelUtil.getNumInnerTypeLevels(srcType);

      while (numInstLevels > numRefLevels && srcObj != null) {
         Object outerObj = getOuterInstance(ctx, srcObj);
         srcObj = outerObj;
         numInstLevels--;
      }
      return srcObj;
   }

   static Object getOuterInstance(ExecutionContext ctx, Object srcObj) {
      Object outerObj = null;
      if (ctx.system != null) {
         outerObj = ctx.system.getOuterInstance(srcObj);
      }
      if (outerObj == null)
         outerObj = DynObject.getParentInstance(srcObj);
      return outerObj;
   }

   /** When your first identifier is a field, we need to figure out what the "this" is.
    *  It could be an outer class or it could be static - hence the class is what we want.
    */
   static Object getRootFieldThis(Expression expr, Object thisType, ExecutionContext ctx, boolean absolute) {
      ITypeDeclaration type = expr.getEnclosingIType();
      Object srcType;
      Object srcObj;

      Object srcField = thisType;
      if (srcField instanceof VariableDefinition) {
         srcField = ((VariableDefinition) srcField).getDefinition();
      }
      if (srcField instanceof IVariable || srcField instanceof FieldDefinition)
         srcType = ModelUtil.getEnclosingType(srcField);
      else if (srcField instanceof TypeDeclaration)
         srcType = ((TypeDeclaration) srcField).getEnclosingType();
      else
         srcType = ModelUtil.getEnclosingType(srcField);
      if (ModelUtil.hasModifier(srcField, "static")) {
         Object staticType;
         if ((staticType = ctx.getCurrentStaticType()) != null) {
            if (ModelUtil.isAssignableFrom(srcType, staticType))
               return staticType;

            Object pType = staticType;
            while (pType != null && !ModelUtil.isAssignableFrom(srcType, pType)) {
               pType = ModelUtil.getEnclosingType(pType);
            }
            // Adding this to deal with a case where we optimized out an inner class.
            if (pType == null)
               pType = srcType; // Why is it ever anything different?
            if (pType != srcType)
               System.out.println("*** Not returning the field's static type");
            srcObj = pType;
         }
         else
            srcObj = ctx.resolveName(ModelUtil.getTypeName(srcType));
      }
      else {
         /**
          * When we have a Foo.this.x reference to something we need to walk up a specific number of type levels - not just use the first type we
          * find like if we called 'toString()' - in that case, it's the first match which wins.
          */
         if (absolute)
            return getCurrentThisType(expr, srcType, ctx);

         srcObj = ctx.getCurrentObject();
         if (srcObj == null)
            return null;
         // Do we start out here with the current object's type or the current expression's enclosing type?
         Object pType = DynUtil.getType(srcObj);

         // First check if the object's type is the same as identiier's type.  If so we just return the object.
         if (ModelUtil.isAssignableFrom(srcType, pType))
            return srcObj;

         // Now check if the enclosing type of the identifier expression is the same.  I'm not sure why these
         // two tests are not always the same... in some cases the TemplateDeclaration seems to be messing things
         // up as it is considered a type but is not a formal inner type.
         pType = type;

         while (pType != null && !ModelUtil.isAssignableFrom(srcType, pType)) {
            pType = ModelUtil.getEnclosingType(pType);
            Object outerObj = null;
            if (pType != null) {
               // This inner loop is here because we may need to skip more than one level in the
               // object's instance type hierarchy to reach the enclosing instance for the field.  This is the case
               // where you have a class which extends an inner class from the same parent e.g.

               //  class outer {
               //      int someType;
               //      class inner1 {
               //         int ref := someType;
               //      }
               //      class inner2 {
               //         class inner3 extends inner1 {
               //
               //         }
               //      }
               //  }
               //  an instance of inner3 needs to go up two levels to get to "outer" even though it is only
               //  one level removed from where the field is defined.
               do {
                  if (srcObj == null)
                     return null;
                  if (ctx.system != null) {
                     outerObj = ctx.system.getOuterInstance(srcObj);
                  }
                  if (outerObj == null)
                     outerObj = DynObject.getParentInstance(srcObj);
                  srcObj = outerObj;
               } while (srcObj != null && !ModelUtil.isAssignableFrom(srcType,  pType = DynUtil.getType(srcObj)));
            }
         }
      }
      return srcObj;
   }

   static Object getRootMethodThis(Expression expr, Object thisType, ExecutionContext ctx) {
      ITypeDeclaration type = expr.getEnclosingIType();
      Object srcType;
      Object srcObj;

      srcType = ModelUtil.getEnclosingType(thisType);

      if (ModelUtil.hasModifier(thisType, "static")) {
         srcObj = ModelUtil.getEnclosingType(thisType);
      }
      else {
         // TODO: replace with getCurrentThisType call
         srcObj = ctx.getCurrentObject();
         Object pType = type;

         while (pType != null && !ModelUtil.isAssignableFrom(srcType, pType)) {
            pType = ModelUtil.getEnclosingType(pType);
            if (pType != null) {
               Object outerObj = getOuterInstance(ctx, srcObj);
               srcObj = outerObj;
            }
         }
      }
      return srcObj;
   }

   /** Determines whether the first part of an object reference is part of a "this" expression or not */
   private boolean isThisObjectReference() {
      assert idTypes[0] == IdentifierType.BoundObjectName;
      Object enclType = ModelUtil.getEnclosingType(boundTypes[0]);
      return enclType != null && !ModelUtil.hasModifier(boundTypes[0], "static") && !(enclType instanceof Template);
   }

   /*
    * Retrieves the value of a top level object reference.  Could be static or a property of one of the objects
    * on the "this" stack of the current definition.
    */
   private Object evalRootObjectValue(ExecutionContext ctx) {
      Object enclType = ModelUtil.getEnclosingType(boundTypes[0]);
      if (!isThisObjectReference()) {
         return ctx.resolveName(ModelUtil.getTypeName(boundTypes[0]));
      }
      else {
         return evalThisReference(ModelUtil.getRuntimeType(enclType), ctx);
      }
   }

   private Object evalThisReference(Object enclType, ExecutionContext ctx) {
      Object thisObj = ctx.findThisType(enclType);
      if (thisObj == null)
         return null;

      if (ModelUtil.isAssignableFrom(enclType, thisObj.getClass())) {
         String typeName = CTypeUtil.getClassName(ModelUtil.getTypeName(boundTypes[0]));
         return DynUtil.getPropertyValue(thisObj, typeName);
      }
      return null;
   }

   public void evalBindingArgs(List<Object> bindArgs, boolean isStatic, Class expectedType, ExecutionContext ctx) {
      int startPropertyIndex = 0;      // The first identifier which is a bound property.
      List<IString> idents = getAllIdentifiers();
      Object srcObj;
      // The srcObj parameter
      switch (idTypes[0]) {
         case VariableName:
            System.err.println("*** Invalid binding to a variable: " + idents.get(0).toString() + " bindings can only refer to bindable types, fields and expressions: " + toDefinitionString());
            return;
         case IsVariable:
         case GetVariable:
         case FieldName:
         case GetSetMethodInvocation:
            srcObj = getRootFieldThis(this, boundTypes[0], ctx, false);
            if (srcObj == null) {
               System.err.println("*** Unable to resolve root property for: " + toDefinitionString());
               return;
            }
            break;
         case BoundTypeName:
            if (idents.size() == 1 || (idTypes[1] != IdentifierType.MethodInvocation && idTypes[1] != IdentifierType.RemoteMethodInvocation)) {
               srcObj = ModelUtil.getRuntimeType(boundTypes[0]);
               startPropertyIndex = 1;// Consumes the first name
               if (srcObj == null) {
                  System.err.println("*** Unable to resolve type of binding expression for: " + toDefinitionString());
                  return;
               }
            }
            else {
               srcObj = null;
            }
            break;

         case BoundObjectName:
            //srcObj = ctx.resolveName(ModelUtil.getTypeName(boundTypes[0]));
            //srcObj = evalRootObjectValue(ctx);

            // If there's no enclosing type for our resolved object, it must be a global object reference, not
            // relative to "this".  So we just resolve the type name.
            if (boundTypes[0] != null && ModelUtil.getEnclosingType(boundTypes[0]) == null) {
               srcObj = ctx.resolveName(ModelUtil.getTypeName(boundTypes[0]));
               if (srcObj == null)
                  System.out.println("*** Can't resolve object instance for binding expression");
               startPropertyIndex = 1;
            }
            else
               srcObj = getRootFieldThis(this, boundTypes[0], ctx, false);
            if (srcObj == null) {
               System.err.println("*** Unable to resolve root obj for: " + toDefinitionString());
               return;
            }
            break;
         case UnboundName:
            // We try to bind here even to a type so that command completion works
            Object val = ctx.resolveUnboundName(idents.get(0).toString());
            bindArgs.add(val);
            bindArgs.add(new IBinding[] {new ConstantBinding(val)});
            return;
         case PackageName:
            // create an identifier expression from the entire path
            int pi;
            srcObj = null;
            for (pi = 1; pi < idents.size(); pi++) {
               if (idTypes[pi] != IdentifierType.PackageName) {
                  if (idTypes[pi] == IdentifierType.BoundTypeName) {
                     startPropertyIndex = pi+1;
                     if (pi == idents.size()-1 || (idTypes[pi+1] != IdentifierType.MethodInvocation && idTypes[pi+1] != IdentifierType.RemoteMethodInvocation))
                        srcObj = ctx.resolveName(getIdentifierPathName(startPropertyIndex));
                     else {
                        srcObj = null;
                     }
                  }
                  else {
                     startPropertyIndex = pi;
                     srcObj = ctx.resolveName(getIdentifierPathName(pi+1));
                  }
                  break;
               }
            }
            if (pi == idents.size()) {
               System.err.println("*** Error - can't find type for binding expression: " + toDefinitionString());
               return;
            }
            break;
         case MethodInvocation:
         case RemoteMethodInvocation:
         case GetObjectMethodInvocation:
            srcObj = null;
            break;
         case ThisExpression:
            srcObj = ctx.getCurrentObject();
            break;
         default:
            System.err.println("*** Error: invalid binding expression: " + this);
            return;
      }
      if (srcObj != null) {
         bindArgs.add(srcObj);

         List<IBinding> props = new ArrayList<IBinding>(idents.size());

         int last = idents.size()-offset();
         Object lastType = srcObj;
         for (int i = startPropertyIndex; i <= last; i++) {
            if (idTypes[i] == null) {
               bindArgs.remove(srcObj);
               return;
            }
            switch (idTypes[i]) {
               case UnboundName:
                  bindArgs.remove(srcObj);
                  return;
               case ThisExpression:
                  continue;
            }

            if (i == last && arguments != null) {
               Object rtMeth = ModelUtil.getRuntimeMethod(boundTypes[last]);
               IBinding result = Bind.methodP(rtMeth, evalBindingParameters(expectedType, ctx, arguments.toArray(new Expression[arguments.size()])));
               if (result == null) {
                  System.err.println("*** Unable to resolve method binding for: " + this);
               }
               props.add(result);
            }
            else {
               while (i < idents.size() - 1 && idTypes[i] == IdentifierType.BoundTypeName) {
                  i++;
                  //props.add(new ConstantBinding(boundTypes[i]));
               }

               if (idTypes[i] == IdentifierType.BoundTypeName)
                  props.add(new ConstantBinding(boundTypes[i]));
               else if (idTypes[i] == IdentifierType.EnumName) {
                  if (i == last) {
                     // A.EnumVal is just a constant binding so just put the runtime enum value in as the arg.
                     bindArgs.clear();
                     bindArgs.add(ModelUtil.getRuntimeEnum(boundTypes[i]));
                     return;
                  }
                  else
                     props.add(new ConstantBinding(ModelUtil.getRuntimeEnum(boundTypes[i])));
               }
               else {
                  IBeanMapper result;
                  if (boundTypes[i] == ArrayTypeDeclaration.LENGTH_FIELD)
                     result = ArrayLengthBeanMapper.INSTANCE;
                  else {
                     result = DynUtil.getPropertyMapping(ModelUtil.resolve(ModelUtil.getEnclosingType(boundTypes[i]), true), idents.get(i).toString());
                  }
                  if (result == null) {
                     System.err.println("*** Unable to resolve property mapping for: " + this);
                     result = DynUtil.getPropertyMapping(ModelUtil.getEnclosingType(boundTypes[i]), idents.get(i).toString());
                  }
                  // TODO: Need to identify and mark properties as constant here?
                  props.add(result);
               }
            }
         }

         bindArgs.add(props.toArray(new IBinding[props.size()]));
      }
      /* This is a method binding... add parameters for the call to get the method and the nested argument bindings */
      else {
         Object rtMeth, methType = boundTypes[idents.size()-offset()];
         if (methType == null)  // Failed to resolve
            bindArgs.add(null);
         else {
            rtMeth = ModelUtil.getRuntimeMethod(methType);
            Object methEnclType = ModelUtil.getEnclosingType(rtMeth);
            if (!isStatic && !ModelUtil.hasModifier(methType, "static")) {
               bindArgs.add(ctx.findThisType(methEnclType));
            }
            bindArgs.add(rtMeth);
            bindArgs.add(evalBindingParameters(expectedType, ctx, arguments.toArray(new Expression[arguments.size()])));
            if (rtMeth == null) {
               System.err.println("*** No runtime method for type: " + methType);
            }
         }
      }
   }

   /**
    * From "a.b.c" to:
    *    Bind.bind(<enclosingObject or class>, boundFieldName,
    */
   public void transformBindingArgs(SemanticNodeList<Expression> bindArgs, BindDescriptor bd) {
      int startPropertyIndex = 0;      // The first identifier which is a bound property.

      int firstProp = 0;

      // Strip off the first identifier if it is the object prefix and we are a nested binding.  The source object
      // for the binding will already be the thisObjectPrefix so we don't need the extra level of indirection (and it
      // messes things up)
      /*
      if (identifiers.size() > 1 && bd.thisObjectPrefix.equals(identifiers.get(0).toString()) && nestedBinding) {
         firstProp = 1;
         startPropertyIndex = 1;
      }
      */

      List<IString> idents = getAllIdentifiers();
      Expression srcObj;
      Object srcType;
      TypeDeclaration type = getEnclosingType();
      // The srcObj parameter
      switch (idTypes[firstProp]) {
         case VariableName:
            System.err.println("*** Invalid binding to a variable: " + idents.get(0).toString() + " bindings can only refer to bindable types, fields and expressions: " + toDefinitionString());
            return;
         case IsVariable:
         case GetVariable:
         case SetVariable:
         case FieldName:
         case GetSetMethodInvocation:
            Object srcField = boundTypes[firstProp];
            if (srcField instanceof VariableDefinition) {
               srcField = ((VariableDefinition) srcField).getDefinition();
            }
            if (srcField instanceof ParamTypedMember) {
               srcField = ((ParamTypedMember) srcField).getMemberObject();
               if (srcField instanceof ParamTypedMember)
                  System.out.println("*** Error - nested param typed member!");
            }
            if (srcField instanceof IVariable || srcField instanceof FieldDefinition)
               srcType = ModelUtil.getEnclosingType(srcField);
            else
               srcType = RTypeUtil.getPropertyDeclaringClass(srcField);
            if (ModelUtil.hasModifier(srcField, "static")) {
               srcObj = ClassValueExpression.create(ModelUtil.getTypeName(srcType));
            }
            else {
               // TODO: need to get the base class relative to "type" from which we lookup srcType
               if (ModelUtil.isAssignableFrom(srcType, type)) {
                  srcObj = IdentifierExpression.create("this");
                  srcType = type;
               }
               // Must be an enclosing type for us to refer to a field with a single name so just need to qualify which "this"
               else {
                  TypeDeclaration pType = type.getEnclosingType();
                  while (pType != null && !ModelUtil.isAssignableFrom(srcType, pType)) {
                     pType = pType.getEnclosingType();
                  }
                  if (pType != null)
                     srcType = pType;
                  srcObj = IdentifierExpression.create(ModelUtil.getTypeName(srcType), "this");
               }
            }
            break;
         case BoundTypeName:
            int typeIx = 1;
            while (typeIx < idents.size() && idTypes[typeIx] == IdentifierType.BoundTypeName)
                 typeIx++;
            // Type.Type.Enum - do an IdentifierExpression for that enum.
            if (typeIx < idents.size() && idTypes[typeIx] == IdentifierType.EnumName) {
               if (ModelUtil.isField(boundTypes[typeIx]))
                  srcObj = IdentifierExpression.create(ModelUtil.getRuntimeTypeName(boundTypes[0]) + "." + ModelUtil.getPropertyName(boundTypes[typeIx]));
               else
                  srcObj = IdentifierExpression.create(ModelUtil.getRuntimeTypeName(boundTypes[typeIx]));
               srcType = null;
               typeIx++;
               // ConstantBinding
               if (typeIx == idents.size()) {
                  bindArgs.add(srcObj);
                  return;
               }
            }
            // If it is an expression based off of the class itself, do a class value expression
            else if (idents.size() == typeIx || (idTypes[typeIx] != IdentifierType.MethodInvocation && idTypes[typeIx] != IdentifierType.RemoteMethodInvocation)) {
               // TODO: Shouldn't we use identifiers.get(0) as below?
               srcObj = ClassValueExpression.create(ModelUtil.getTypeName(srcType = boundTypes[0]));
            }
            // Method call - the method definition will have the type in it so no need to pass these on.
            else {
               srcObj = null;
               srcType = null;
            }
            startPropertyIndex = typeIx; // Consumes all of the BoundTypeName components
            break;
         case BoundObjectName:
         case EnumName:
            srcType = boundTypes[0];
            srcObj = IdentifierExpression.create(idents.get(0).toString());
            // ConstantBinding
            if (idTypes[0] == IdentifierType.EnumName && idents.size() == 1) {
               bindArgs.add(srcObj);
               return;
            }
            startPropertyIndex = 1;
            break;
         case UnboundName:
            System.err.println("*** Error - can't find type for binding expression: " + this);
            return;
         case PackageName:
            // create an identifier expression from the entire path
            int pi;
            srcType = null;
            srcObj = null;
            for (pi = 1; pi < idents.size(); pi++) {
               if (idTypes[pi] != IdentifierType.PackageName) {
                  srcType = boundTypes[pi];
                  // When we are in a "this" or "super" expr just evaluate out to the original expr
                  if (idTypes[pi] == IdentifierType.BoundTypeName && !isThisExpression() && !isSuperExpression()) {

                     // Might have many sub-types in here so skip to the last one
                     while (pi < idents.size()-1 && idTypes[pi+1] == IdentifierType.BoundTypeName) {
                        pi = pi+1;
                        srcType = boundTypes[pi];
                     }

                     startPropertyIndex = pi+1;
                     if (pi == idents.size()-1 || (idTypes[pi+1] != IdentifierType.MethodInvocation && idTypes[pi+1] != IdentifierType.RemoteMethodInvocation))
                        srcObj = ClassValueExpression.create(ModelUtil.getTypeName(srcType));
                     else {
                        srcObj = null;
                        srcType = null;
                     }
                  }
                  else {
                     if (isThisExpression() || isSuperExpression()) {
                        srcObj = IdentifierExpression.create(getIdentifierPathName(idents.size()));
                        startPropertyIndex = idents.size();
                     }
                     else {
                        startPropertyIndex = pi;
                        srcObj = IdentifierExpression.create(getQualifiedClassIdentifier());
                     }
                  }
                  break;
               }
            }
            if (pi == idents.size()) {
               System.err.println("*** Error - can't find type for binding expression: " + toDefinitionString());
               return;
            }
            break;
         case MethodInvocation:
         case RemoteMethodInvocation:
         case GetObjectMethodInvocation:
            srcObj = null;
            srcType = null;
            break;
         case ThisExpression:
            srcObj = IdentifierExpression.create("this");
            srcType = boundTypes[0];
            startPropertyIndex = 1;
            break;
         case SuperExpression:
            // Should have generated an error earlier
            return;
         default:
            System.err.println("*** Error: invalid binding expression: " + this);
            return;
      }
      if (srcObj != null) {

         bindArgs.add(srcObj);

         SemanticNodeList<Expression> props = new SemanticNodeList<Expression>(idents.size());

         Object lastObj = srcType;
         for (int i = startPropertyIndex; i < idents.size(); i++) {
            if (idTypes[i] == null) {
               System.err.println("*** Data binding to dynamic types not yet implemented " + idents.get(i) + " not bound to a type for: " + toDefinitionString());
               bindArgs.remove(srcObj);
               return;
            }
            switch (idTypes[i]) {
               case UnboundName:
                  System.err.println("*** Data binding to dynamic types not yet implemented " + idents.get(i) + " not bound to a type for: " + toDefinitionString());
                  bindArgs.remove(srcObj);
                  return;
               case EnumName:
                  System.err.println("*** data binding not implemented for enum expression");
                  continue;
            }

            String identifier;
            Object varType = boundTypes[i];
            if (varType instanceof VariableDefinition)
               identifier = ((VariableDefinition) varType).getRealVariableName();
            else
               identifier = idents.get(i).toString();
            if (i == idents.size()-1 && arguments != null) {
               // Bind.methodP(getMethod call, arguments converted to IBinding[])
               props.add(createChildMethodBinding(getTypeForIdentifier(i-1), identifier, boundTypes[i], arguments, idTypes[i] == IdentifierType.RemoteMethodInvocation));
            }
            else {
               props.add(createGetPropertyMappingCall(lastObj, identifier));
               lastObj = boundTypes[i];
            }
         }
         if (bd.isSetAndReturnLHS) {
            if (props.size() > 1)
               System.err.println("*** Can't handle complex identifier expressions for setAndReturn");
            bindArgs.add(props.get(0));
         }
         else
            bindArgs.add(createBindingArray(props, true));
      }
      /* This is a method binding... add parameters for the call to get the method and the nested argument bindings */
      else {
         Expression methodClass;
         Object methodDeclaringClass;
         int ix = idents.size()-1;
         Object methodType = boundTypes[ix];
         boolean isRemote = idTypes[ix] == IdentifierType.RemoteMethodInvocation;
         if (idents.size() == 1) {

            if (methodType == null) {
               System.err.println("*** Bound expression - type not defined: " + toDefinitionString());
               return;
            }

            methodDeclaringClass = ModelUtil.getMethodDeclaringClass(boundTypes[0]);

            // If either the field or the method is static, use the class directly
            if (!bd.isStatic && !ModelUtil.hasModifier(methodType, "static")) {
               TypeDeclaration enclType = getEnclosingType();
               if (ModelUtil.isAssignableFrom(methodDeclaringClass, enclType)) {
                  methodClass = IdentifierExpression.create("getClass");
                  bindArgs.add(IdentifierExpression.create("this"));
               }
               // Method is defined in an enclosing class - need to prefix with the type and add a new argument
               // as the method lives on a different object than the destination property
               else {
                  // This type name must be in this type hierarchy - can't be using a base-classes type name here so walk up until we find the
                  // right type and use that name.
                  TypeDeclaration outerType = enclType.getEnclosingType();
                  do {
                     if (outerType == null || ModelUtil.isAssignableFrom(methodDeclaringClass, outerType)) {
                        break;
                     }
                     outerType = outerType.getEnclosingType();
                  } while (outerType != null);
                  if (outerType == null) {
                     System.err.println("*** Unable to find declaring class: " + methodDeclaringClass + " in enclosing type hierarchy");
                  }
                  String methodClassName = ModelUtil.getTypeName(outerType);
                  methodClass = IdentifierExpression.create(methodClassName, "this", "getClass");
                  bindArgs.add(IdentifierExpression.create(methodClassName, "this"));
               }
               methodClass.setProperty("arguments", new SemanticNodeList<Expression>());
            }
            else {
               if (ModelUtil.isDynamicType(methodDeclaringClass))
                  methodClass = StringLiteral.create(ModelUtil.getRuntimeTypeName(methodDeclaringClass));
               else
                  methodClass = ClassValueExpression.create(ModelUtil.getTypeName(methodDeclaringClass));
            }
         }
         else {
            methodDeclaringClass = getTypeForIdentifier(idents.size()-2);
            if (ModelUtil.isDynamicType(methodDeclaringClass))
               methodClass = StringLiteral.create(ModelUtil.getRuntimeTypeName(methodDeclaringClass));
            else
               methodClass = ClassValueExpression.create(getMethodTypeName());
         }

         IdentifierExpression getMethod = createGetMethodExpression(methodClass, methodDeclaringClass,
                                                      idents.get(idents.size()-1).toString(),
                                                      methodType, arguments, isRemote);
         bindArgs.add(getMethod);
         bindArgs.add(createBindingArray(arguments, false));
      }
   }

   private String getMethodTypeName() {
      StringBuffer name = new StringBuffer();
      List<IString> idents = getAllIdentifiers();
      for (int i = 0; i < idents.size()-1; i++) {
         if (i != 0) name.append('.');
         name.append(idents.get(i).toString());
      }
      return name.toString();
   }

   public void changeExpressionsThis(TypeDeclaration oldThis, TypeDeclaration outerType, String newThisPrefix) {
      List<IString> idents = getAllIdentifiers();
      switch (idTypes[0]) {
         case FieldName:
         case GetVariable:
         case IsVariable:
         case MethodInvocation:
         case RemoteMethodInvocation:
            // If this field is not a direct child of the type, do not rewrite
            if (boundTypes[0] != null && ModelUtil.isAssignableFrom(ModelUtil.getEnclosingType(boundTypes[0]), oldThis)) {
               idents.add(0, PString.toIString(newThisPrefix));
               ParseUtil.restartComponent(this);
            }
            break;
         // Not sure this happens but just in case
         case ThisExpression:
            idents.set(0, PString.toIString(newThisPrefix));
            ParseUtil.restartComponent(this);
            break;
         case BoundTypeName:
            Object enclType = ModelUtil.getEnclosingType(boundTypes[0]);
            // If we've moved into a different file altogether, we need to rewrite all relative types as absolute ones
            // Also, if we resolved the value through the enclosing type we just removed, we also have to rewrite as absolute.
            if (!ModelUtil.sameTypes(ModelUtil.getRootType(oldThis), ModelUtil.getRootType(outerType)) || (enclType != null && ModelUtil.isAssignableFrom(enclType,  oldThis))) {
               Object thisType = boundTypes[0];
               // We have "className.this" but are moving this to a new type that extends the existing one.  Need to find
               // the enclosing type of outer which implements boundType and use that's type name.
               if (isThisExpression()) {
                  do {
                     if (!ModelUtil.isAssignableFrom(thisType, outerType)) {
                        outerType = outerType.getEnclosingType();
                     }
                     else
                        break;
                  } while (outerType != null);
                  if (outerType == null) {
                     System.out.println("*** Can't find enclosing type to move: " + ModelUtil.getTypeName(boundTypes[0]) + " in the new outer type: " + outerType);
                     return;
                  }
                  else
                     thisType = outerType;
               }

               String fullType = ModelUtil.getTypeName(thisType);
               String[] typesToAdd = StringUtil.split(fullType, '.');
               int i;
               for (i = 0; i < typesToAdd.length-1; i++) {
                  idents.add(i, PString.toIString(typesToAdd[i]));
               }
               // Reset the last guy in case we changed the type altogether for the this
               idents.set(i, PString.toIString(typesToAdd[i]));

               if (typesToAdd.length > 1)
                  ParseUtil.restartComponent(this);
            }
            break;
      }
      if (arguments != null) {
         for (int i = 0; i < arguments.size(); i++)
            arguments.get(i).changeExpressionsThis(oldThis, outerType, newThisPrefix);
      }
   }

   public String addNodeCompletions(JavaModel origModel, JavaSemanticNode origNode, String extMatchPrefix, int offset, String dummyIdentifier, Set<String> candidates) {
      List<IString> idents = getAllIdentifiers();
      if (idents == null)
         return null;

      IdentifierExpression origIdent = origNode instanceof IdentifierExpression ? (IdentifierExpression) origNode : null;
      // The origIdent inside of an Element tag will not have been started, but the replacedByStatement which represents in the objects is started
      if (origIdent != null && origIdent.replacedByStatement instanceof IdentifierExpression)
         origIdent = (IdentifierExpression) origIdent.replacedByStatement;

      int i = 0;
      for (IString ident:idents) {
         String identStr = ident.toString();
         int dummyIx = identStr.indexOf(dummyIdentifier);
         if (dummyIx != -1) {
            String matchPrefix = identStr.substring(0, dummyIx);

            Object curType = origNode == null ? origModel == null ? null : origModel.getModelTypeDeclaration() : origNode.getEnclosingType();
            if (origNode != null && curType == null)
               curType = origModel == null ? origModel.getModelTypeDeclaration() : null;

            if (origIdent != null && !origIdent.isStarted())
               ParseUtil.initAndStartComponent(origIdent);

            if (origIdent != null && i > 0 && origIdent.boundTypes != null && origIdent.boundTypes.length >= i) {
               curType = origIdent.getTypeForIdentifier(i-1);
               if (curType == null) // If we can't resolve the type for the previous we should restrict it to a type - not show global names
                  curType = Object.class;
            }

            boolean includeGlobals = idents.size() == 1;
            if (origModel != null) {
               if (curType != null)
                  ModelUtil.suggestMembers(origModel, curType, matchPrefix, candidates, includeGlobals, true, true, false);
               else if (origModel != null) {
                  ModelUtil.suggestTypes(origModel, origModel.getPackagePrefix(), matchPrefix, candidates, includeGlobals);
               }
            }

            IBlockStatement enclBlock = getEnclosingBlockStatement();
            if (enclBlock != null && i == 0)
               ModelUtil.suggestVariables(enclBlock, matchPrefix, candidates);

            return matchPrefix;
         }
         i++;
      }
      return null;
   }

   public int suggestCompletions(String prefix, Object currentType, ExecutionContext ctx, String command, int cursor, Set<String> candidates, Object continuation) {
      List<IString> idents = getAllIdentifiers();
      if (idents == null)
         return -1;

      if (arguments != null) {
         if (arguments.size() > 0) {
            // TODO: fill in the expected type here based on the argument type?
            return arguments.get(arguments.size()-1).suggestCompletions(prefix, currentType, ctx, command, cursor, candidates, continuation);
         }
         return -1;
      }

      Object obj;

      int idSize = idents.size();

      //boolean emptyDotName = continuation != null && continuation instanceof Boolean;
      IString lastIdentIstr = idents.get(idSize-1);
      boolean emptyDotName = lastIdentIstr == null || lastIdentIstr.toString().length() == 0;

      try {
         obj = emptyDotName ? eval(null, ctx) : evalRootValue(ctx);
      }
      catch (RuntimeException exc) {
         // Just won't show completions against this object if we don't resolve but try falling back to the type
         obj = null;

         // For "a.b" we complete 'b' using the type of a
         if (idSize > 1) {
            if (boundTypes != null)
               obj = getTypeForIdentifier(idSize-2);
         }
      }

      if (obj == null && idSize == 1) {
         obj = ctx.getCurrentObject();
      }

      if (obj == null) {
         if (idSize == 1 || boundTypes == null)
            obj = currentType;
         else {
            obj = getTypeForIdentifier(idSize-2);
         }
      }

      if (!(obj instanceof Class) && !(obj instanceof ITypeDeclaration)) {
         if (obj != null)
            obj = DynUtil.getType(obj);
      }

      String lastIdent = idSize == 0 ? "" : idents.get(idSize-1).toString();
      // We don't end up with an identifier for the "foo." case.  Just look for all under foo in that case.
      int pos = -1;

      if (lastIdent.equals("")) {
         pos = parseNode.getStartIndex() + parseNode.length();
      }
      else {
         if (parseNode != null) {
            pos = parseNode.getStartIndex();
            if (pos != -1)
               pos += parseNode.lastIndexOf(lastIdent);
         }

         if (pos == -1)
            pos = command.lastIndexOf(lastIdent);
      }

      JavaModel model = getJavaModel();
      if (model == null)
         return pos;
      boolean includeGlobals = idSize == 1 && !emptyDotName;
      if (obj != null)
         ModelUtil.suggestMembers(model, obj, lastIdent, candidates, includeGlobals, true, true, false);
      else {
         ModelUtil.suggestTypes(model, prefix, lastIdent, candidates, includeGlobals);
      }

      IBlockStatement enclBlock = getEnclosingBlockStatement();
      if (enclBlock != null)
         ModelUtil.suggestVariables(enclBlock, lastIdent, candidates);

      return pos;
   }

   public boolean applyPartialValue(Object value) {
      List<IString> idents = getAllIdentifiers();

      // Handles the trailing "." case by just adding an empty identifier 
      if (value instanceof VariableSelector) {
         String otherName = ((VariableSelector) value).identifier;
         if (otherName == null)
            otherName = "";
         idents.add(PString.toIString(otherName));
         return true;
      }
      
      if (value instanceof IdentifierExpression) {
         IdentifierExpression partial = (IdentifierExpression) value;
         if (arguments == null && partial.arguments != null) {
            setProperty("arguments", partial.arguments);
            return true;
         }
         if (partial.identifiers != null) {
            if (partial.identifiers != idents) {
               if (idents == null) {
                  setProperty("identifiers", partial.identifiers);
               }
               else
                  identifiers.addAll(partial.identifiers);
            }
            return true;
         }
      }
      if (value instanceof SemanticNodeList) {
         if (value == arguments)
            return true;
         else {
            SemanticNodeList valList = (SemanticNodeList) value;
            boolean isString = true;
            for (Object elem:valList) {
               if (!PString.isString(elem))
                  isString = false;
            }
            if (isString) {
               if (idents == null) {
                  /* Not sure we need this case and it was getting hit to extend TypedMethodExpression before
                    we overrode it there to be sure we at least matched the typed identifer.
                  setProperty("identifiers", valList);
                  return true;
                  */
               }
               else {
                  int i;
                  for (i = 0; i < idents.size(); i++) {
                     IString ident = idents.get(i);
                     if (valList.size() <= i)
                        return false;
                     if (ident == null || !ident.equals(valList.get(i)))
                        return false;
                  }
                  boolean any = false;
                  for (; i < valList.size(); i++) {
                     idents.add(PString.toIString(valList.get(i)));
                     any = true;
                  }
                  return any;
               }
            }
         }
      }
      return false;
   }

   /**
    * Styling for identifier expressions is complicated because the information we need to do the styling is
    * at this level, but the elements we are styling are buried down in the parse-tree of this node.  So far
    * this is the only problematic case for Java so we are living with it but adding some features at the
    * parselets level would help.
    *
    * One problem is that the parseNode for the IdentifierExpression may be expression, optExpression or exprStatement.
    * We optionally need to 'drill down' to get the parse-node for the IdentifierExpression, then wrap the output with
    * any parse nodes we skipped before or after.  To handle this at the parselet level, we could skip those levels
    * automatically.
    *
    * Another problem is that the path name is split into the first name, then "." followed by each subsequent name
    * so there's an extra bump in the hierarchy to deal with the second and subsequent path-names.
    *
    * We could add a method called - childStyleHandler which is called for each child parse node that produces an array
    * value (in this case, the identifiers array).  It would return null for parse nodes which are ignored in styling
    * or 'member' or 'staticMember' as necessary for each segment in the identifiers array.  That method would
    * be passed the current parent parse node, the child-index, and the current parse-node so it could go back into the identifier expression and find the right style for that segment
    * of the path-name.  It could probaby just unmap the value array and deal with the hierarchy skip so maybe we don't need
    * that much context in that method.
    */
   public void styleNode(IStyleAdapter adapter) {
      if (idTypes == null) {
         // We've been replaced and not started - presumably this is just a copy of our statement so we can style it using the
         // replacedByStatement's information - just need to use this parse node since it's the original one in the source.
         if (replacedByStatement instanceof IdentifierExpression) {
            IdentifierExpression replaced = (IdentifierExpression) replacedByStatement;
            // Currently the Element does not always start it's children expressions... instead the root is started.  Here we could either find
            // the active element that corresponds to this expression and use it for styling or just start this here.  This will require all language
            // elements to be able to resolve their value without being converted so long term maybe the other approach is easier?
            if (!replaced.isStarted())
               ParseUtil.realInitAndStartComponent(replaced);
            replaced.styleExpression((ParentParseNode) parseNode, adapter);
         }
         else
            super.styleNode(adapter);
         return;
      }

      List<IString> idents = getAllIdentifiers();
      int sz = idents.size();

      styleExpression((ParentParseNode) parseNode, adapter);
   }

   private void styleExpression(ParentParseNode parseNode, IStyleAdapter adapter) {
      //String rep = ParseUtil.styleParseNode(parseNode);
      ParentParseNode pp = (ParentParseNode) parseNode;
      Parselet topParselet = pp.getParselet();
      ArrayList<Object> remaining = null;
      int remainingIx = -1;

      List<IString> idents = getAllIdentifiers();
      int sz = idents.size();

      Parselet idExParselet = ((JavaLanguage) topParselet.getLanguage()).identifierExpression;
      // TODO: here we are dealing with the fact that the IdentifierExpression could be wrapped in
      // an expressionStatement, or optExpression.   In those cases, the IdentifierExpression is down 1-level
      // and we have the ; we need to handle in 'remaining'  This could be handled more generically in the grammar.
      // See comment for this method.
      if (topParselet != idExParselet) {
         boolean foundIdx = false;
         for (int i = 0; i < pp.children.size(); i++) {
            Object child = pp.children.get(i);
            if (child instanceof ParentParseNode && ((ParentParseNode) child).getParselet() == idExParselet) {
               remaining = new ArrayList<Object>();
               remainingIx = i + 1;
               while (++i < pp.children.size()) {
                  Object remainingNode = pp.children.get(i);
                  if (remainingNode != null)
                     remaining.add(remainingNode);
               }
               pp = (ParentParseNode) child;
               foundIdx = true;
               break;
            }
            else {
               ParseUtil.toStyledChild(adapter, pp, child, i);
            }
         }
         // If parent is <annotationValue.0> the identifierExpression is in side of child.get(1) inside of a ParseNode
         if (!foundIdx)
            return;
      }
      ParentParseNode identsNode = (ParentParseNode) pp.children.get(0);
      ParentParseNode argsNode = null;
      if (pp.children.size() > 1) {
         Object secondChild = pp.children.get(1);
         if (secondChild instanceof ParentParseNode)
            argsNode = (ParentParseNode) secondChild;
         else if (secondChild instanceof ParseNode) {
            System.out.println("*** Untested case in styleExpression");
            ParseUtil.toStyledChild(adapter, pp, secondChild, 1);
         }
      }

      if (identsNode == null) {
         super.styleNode(adapter);
         return;
      }

      for (int i = 0; i < sz; i++) {
         IParseNode child;
         // TODO: Here we are unwrapping the grammar rule for creating the identifiers property - this could be solved in parselets more generically (see comment above)
         if (i == 0) {
            Object firstChild = identsNode.children.get(0);
            if (firstChild instanceof IParseNode)
               child = (IParseNode) firstChild;
            else {
               ParseUtil.toStyledChild(adapter, identsNode, firstChild, 0);
               continue;
            }
         }
         else {
            Object nextChildObj = identsNode.children.get(1);
            if (nextChildObj instanceof ParentParseNode) {
               ParentParseNode nextChildren = (ParentParseNode) nextChildObj;
               int childIx = (i - 1) * 2;
               if (nextChildren.children.size() > childIx) {
                  // First do the '.'
                  ParseUtil.toStyledString(adapter, nextChildren.children.get(childIx));
                  // Then the next identifier
                  child = (IParseNode) nextChildren.children.get(childIx + 1);
               }
               else {
                  continue;
               }
            }
            else {
               ParseUtil.toStyledString(adapter, nextChildObj);
               continue;
            }
         }
         boolean handled = false;

         if (idTypes == null) {
            System.err.println("*** styling identifier expression that has not been initializd?");
         }

         String styleName = null;
         if (idTypes != null && idTypes[i] != null) {
            switch (idTypes[i]) {
               case SetVariable:
                  // If we have "var = ..." and this maps to setVar(..) we should treat it as a field for styling
                  //if (!ModelUtil.isField(boundTypes[i]))
                  //   break;
               case FieldName:
               case GetVariable:
               case BoundObjectName:
                  styleName = isStaticTarget(i) ? "staticMember" : "member";
                  break;
               case EnumName:
                  styleName = "constant";
                  break;
               case ThisExpression:
               case SuperExpression:
                  styleName = "keyword";
                  break;
               default:
                  break;
            }
         }
         if (styleName == null) {
            child.styleNode(adapter, null, null, -1);
         }
         else {
            adapter.styleStart(styleName);
            child.styleNode(adapter, null, null, -1);
            adapter.styleEnd(styleName);
         }
      }
      if (argsNode != null)
         argsNode.styleNode(adapter, null, null, -1);
      if (remaining != null) {
         for (Object node:remaining) {
            if (node != argsNode)
               ParseUtil.toStyledChild(adapter, parseNode, node, remainingIx++);
         }
      }
   }


   public boolean callsSuper() {
      // Need to catch both the normal case: super(x) and the JS case: typeName.call(...)
      if (idTypes != null && (idTypes[0] == IdentifierType.SuperExpression || (idTypes.length == 2 && idTypes[1] == IdentifierType.SuperExpression && idTypes[0] == IdentifierType.BoundTypeName)) && arguments != null) {
         AbstractMethodDefinition method = getEnclosingMethod();
         if (method instanceof ConstructorDefinition) {
            ConstructorDefinition constr = (ConstructorDefinition) method;
            return !constr.isModifySuper(); // Eliminate the case where super is really going to a modified constructor.  This method is for real super's in the constructor that go to a base class for the purposes of maintaining the dynamic/compiled type constract.
         }
         else // In the JS method case we've been detached from the parent.
            return true;
      }
      return false;
   }

   public boolean callsThis() {
      List<IString> idents = getAllIdentifiers();
      return idTypes != null && idTypes[0] == IdentifierType.ThisExpression && arguments != null && idents.size() == 1;
   }

   public Expression[] getConstrArgs() {
      return callsSuper() ? arguments.toArray(new Expression[arguments.size()]) : null;
   }

   public void refreshBoundTypes(int flags) {
      super.refreshBoundTypes(flags);
      if (boundTypes != null)
         for (int i = 0; i < boundTypes.length; i++) {
            // For super expressions we need just resolve the entire thing from scratch.
            if (idTypes[i] == IdentifierType.SuperExpression) {
               reresolveTypeReference();
               return;
            }
            else if (boundTypes[i] != null) {
               boundTypes[i] = ModelUtil.refreshBoundIdentifierType(getLayeredSystem(), boundTypes[i], flags);
            }
         }
      // Is this used anyplace?
      if (innerCreator != null)
         innerCreator.refreshBoundTypes(flags);
   }

   public int transformTemplate(int ix, boolean statefulContext) {
      ix = super.transformTemplate(ix, statefulContext);
      if (arguments != null) {
         for (Expression expr:arguments)
            ix = expr.transformTemplate(ix, statefulContext);
      }
      return ix;
   }

   public void addDependentTypes(Set<Object> types) {
      if (boundTypes != null)
         for (int i = 0; i < boundTypes.length; i++) {
            Object varType = getTypeForIdentifier(i);
            if (varType != null) {
               if (!(varType instanceof AbstractMethodDefinition))
                  types.add(varType);
            }
            Object boundType = boundTypes[i];
            if (idTypes != null && boundType != null) {
               switch (idTypes[i]) {
                  case SuperExpression: // Presumably we picked up the super-type already from the extends clause - it might be a method so at least we'll need to check the enclosing type
                     break;
                  case MethodInvocation:
                  case FieldName:
                     types.add(ModelUtil.getEnclosingType(boundType));
                     break;
               }
            }
         }
      // Is this used anyplace?
      if (innerCreator != null)
         innerCreator.addDependentTypes(types);
      if (arguments != null) {
         for (Expression expr:arguments)
            expr.addDependentTypes(types);
      }
   }


   private boolean needsClassInitConversion(Object srcType) {
      // The children of AssignmentExpressions used to be excluded here as well but for an enum variable reference
      // we definitely need this.
      return arguments == null && !(parentNode instanceof SelectorExpression) && ModelUtil.needsClassInit(srcType) && !ModelUtil.isAssignableFrom(srcType, getEnclosingType()) && !(parentNode instanceof SwitchLabel);
   }

   /*
   private boolean isUnbound() {
      if (idTypes == null)
         return true;
      for (int i = 0; i < idTypes.length; i++) {
         IdentifierType type = idTypes[i];
         switch (type) {
            case UnboundMethodName:
            case UnboundName: {
               IString ident = identifiers.get(i);
               if (!ident.startsWith("_outer") && !ident.equals("outer"))
                  return true;
            }
         }
      }
      return false;
   }
   */

   public Statement transformToJS() {
      if (idTypes == null) {
         return this;
      }
      List<IString> idents = getAllIdentifiers();
      int sz = idents.size();

      /* TODO: probably just remove.  this was an ilfated attempted to re-transform types but didn't solve any problems
      if (isUnbound()) {
         resolveTypeReference();
      }
      */

      if (jsTransformed) { // TODO: not sure why these are getting transformed twice... maybe through binary expressions?  One more boolean per identifier exprsssion could add up!
         return this;
      }
      jsTransformed = true;

      Statement result = this;

      int start = 0;
      for (int i = 1; i < sz; i++) {
         if (idTypes[i] == IdentifierType.ThisExpression) {
            Object thisType = boundTypes[i-1];
            Object curType = getEnclosingType();
            for (int j = 0; j < i; j++) {
               removeIdentifier(0);
               sz--;
            }
            int numOuters = 0;
            int ix = 1;
            while (curType != null && !ModelUtil.isAssignableFrom(thisType, curType)) {
               int ct = ModelUtil.getOuterInstCount(curType);
               addIdentifier(ix, "_outer" + ct, IdentifierType.FieldName, curType);
               curType = ModelUtil.getEnclosingType(curType);
               numOuters++;
               ix++;
            }
            start = i+numOuters;
            break;
         }
         else if (idTypes[i] == IdentifierType.ResolvedObjectName)
            start = i;
      }

      boolean retried = false;
      for (int i = start; i < sz; i++) {
         if (idTypes[i] == IdentifierType.UnboundName && !retried && !specialJSIdentifier(i)) {
            retried = true;
            resolveTypeReference();
         }
         String str = idents.get(i).toString();
         if (JSUtil.jsKeywords.contains(str))
            idents.set(i, new PString("_" + str));

         if (idTypes[i] == IdentifierType.EnumName) {
            if (boundTypes[i] instanceof EnumConstant && ((EnumConstant) boundTypes[i]).body != null) {
               EnumConstant enumConst = (EnumConstant) boundTypes[i];
               EnumDeclaration enumDecl = (EnumDeclaration) enumConst.getEnclosingType();
               LayeredSystem sys = getLayeredSystem();
               String prefix = sys.runtimeProcessor.getStaticPrefix(enumDecl, this);

               addIdentifier(0, prefix, IdentifierType.BoundTypeName, enumDecl);
            }

            // When there's a body we create an actual type for each enum constant.  We need to transform the
            //if (boundTypes[i] instanceof EnumConstant && ((EnumConstant) boundTypes[i]).body != null) {
            //   LayeredSystem sys = getLayeredSystem();
            //   String prefix = sys.runtimeProcessor.getStaticPrefix(boundTypes[i], this);

               // Get rid of the enum type since it's name is included in the static prefix
            //   for (int r = 0; r < i; r++) {
            //      removeIdentifier(0);
            //   }

            //   idents.set(i, new PString(prefix));
               //   ie.setIdentifier(0, prefix, IdentifierExpression.IdentifierType.EnumName, boundType);
            //}
         }

         if (idTypes[i] == IdentifierType.FieldName && ModelUtil.isFieldShadowedByMethod(boundTypes[i])) {
            if (!str.startsWith(JSUtil.ShadowedPropertyPrefix))
               idents.set(i, new PString(JSUtil.ShadowedPropertyPrefix + str));
         }

         if (idTypes[i] == IdentifierType.MethodInvocation || idTypes[i] == IdentifierType.RemoteMethodInvocation) {
            Object meth = boundTypes[i];
            // Meth here is TypeDeclaration when we have a newX method that we could not resolve
            if (meth != null && !(meth instanceof TypeDeclaration)) {
               JavaModel model = getJavaModel();
               if (model.customResolver == null || !model.customResolver.useRuntimeResolution())
                  meth = ModelUtil.resolveSrcMethod(getLayeredSystem(), meth, true, false);

               Object jsMethSettings = ModelUtil.getAnnotation(meth, "sc.js.JSMethodSettings");
               if (jsMethSettings != null) {
                  String replaceWith = (String) ModelUtil.getAnnotationValue(jsMethSettings, "replaceWith");
                  if (replaceWith != null && replaceWith.length() > 0) {
                     RTypeUtil.addMethodAlias(ModelUtil.getTypeName(ModelUtil.getEnclosingType(meth)), idents.get(i).toString(), replaceWith);
                     idents.set(i, new PString(replaceWith));
                  }
               }

               // Do any modification for data type conversions required for the arguments, such as char to int which requires the sc_charToInt(ch) call (because a char in JS is a string of length 1)
               SemanticNodeList<Expression> args = arguments;
               if (args != null) {
                  int aix = 0;
                  Object[] paramTypes = ModelUtil.getParameterTypes(meth);
                  if (paramTypes != null) {
                     for (Expression arg:args) {
                        if (aix < paramTypes.length) { // Skip repeat args for now
                           Expression newExpr = arg.applyJSConversion(paramTypes[aix]);
                           if (newExpr != null)
                              arguments.set(aix, newExpr);
                        }
                        aix++;
                     }
                  }
               }
            }
         }



      }

      if (idTypes.length > start) {
         if (idTypes == null || idTypes[start] == null)
            System.out.println("*** Error uninitialized expression in transform to JS ");
         // JS types need to prefix this
         switch (idTypes[start]) {
            case BoundObjectName:
               //if (!isThisObjectReference())
               //   break;
               // FALL THROUGH FOR objects based on "this"

            case MethodInvocation:
            case RemoteMethodInvocation:
            case FieldName:
            case GetVariable:
            case IsVariable:
            case SetVariable:
            case GetSetMethodInvocation:
            case GetObjectMethodInvocation:
               Object srcField = boundTypes[0];
               if (srcField instanceof VariableDefinition) {
                  Object srcFieldDef = ((VariableDefinition) srcField).getDefinition();
                  if (srcFieldDef != null)
                     srcField = srcFieldDef;
               }

               Object srcType;
               TypeDeclaration type = getEnclosingType();

               if (srcField instanceof IVariable || srcField instanceof FieldDefinition || srcField instanceof BodyTypeDeclaration || srcField instanceof ITypedObject) {
                  srcType = ModelUtil.getEnclosingType(srcField);
                  if (srcType == null && srcField instanceof BodyTypeDeclaration)
                     srcType = srcField;
               }
               else
                  srcType = RTypeUtil.getPropertyDeclaringClass(srcField);

               boolean isStaticTarget = isStaticTarget(0);
               String prefix;
               if (isStaticTarget) {
                  prefix =  getLayeredSystem().runtimeProcessor.getStaticPrefix(srcType, this);

                  // Do we need to inject the classInit call for this type first?  Optimize out the case where we are in a method of the same type.
                  if (needsClassInitConversion(srcType)) {
                     SemanticNodeList<Expression> clInitArgs = new SemanticNodeList<Expression>();
                     clInitArgs.add(IdentifierExpression.create(prefix));
                     IdentifierExpression baseExpr = IdentifierExpression.createMethodCall(clInitArgs, "sc_clInit");
                     SemanticNodeList<Selector> sels = new SemanticNodeList<Selector>();
                     for (int j = 0; j < idents.size(); j++) {
                        VariableSelector v = new VariableSelector();
                        String ident = idents.get(j).toString();
                        v.setProperty("identifier", ident);

                        if (j == idents.size() - 1 && arguments != null)
                           v.setProperty("arguments", arguments);

                        sels.add(v);
                     }
                     SelectorExpression newSel = SelectorExpression.create(baseExpr, sels.toArray(new Selector[sels.size()]));
                     if (parentNode.replaceChild(this, newSel) == -1)
                        System.out.println("*** error could not find node to replace");
                     result = newSel;
                  }
                  else {
                     addIdentifier(0, prefix, IdentifierType.BoundTypeName, srcType);
                  }
               }
               // Don't add 'this' if we are not bound - sc_clInit and other non-bindable functions make it in here.
               else if (srcField != null) {
                  prefix =  "this";
                  addIdentifier(0, prefix, IdentifierType.ThisExpression, srcType);
               }

               if (srcField != null && !ModelUtil.hasModifier(srcField, "static")) {
                  // TODO: need to get the base class relative to "type" from which we lookup srcType
                  TypeDeclaration pType = type;
                  int ix = 1;
                  while (pType != null && !ModelUtil.isAssignableFrom(srcType, pType)) {
                     int ct = pType.getOuterInstCount();
                     addIdentifier(ix++, "_outer" + ct, IdentifierType.FieldName, pType);
                     pType = pType.getEnclosingType();
                  }
               }
               break;

            case PackageName:
            case BoundTypeName:
               int i;
               for (i = 0; i < sz && idTypes[i] != IdentifierType.BoundTypeName; i++)
                  ;

               // If there are multiple BoundTypeName's such as for an inner type, skip to the last one
               while (i < sz - 1 && (idTypes[i+1] == IdentifierType.BoundTypeName || idTypes[i+1] == IdentifierType.PackageName)) {
                  i++;
               }

               boolean handled = false;
               int thisIx = i;

               // For the type.this case we need to convert to this.outer.outer... as needed and strip the prefix type
               while (thisIx < sz) {
                  if (idTypes[thisIx] == IdentifierType.ThisExpression) {
                     Object refType = boundTypes[thisIx-1];
                     for (int ti = 0; ti < thisIx; ti++) {
                        removeIdentifier(0);
                        sz--;
                     }
                     boundTypes[0] = refType;
                     Object curType = getEnclosingType();
                     int ix = 1;
                     while (curType != null && !ModelUtil.isAssignableFrom(refType, curType)) {
                        int ct = ModelUtil.getOuterInstCount(curType);
                        addIdentifier(ix, "_outer" + ct, IdentifierType.FieldName, curType);
                        curType = ModelUtil.getEnclosingType(curType);
                        ix++;
                     }

                     handled = true;
                     break;
                  }
                  thisIx++;
               }

               if (!handled) {
                  if (idTypes != null && i >= idTypes.length)
                     System.out.println("*** Error - unresolved identifier expression");
                  Object boundType = getTypeForIdentifier(i);
                  LayeredSystem sys = getLayeredSystem();
                  JavaModel model = getJavaModel();
                  if (model.customResolver == null || !model.customResolver.useRuntimeResolution())
                     boundType = ModelUtil.resolveSrcTypeDeclaration(sys, boundType, false, false);
                  prefix = sys.runtimeProcessor.getStaticPrefix(boundType, this);

                  for (int j = 1; j <= i; j++) {
                     removeIdentifier(1);
                     sz--;
                  }

                  if (needsClassInitConversion(boundType)) {
                     SemanticNodeList<Expression> clInitArgs = new SemanticNodeList<Expression>();
                     clInitArgs.add(IdentifierExpression.create(prefix));

                     IdentifierExpression baseExpr = IdentifierExpression.createMethodCall(clInitArgs, "sc_clInit");
                     SemanticNodeList<Selector> sels = new SemanticNodeList<Selector>();
                     for (int j = 1; j < idents.size(); j++) {
                        VariableSelector v = new VariableSelector();
                        String ident = idents.get(j).toString();
                        v.setProperty("identifier", ident);

                        if (j == idents.size() - 1 && arguments != null)
                           v.setProperty("arguments", arguments);

                        sels.add(v);
                     }
                     SelectorExpression newSel = SelectorExpression.create(baseExpr, sels.toArray(new Selector[sels.size()]));
                     if (parentNode.replaceChild(this, newSel) == -1) {
                        // This happens when we are transforming an expression from an annotation value.  Because we just cloned the expression, the parent does not
                        // contain the child so the replace fails.  It's fine in this case.
                        //System.out.println("*** error could not find node to replace");
                     }
                     result = newSel;
                  }
                  else {
                     setIdentifier(0, prefix, IdentifierType.BoundTypeName, boundType);
                  }
               }
               break;

            case SuperExpression:
            case ThisExpression:
               convertToJSCall(idTypes[0]);
               break;

            case UnboundName:
            case UnboundMethodName:
               //if (!specialJSIdentifier(0))
               //   System.out.println("*** unbound in transformToJS");
               break;

            case EnumName:
               Object enumType = boundTypes[start];
               break;

            // Convert this guy into a resolveName("typeName") call.
            case ResolvedObjectName:
               JavaModel model = getJavaModel();
               String resolverName = model.customResolver.getResolveMethodName();
               String resolverTypeName = CTypeUtil.getPackageName(resolverName);
               String methodName = CTypeUtil.getClassName(resolverName);
               String jsName = JSUtil.convertTypeName(model.layeredSystem, resolverTypeName);
               jsName += ((JSRuntimeProcessor) model.layeredSystem.runtimeProcessor).prototypeSuffix;
               String typeName = getIdentifierPathName(sz);
               String pkgName = model.getPackagePrefix();
               String typeTypeName = ModelUtil.getTypeName(boundTypes[sz-1]);
               // If we stripped off the package during serialization, add it back now - unless this is the special sc_type_ prefix.
               if (pkgName != null && !typeName.startsWith(pkgName) && !typeName.startsWith("sc_type_")) {
                  if (model.customResolver.resolveObject(null, typeName, false, true) == null && model.customResolver.resolveObject(pkgName, typeName, false, true) != null)
                     typeName = CTypeUtil.prefixPath(pkgName, typeName);
               }
               while (idents.size() > 2) {
                  removeIdentifier(0);
                  sz--;
               }
               setIdentifier(0, jsName, IdentifierType.BoundTypeName, null);
               if (idents.size() == 1)
                  addIdentifier(1, methodName, IdentifierType.MethodInvocation, null);
               else
                  setIdentifier(1, methodName, IdentifierType.MethodInvocation, null);
               SemanticNodeList<Expression> resolveNameArg = new SemanticNodeList<Expression>();
               resolveNameArg.add(StringLiteral.create(typeName));
               setProperty("arguments", resolveNameArg);
               break;
         }
      }

      if (arguments != null) {
         for (Expression expr:arguments)
            expr.transformToJS();
      }
      return result;
   }

   private boolean specialJSIdentifier(int ix) {
      List<IString> idents = getAllIdentifiers();
      // This guy won't get bound but ignore the error
      if (idents.get(ix).startsWith("_outer"))
         return true;
      return false;
   }

   private void convertToJSCall(IdentifierType idType) {
      List<IString> idents = getAllIdentifiers();
      int sz = idents.size();

      Object superType = null;
      Object enclType = null;
      if (arguments != null) {
         boolean addOuter = false;
         int skipCt = 0;
         // Method super
         if (sz == 2) {
            if (getLayeredSystem() == null || getLayeredSystem().runtimeProcessor == null)
               System.out.println("*** Error - invalid type in JS conversion");
            if (boundTypes == null)
               System.err.println("*** Unresolved identifier expression in JS conversion: " + this);
            else {
               // convert from super.x(..) to JSType_c.x.call(this, ..)
               setIdentifier(0, getLayeredSystem().runtimeProcessor.getStaticPrefix(getTypeForIdentifier(0), this), IdentifierType.BoundTypeName, boundTypes[0]);
            }
         }
         // Constructor super
         else {
            superType = getTypeForIdentifier(0);
            if (superType == null)
               superType = getTypeForIdentifier(0);
            enclType = getEnclosingType();
            if (ModelUtil.getEnclosingInstType(superType) != null && ModelUtil.getEnclosingInstType(enclType) != null) {
               int curCt = ModelUtil.getOuterInstCount(enclType);
               int superCt = ModelUtil.getOuterInstCount(superType);
               if (curCt != superCt)
                  skipCt = curCt - superCt;
               if (skipCt < 0) {
                  System.err.println("*** Invalid case - sub type must have more levels than super type");
                  skipCt = 0;
               }
               // else - this is the case where we are extending an inner class from a lower-level type.  Need to pass the super-type the original outer type which is now skipCt levels up the inner inst type hierarchy.
               addOuter = true;
            }
            // convert from super.x(..) to JSType.call(this, ..)
            setIdentifier(0, JSUtil.convertTypeName(getLayeredSystem(), ModelUtil.getTypeName(getTypeForIdentifier(0))), IdentifierType.BoundTypeName, boundTypes[0]);
         }
         addIdentifier(sz, "call", idType, boundTypes[sz-1]);
         arguments.add(0, IdentifierExpression.create(new NonKeywordString("this")));
         if (addOuter) {
            // TODO: this won't bind to anything.   Maybe pre-initialize it's bound value here?
            IdentifierExpression outerExpr;
            if (skipCt == 0)
               outerExpr = IdentifierExpression.create("_outer");
            else {
               StringBuilder toAdd = new StringBuilder();
               Object thisEnclType = ModelUtil.getEnclosingInstType(enclType);
               // Get the type of the outer type for the super type -
               Object extEnclType = ModelUtil.getEnclosingType(superType);
               // Count the number of levels till we reach this type in the this type's outer types
               toAdd.append("_outer");
               while (extEnclType != null && thisEnclType != null && !ModelUtil.isAssignableFrom(extEnclType, thisEnclType)) {
                  toAdd.append(".outer");
                  thisEnclType = ModelUtil.getEnclosingType(thisEnclType);
               }
               outerExpr = IdentifierExpression.create(toAdd.toString());
            }

            arguments.add(1, outerExpr);
         }
      }
   }


   public void removeIdentifier(int ix) {
      List<IString> idents = getAllIdentifiers();
      idents.remove(ix);

      int oldLen = idTypes.length;
      IdentifierType[] newIdTypes = new IdentifierType[oldLen - 1];
      int j = 0;
      boolean found = false;
      for (int i = 0; i < oldLen; i++) {
         if (j == ix && !found) {
            found = true;
         }
         else {
            newIdTypes[j++] = idTypes[i];
         }
      }
      idTypes = newIdTypes;

      Object[] newBoundTypes = new Object[oldLen - 1];
      j = 0;
      found = false;
      for (int i = 0; i < oldLen; i++) {
         if (j == ix && !found) {
            found = true;
         }
         else {
            newBoundTypes[j++] = boundTypes[i];
         }
      }
      boundTypes = newBoundTypes;
   }

   public void addIdentifier(int ix, String identifier, IdentifierType idType, Object boundType) {
      List<IString> idents = getAllIdentifiers();
      if (ix == idents.size())
         idents.add(new NonKeywordString(identifier));
      else
         idents.add(ix, new NonKeywordString(identifier));

      if (idTypes != null) {
         int oldLen = idTypes.length;
         IdentifierType[] newIdTypes = new IdentifierType[oldLen + 1];
         int j = 0;
         for (int i = 0; i < oldLen; i++) {
            if (j == ix) {
               newIdTypes[j++] = idType;
               i--;
            }
            else {
               newIdTypes[j++] = idTypes[i];
            }
         }
         if (j == ix)
            newIdTypes[j] = idType;
         idTypes = newIdTypes;

         Object[] newBoundTypes = new Object[oldLen + 1];
         j = 0;
         for (int i = 0; i < oldLen; i++) {
            if (j == ix) {
               newBoundTypes[j++] = boundType;
               i--;
            }
            else {
               newBoundTypes[j++] = boundTypes[i];
            }
         }
         if (j == ix)
            newBoundTypes[j] = boundType;
         boundTypes = newBoundTypes;
      }
   }

   public void setIdentifier(int ix, String identifier, IdentifierType idType, Object boundType) {
      List<IString> idents = getAllIdentifiers();
      idents.set(ix, new NonKeywordString(identifier));
      idTypes[ix] = idType;
      boundTypes[ix] = boundType;
   }

   public IdentifierExpression deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap) {
      IdentifierExpression newIdent = (IdentifierExpression) super.deepCopy(options, oldNewMap);

      if ((options & CopyState) != 0) {
         if (idTypes != null) {
            newIdent.idTypes = idTypes.clone();
         }
         if (boundTypes != null) {
            newIdent.boundTypes = boundTypes.clone();
         }
         newIdent.inferredType = inferredType;
      }
      if ((options & CopyInitLevels) != 0) {
         newIdent.isAssignment = isAssignment;
         newIdent.referenceInitializer = referenceInitializer;
      }
      return newIdent;
   }

  public boolean canMakeBindable() {
      // TODO: need to fix this.  It's used when generating templates to deteremine if the expression can be bound to or not.
      // That's during initialization though and so we can't rely on the expression being started.  If it's a compiled expression,
      // we should be able to tell we can't bind to it and not use binding for that element.
      // May not be started yet - need this to determine whether we can make the page stateful which happens during init
     List<IString> idents = getAllIdentifiers();
      if (idents != null) {
         for (IString str:idents)
            if (str.equals("super"))
               return false;
      }
      return true;
   }

   public void changeToRHS() {
      isAssignment = false;
      if (idTypes != null) {
         for (int i = 0; i < idTypes.length; i++) {
            IdentifierType idType = idTypes[i];
            switch (idType) {
               case SetVariable:
                  // Need to remap this identifier expression or convert the set method to the get method.
                  idTypes = null;
                  boundTypes = null;
                  return;
            }
         }
      }
   }

   public boolean isDeclaredConstant() {
      return false;
   }

   public boolean isConstant() {
      if (!started)
         return false; // Don't start just to do an optimization...
         //ParseUtil.realInitAndStartComponent(this);

      if (boundTypes == null)
         return false;

      // Still need to see if this is a getX even if there are arguments
      if (arguments != null && arguments.size() > 0)
         return false;
      List<IString> idents = getAllIdentifiers();
      Object prop = boundTypes[idents.size()-1];
      if (prop != null && ModelUtil.isProperty(prop)) {
         if (ModelUtil.isConstant(prop))
            return true;
      }
      return false;
   }

   protected static String argsToGenerateString(SemanticNodeList<Expression> args) {
      StringBuilder sb = new StringBuilder();
      if (args != null) {
         sb.append("(");
         int subIx = 0;
         for (Expression expr:args) {
            if (subIx != 0)
               sb.append(", ");
            sb.append(expr.toGenerateString());
            subIx++;
         }
         sb.append(")");
      }
      return sb.toString();
   }

   public String toGenerateString() {
      StringBuilder sb = new StringBuilder();
      List<IString> idents = getAllIdentifiers();
      if (idents != null){
         generateIdentString(sb, idents.size());
      }
      else
         sb.append("<no identifiers>");
      if (arguments != null)
        sb.append(argsToGenerateString(arguments));
      return sb.toString();
   }

   public void generateIdentString(StringBuilder sb, int ix) {
      List<IString> idents = getAllIdentifiers();
      if (idents != null) {
         for (int i = 0; i < ix; i++) {
            IString is = idents.get(i);
            if (i != 0)
               sb.append(".");
            sb.append(is);
         }
      }
   }

   public void setParentNode(ISemanticNode node) {
      super.setParentNode(node);
   }

   public boolean producesHtml() {
      if (!started) {
         resolveTypeReference();
      }
      List<IString> idents = getAllIdentifiers();
      Object methObj = boundTypes[idents.size()-1];
      if (methObj != null) {
         Object annotVal = ModelUtil.getAnnotationValue(methObj, "sc.obj.HTMLSettings", "returnsHTML");
         return annotVal != null && (Boolean) annotVal;
      }
      return false;
   }

   public boolean getNodeContainsPart(ISrcStatement fromSt) {
      if (super.getNodeContainsPart(fromSt))
         return true;
      if (arguments != null) {
         for (Expression arg:arguments)
            if (arg == fromSt || arg.getNodeContainsPart(fromSt))
               return true;
      }
      return false;
   }

   public ISrcStatement findFromStatement(ISrcStatement st) {
      ISrcStatement res = super.findFromStatement(st);
      if (res != null)
         return res;
      // The breakpoint may be set on some expression that has been embedded into one of our arguments.  If so, we are the closest statement to the breakpoint
      if (arguments != null) {
         for (Expression arg:arguments) {
            if (arg.findFromStatement(st) != null)
               return this;
         }
      }
      return null;
   }

   // An argument of the expression might be a NewExpression with a class body so we need to look for more than just one match against the identifier expression
   public void addBreakpointNodes(List<ISrcStatement> res, ISrcStatement srcStatement) {
      super.addBreakpointNodes(res, srcStatement);
      if (arguments != null) {
         for (Expression arg:arguments) {
            arg.addBreakpointNodes(res, srcStatement);
         }
      }
   }

   public boolean needsEnclosingClass() {
      /** A Foo.this expression can implicitly bind in the absolute type hierarchy of the current code - disabling the optimization where we can eliminate an inner class. */
      if (isThisExpression()) {
         if (getEnclosingType() != getTypeDeclaration())
            return true;
      }
      return false;
   }

   public boolean setInferredType(Object inferredType, boolean finalType) {
      this.inferredType = inferredType;
      this.inferredFinal = finalType;

      // TODO: do we need to re-resolve all type references now?
      // TODO: performance: if we haven't changed inferredType can we skip the re-resolve
      JavaModel model;
      if (!finalType && (model = getJavaModel()) != null) {
         boolean oldTypeErrors = model.disableTypeErrors;
         try {
            model.setDisableTypeErrors(true);
            reresolveTypeReference();
         }
         finally {
            model.setDisableTypeErrors(oldTypeErrors);
         }
      }
      else
         reresolveTypeReference();
      //propagateInferredTypes();
      return false;
   }

   public boolean isInferredSet() {
      return inferredType != null || !hasInferredType();
   }

   public boolean isInferredFinal() {
      return inferredFinal;
   }

   public boolean propagatesInferredType(Expression child) {
      return true;
   }

}
