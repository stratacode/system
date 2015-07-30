/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.dyn.DynUtil;
import sc.lang.ILanguageModel;
import sc.lang.SemanticNodeList;
import sc.lang.template.Template;
import sc.layer.LayeredSystem;
import sc.type.IBeanIndexMapper;
import sc.type.IBeanMapper;
import sc.type.PropertyMethodType;
import sc.util.FileUtil;

import java.io.File;
import java.util.*;

/**
 * A regular Java method definition (not a constructor).  This implements IVariable for the case where
 * the method is an "is" or a "get" method and extensions are enabled.
 */
public class MethodDefinition extends AbstractMethodDefinition implements IVariable {
   public boolean override;
  
   public transient String propertyName = null;
   public transient PropertyMethodType propertyMethodType;
   public transient Object superMethod; // A reference to the method we override if any
   public transient boolean isMain;
   public transient boolean dynamicType;
   public transient boolean overridesCompiled;
   public transient boolean needsDynAccess = false;   // For properties defined via get/set, we still access them in the DynType as properties.

   public static SemanticNodeList MAIN_ARGS = new SemanticNodeList(1);
   static {
      MAIN_ARGS.add(ClassType.create("String"));
      ((ClassType)MAIN_ARGS.get(0)).arrayDimensions = "[]";
   }

   public boolean transform(ILanguageModel.RuntimeType type) {
      boolean res = super.transform(type);
      return res;
   }

   public void init() {
      if (initialized) return;
      
      if (name != null && type != null) {
         JavaType[] paramJavaTypes = parameters == null ? null : parameters.getParameterJavaTypes();
         if ((propertyName = ModelUtil.isGetMethod(name, paramJavaTypes, type)) != null)
            propertyMethodType = propertyName.startsWith("i") ? PropertyMethodType.Is : PropertyMethodType.Get;
         else if ((propertyName = ModelUtil.isSetMethod(name, paramJavaTypes, type)) != null)
            propertyMethodType = PropertyMethodType.Set;
         else if ((propertyName = ModelUtil.isSetIndexMethod(name, paramJavaTypes, type)) != null)
            propertyMethodType = PropertyMethodType.SetIndexed;
         else if ((propertyName = ModelUtil.isGetIndexMethod(name, paramJavaTypes, type)) != null)
            propertyMethodType = PropertyMethodType.GetIndexed;
         isMain = name.equals("main") && type.isVoid() && paramJavaTypes != null && paramJavaTypes.length == 1 &&
                 ModelUtil.typeIsStringArray(paramJavaTypes[0]) && hasModifier("static");
      }

      // Do this after our propertyName is set so that other initializers can use that info
      super.init();
   }

   public boolean isDynamicType() {
      return dynamicType || super.isDynamicType();
   }

   public boolean getOverridesCompiled() {
      return overridesCompiled;
   }

   public void start() {
      if (started) return;

      super.start();
   }

   public void validate() {
      if (validated) return;

      TypeDeclaration methodType = getEnclosingType();

      // We are probably just a fragment so none of this stuff is required.
      if (methodType == null) {
         return;
      }
      // TODO: not sure how or whether we should inherit methods from downstream layers.  Right now, the eerived type will be Layer.class which is the base at least.
      Object extendsType = methodType.isLayerType ? null : methodType.getExtendsTypeDeclaration();
      Object modType = methodType.getDerivedTypeDeclaration();
      if (extendsType == null)
         extendsType = Object.class;
      Object overridden = ModelUtil.definesMethod(extendsType, name, getParameterList(), null, null, false, false);
      superMethod = overridden;
      if (overridden instanceof MethodDefinition) {
         MethodDefinition superMeth = (MethodDefinition) overridden;
      }

      /* Dynamic methods need to find any overridden method and make sure calls to that one are also made dynamic.
       * This is here in the validate because the isDynamic method on our parents is not valid until we've propagated
       * the dyanmicType flag through the type hierarchy.
       */
      if (isDynamicType()) {
         if (overridden != null) {
            if (ModelUtil.isCompiledMethod(overridden)) {
               methodType.setNeedsDynamicStub(true);
               overridesCompiled = true;
            }
            else if (overridden instanceof MethodDefinition) {
               MethodDefinition overMeth = (MethodDefinition) overridden;
               overMeth.dynamicType = true;
               // This is a modified method - it gets replaced by the following one from the runtime perspective
               if (ModelUtil.sameTypes(methodType, overMeth.getEnclosingType())) {
                  if (overMeth == this)
                     System.out.println("*** replacing a method by itself!");
                  overMeth.replacedByMethod = this;
                  overriddenMethod = overMeth;
               }
            }
         }

         // We can't modify an interface method but if we override a method in a compile interface we
         // need that interface in the dynamic stub of the enclosing type.
         if (overridden == null && methodType.implementsBoundTypes != null) {
            Object implMeth;
            for (Object impl:methodType.implementsBoundTypes) {
               implMeth = ModelUtil.definesMethod(impl, name, getParameterList(), null, null, false, false);
               if (implMeth != null && ModelUtil.isCompiledMethod(implMeth)) {
                  methodType.setNeedsDynamicStub(true);
                  overridesCompiled = true;
               }
            }
         }

         if (modType != extendsType) {
            overridden = ModelUtil.definesMethod(modType, name, getParameterList(), null, null, false, false);
            if (overridden instanceof MethodDefinition) {
               MethodDefinition overMeth = (MethodDefinition) overridden;
               if (overMeth == this)
                  System.out.println("*** replacing a method by itself");
               if (ModelUtil.sameTypes(methodType, overMeth.getEnclosingType())) {
                  overMeth.replacedByMethod = this;
                  overriddenMethod = overMeth;
               }
            }
         }
      }
      else {
         boolean makeBindable = false;

         Object annot = ModelUtil.getBindableAnnotation(this);
         if (annot != null) {
            if (propertyName != null)
               makeBindable = ModelUtil.isAutomaticBindingAnnotation(annot);
            else
               displayError("Bindable annotation valid only on property methods: ");
         }
         if (makeBindable) {
            TypeDeclaration enclType = getEnclosingType();
            // When we are in the midst of doing the transform, we will add getX and setX methods.  Those do not need to be
            // added as properties at the type level.
            if (!enclType.isTransformedType())
               enclType.addPropertyToMakeBindable(propertyName, this, null);
         }
      }
      super.validate();
   }

   public Object definesMember(String name, EnumSet<MemberType> mtype, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed) {
      if (!isInitialized())
         init();
      if (propertyName != null &&
          ((mtype.contains(MemberType.GetMethod) && (propertyMethodType == PropertyMethodType.Get ||
                                                     propertyMethodType == PropertyMethodType.Is)) ||
          (mtype.contains(MemberType.SetMethod) && propertyMethodType == PropertyMethodType.Set)) && propertyName.equals(name))
         return this;
      return super.definesMember(name, mtype, refType, ctx, skipIfaces, isTransformed);
   }

   public String getVariableName() {
      return propertyName;
   }

   /** The methods return type */
   public Object getTypeDeclaration() {
      if (override && type == null) {
         Object prev = getPreviousDefinition();
         if (prev instanceof MethodDefinition)
            return ((MethodDefinition) prev).getTypeDeclaration();
         else
            return ModelUtil.getReturnType(prev);
      }
      return type == null ? null : type.getTypeDeclaration();
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

   public Object getTypeDeclaration(List<? extends ITypedObject> arguments, boolean resolve) {
      if (override && type == null) {
         Object prev = getPreviousDefinition();
         if (prev instanceof IMethodDefinition)
            return ((IMethodDefinition) prev).getTypeDeclaration(arguments, resolve);
         else
            return ModelUtil.getReturnType(prev);
      }

      Object returnType = getTypeDeclaration();
      if (typeParameters == null)
         return returnType;

      if (returnType instanceof TypeParameter && arguments != null) {
         TypeParameter rtParam = (TypeParameter) returnType;
         List<Parameter> paramList = parameters.getParameterList();
         for (int i = 0; i < paramList.size(); i++) {
            Object paramType = paramList.get(i).getTypeDeclaration();
            if (paramType != null && ModelUtil.isTypeVariable(paramType)) {
               String paramArgName = ModelUtil.getTypeParameterName(paramType);
               if (paramArgName != null && paramArgName.equals(rtParam.name)) {
                  if (i >= arguments.size()) // No value for a repeating parameter?
                     return null;
                  return arguments.get(i).getTypeDeclaration();
               }
            }
         }
      }
      // The case for a method like:
      //   public static <E extends Enum<E>> EnumSet<E> allOf(Class<E> type)
      //
      // Get the type of each parameter.  For each type parameter to the method.
      // Take the current type for that parameter and compute the bound types for those type parameters.
      //
      if (returnType instanceof ParamTypeDeclaration) {
         ParamTypeDeclaration returnTypePT = (ParamTypeDeclaration) returnType;
         ParamTypeDeclaration result = returnTypePT.copy();
         for (TypeParameter tp:typeParameters) {
            if (parameters != null) {
               List<Parameter> paramList = parameters.getParameterList();
               for (int i = 0; i < paramList.size(); i++) {
                  Parameter nextParam = paramList.get(i);
                  Object paramType = nextParam.getTypeDeclaration();
                  List<?> typeParams = ModelUtil.getTypeParameters(paramType);
                  if (typeParams != null) {
                     for (int j = 0; j < typeParams.size(); j++) {
                        Object typeParam = typeParams.get(j);
                        int paramPos = ModelUtil.getTypeParameterPosition(typeParam);
                        if (paramPos == tp.getPosition()) {
                           // Special case to handle foo.class -> Class<T> construct.
                           if (ModelUtil.getParamTypeBaseType(paramType) == Class.class) {
                              Object paramExpr = arguments.get(i);
                              // Need the actual class itself, not the type of the expression (which in this case is class)
                              if (paramExpr instanceof ClassValueExpression) {
                                 ClassValueExpression pe = (ClassValueExpression) paramExpr;
                                 Object classType = pe.resolveClassType();
                                 result.types.set(j, classType);
                              }
                              // else - a runtime class.  We don't get additional type info from that.
                           }
                           // If it's a type variable itself, it's not changing the return type.
                           else if (!ModelUtil.isTypeVariable(typeParam)) {
                              System.err.println("*** unhandled case with param type methods");
                           }
                        }
                     }
                  }
                  else if (paramType instanceof ArrayTypeDeclaration) {
                     Object componentType = ((ArrayTypeDeclaration) paramType).getComponentType();
                     if (ModelUtil.isTypeVariable(componentType)) {

                     }
                  }
               }
            }
         }
         return result;
      }
      return returnType;
   }

   public String getPropertyName() {
      return propertyName;
   }

   public void process() {
      if (processed) return;

      JavaModel model;
      if (isMain && (model = getJavaModel()) != null) {
         Object mainSettings = getAnnotation("sc.obj.MainSettings");
         if (mainSettings != null) {
            Boolean disabled = (Boolean) ModelUtil.getAnnotationValue(mainSettings, "disabled");
            if (disabled == null || !disabled) {
               Boolean produceJar = (Boolean) ModelUtil.getAnnotationValue(mainSettings, "produceJar");
               Boolean produceScript = (Boolean) ModelUtil.getAnnotationValue(mainSettings, "produceScript");
               String execCommandTemplateName = (String) ModelUtil.getAnnotationValue(mainSettings, "execCommandTemplate");
               if (execCommandTemplateName != null && execCommandTemplateName.equals(""))
                  execCommandTemplateName = null;
               Boolean debug = (Boolean) ModelUtil.getAnnotationValue(mainSettings, "debug");
               Integer debugPort = (Integer) ModelUtil.getAnnotationValue(mainSettings, "debugPort");
               String execName = (String) ModelUtil.getAnnotationValue(mainSettings, "execName");
               String defaultArgs = (String) ModelUtil.getAnnotationValue(mainSettings, "defaultArgs");
               Integer maxMem = (Integer) ModelUtil.getAnnotationValue(mainSettings, "maxMemory");
               Integer minMem = (Integer) ModelUtil.getAnnotationValue(mainSettings, "minMemory");
               if (defaultArgs == null)
                  defaultArgs = "";
               else {
                  if (defaultArgs.length() > 0 && !defaultArgs.startsWith(" "))
                     defaultArgs = " " + defaultArgs;
               }
               String scriptSuffix = "";
               if (execName == null || execName.length() == 0) {
                  execName = model.getModelTypeName().replace(".", FileUtil.FILE_SEPARATOR);
                  scriptSuffix = ".sh";
               }

               LayeredSystem lsys = getJavaModel().getLayeredSystem();

               String command, restartCommand, sharedArgs;
               String fullTypeName = getEnclosingType().getFullTypeName();
               String debugStr = debug != null && debug ?
                      " -Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=" + (debugPort == null ? "5005": debugPort)  :
                       "";

               String dynStr = "";
               if (lsys.hasDynamicLayers()) {
                  dynStr = "sc.layer.LayeredSystem " + lsys.getLayerNames() + " -lp " + lsys.getLayerPath() + " -r ";
               }

               String memStr = (minMem != null && minMem != 0 ? " -ms" + minMem + "m ": "") + (maxMem != null && maxMem != 0 ? " -mx" + maxMem + "m " : "");
               String vmParams = lsys.getVMParameters();
               // TODO: put this code into a configurable template
               if (produceJar != null && produceJar) {
                  // TODO: need to find a way to inject command line args into the jar process
                  if (dynStr.length() > 0)
                     System.err.println("*** Unable to produce script with jar option for dynamic layers");
                  lsys.buildInfo.addModelJar(model, model.getModelTypeName(), execName + ".jar", null, false);
                  sharedArgs =  "java" + debugStr + memStr + vmParams +
                                      " -jar \"$DIRNAME/" + FileUtil.getFileName(execName) + ".jar\"";
               }
               else {
                  lsys.buildInfo.addMainCommand(model, execName);
                  sharedArgs = "java" + debugStr + vmParams + memStr + " -cp \"" + lsys.userClassPath + "\" " + dynStr + fullTypeName + " ";
               }
               sharedArgs += defaultArgs;
               command = sharedArgs + " $*";
               restartCommand = sharedArgs + " -restart";


               if (produceScript != null && produceScript) {
                  String runScriptFile = FileUtil.concat(lsys.buildDir, execName + scriptSuffix);
                  // TODO: adjust relative paths and add a "cd" in the script?  Eliminate system classes?
                  String startCommand;
                  // TODO: should we make this more flexible?
                  boolean doRestart = fullTypeName.contains("LayeredSystem");

                  Template templ = null;
                  if (execCommandTemplateName != null)
                     templ = getEnclosingType().findTemplatePath(execCommandTemplateName, "exec command template", ExecCommandParameters.class);
                  String restartCommands = !doRestart ? "" :
                                  "while expr $? = 33; do" +
                                  FileUtil.LINE_SEPARATOR +
                                  "   " + restartCommand +
                                  FileUtil.LINE_SEPARATOR +
                                  "SC_EXIT_STATUS=$?" +
                                  FileUtil.LINE_SEPARATOR +
                                  "done" +
                                  FileUtil.LINE_SEPARATOR;
                  if (templ == null) {
                     startCommand = "#!/bin/sh\n" +
                                    "# This file is generated by the MainSettings annotation on class " + fullTypeName + " - warning changes made here will be lost" +
                                    FileUtil.LINE_SEPARATOR +
                                    "CMDPATH=\"`type -p $0`\"" +
                                    FileUtil.LINE_SEPARATOR +
                                    "DIRNAME=`dirname $CMDPATH`" +
                                    FileUtil.LINE_SEPARATOR +
                                    command +
                                    FileUtil.LINE_SEPARATOR +
                                    "SC_EXIT_STATUS=$?" +
                                    FileUtil.LINE_SEPARATOR +
                                    restartCommands +
                                    "exit $SC_EXIT_STATUS";
                  }
                  else {
                     ExecCommandParameters params = new ExecCommandParameters();
                     params.command = command;
                     params.fullTypeName = fullTypeName;
                     params.restartCommand = restartCommand;

                     startCommand = TransformUtil.evalTemplate(params, templ);
                  }

                  // TODO: include comment about not modifying, set exec bit
                  FileUtil.saveStringAsFile(runScriptFile, startCommand, true);
                  new File(runScriptFile).setExecutable(true, true);
               }
            }
         }
      }
      else if (!isMain && getAnnotation("sc.obj.MainSettings") != null) {
         if (!isStatic())
            displayError("MainSettings set on non-static method: ");
         else
            displayError("MainSettings set on method which is not a valid Java 'public static void main(String[] args)' method: ");
      }
      super.process();
   }

   public boolean isProperty() {
      return propertyName != null;
   }

   public boolean hasGetMethod() {
      return isGetMethod() || getEnclosingType().definesMember(propertyName, MemberType.GetMethodSet, null, null) != null;
   }

   public boolean hasSetMethod() {
      return isSetMethod() || getEnclosingType().definesMember(propertyName, MemberType.SetMethodSet, null, null) != null;
   }

   public Object getSetMethodFromGet() {
      if (isGetMethod())
         return getEnclosingType().definesMember(propertyName, MemberType.SetMethodSet, null, null);
      return null;
   }

   public Object getGetMethodFromSet () {
      if (isSetMethod())
         return getEnclosingType().definesMember(propertyName, MemberType.GetMethodSet, null, null);
      return null;
   }

   public boolean isGetMethod() {
      return propertyName != null && (name.startsWith("get") || name.startsWith("is")) || (origName != null && (origName.startsWith("get") || origName.startsWith("is")));
   }

   public boolean isSetMethod() {
      // Make sure to include the orig name in case we do the _bind_set transformation
      return propertyName != null && name.startsWith("set") || (origName != null && origName.startsWith("set"));
   }

   public boolean isGetIndexMethod() {
      return propertyMethodType == PropertyMethodType.GetIndexed;
   }

   public boolean isSetIndexMethod() {
      return propertyMethodType == PropertyMethodType.SetIndexed;
   }

   public Object getReturnType() {
      return getTypeDeclaration();
   }

   public Object getPreviousDefinition() {
      BodyTypeDeclaration btd = getEnclosingType();
      Object base = btd.getDerivedTypeDeclaration();
      Object ext = btd.getExtendsTypeDeclaration();

      Object res;
      if (ext != null) {
         res = ModelUtil.definesMethod(ext, name, getParameterList(), null, null, false, false);
         if (res != null)
            return res;
      }
      if (ext != base && base != null) {
         res = ModelUtil.definesMethod(base, name, getParameterList(), null, null, false, false);
         if (res != null)
            return res;
      }
      return null;
   }

   public Object callVirtual(Object thisObj, Object... values) {
      if (propertyName != null) {
         Object type = DynUtil.getType(thisObj);

         if (isGetIndexMethod() && values.length == 1 && values[0] instanceof Integer) {
            IBeanMapper indexMapper = DynUtil.getPropertyMapping(type, propertyName);
            if (indexMapper instanceof IBeanIndexMapper) {
               IBeanIndexMapper ixMapper = (IBeanIndexMapper) indexMapper;
               if (ixMapper.getIndexedGetSelector() == this)
                  return call(thisObj, values);
               return ((IBeanIndexMapper) indexMapper).getIndexPropertyValue(thisObj, (Integer) values[0]);
            }
            else
               throw new IllegalArgumentException("No indexed property named: " + propertyName + " for type: " + type);
         }
         else {
            boolean isGet = isGetMethod();
            IBeanMapper mapper = DynUtil.getPropertyMapping(type, propertyName);
            if (isGet) {
               if (mapper == null || mapper.getGetSelector() == this)
                  return call(thisObj, values);
               return mapper.getPropertyValue(thisObj);
            }
            else if (isSetMethod()) {
               if (values.length != 1)
                  throw new UnsupportedOperationException();

               if (mapper == null || mapper.getSetSelector() == this)
                  return call(thisObj, values);
               mapper.setPropertyValue(thisObj, values[0]);
               return null;
            }
            else
               throw new UnsupportedOperationException();
         }
      }
      return super.callVirtual(thisObj, values);
   }

   public boolean hasModifier(String modifier) {
      if (super.hasModifier(modifier))
         return true;
      if (override) {
         Object prevDef = getPreviousDefinition();
         if (prevDef != null)
            return ModelUtil.hasModifier(prevDef, "static");
      }
      return false;
   }

   public MethodDefinition deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap) {
      MethodDefinition res = (MethodDefinition) super.deepCopy(options, oldNewMap);

      if ((options & CopyInitLevels) != 0) {
         res.propertyName = propertyName;
         res.propertyMethodType = propertyMethodType;
         res.isMain = isMain;
         res.dynamicType = dynamicType;
         res.overridesCompiled = overridesCompiled;
         res.needsDynAccess = needsDynAccess;
      }
      return res;
   }

   public List<Object> findOverridingMethods() {
      ArrayList<Object> res = new ArrayList<Object>();
      TypeDeclaration enclType = getEnclosingType();
      LayeredSystem sys = getLayeredSystem();
      List<?> parameterTypes = getParameterList();

      if (sys == null)
         return null;
      addOverridingMethods(sys, enclType, res, parameterTypes);

      return res;
   }

   private void addOverridingMethods(LayeredSystem sys, TypeDeclaration enclType, ArrayList<Object> res, List<? extends Object> ptypes) {
      Iterator<TypeDeclaration> subTypes = sys.getSubTypesOfType(enclType);
      while (subTypes.hasNext()) {
         TypeDeclaration subType = subTypes.next();

         Object result = subType.declaresMethod(name, ptypes, null, enclType, false);
         if (result instanceof MethodDefinition)
            res.add(result);

         addOverridingMethods(sys, subType, res, ptypes);
      }
   }

   public boolean isAbstractMethod() {
      return body == null;
   }

   public Object getInferredReturnType() {
      Object retType = null;
      if (body != null) {
         ArrayList<Statement> returns = new ArrayList<Statement>();
         body.addReturnStatements(returns);
         if (returns.size() > 0) {
            for (int i = 0; i < returns.size(); i++) {
               ReturnStatement ret = (ReturnStatement) returns.get(i);
               if (ret.expression != null) {
                  Object newRetType = ret.expression.getTypeDeclaration();
                  if (newRetType != null && newRetType != NullLiteral.NULL_TYPE) {
                     if (retType == null)
                        retType = newRetType;
                     else
                        retType = ModelUtil.findCommonSuperClass(newRetType, retType);
                  }
               }
            }
         }
      }
      return retType;
   }

   /** Does this method's body get stripped out during the transform? */
   public boolean suppressInterfaceMethod() {
      return body != null && !hasModifier("static") && !hasModifier("default");
   }
}
