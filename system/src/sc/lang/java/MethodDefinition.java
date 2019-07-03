/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.dyn.DynUtil;
import sc.lang.ILanguageModel;
import sc.lang.SemanticNodeList;
import sc.lang.template.GlueStatement;
import sc.lang.template.Template;
import sc.layer.LayeredSystem;
import sc.obj.IObjectId;
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

   /** Gets set to true when you use the override keyword to set an annotation on a method, perhaps as part of an annotation layer. */
   public boolean override;
  
   public transient String propertyName = null;
   public transient PropertyMethodType propertyMethodType;
   public transient Object superMethod; // A reference to the method we override if any from the extends class of this method's type
   public transient Object ifaceMethod; // A reference to the method we override from any interface implemented by this type
   public transient boolean isMain;
   public transient boolean dynamicType;
   public transient boolean overridesCompiled;
   public transient boolean needsDynAccess = false;   // For properties defined via get/set, we still access them in the DynType as properties.
   public transient Object mainSettings = null; // Value of the annotation if this is a MainSettings method

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

      if (name != null) {
         JavaType[] paramJavaTypes = parameters == null ? null : parameters.getParameterJavaTypes(true);
         if (type != null) {
            if ((propertyName = ModelUtil.isGetMethod(name, paramJavaTypes, type)) != null)
               propertyMethodType = propertyName.startsWith("i") ? PropertyMethodType.Is : PropertyMethodType.Get;
            else if ((propertyName = ModelUtil.isSetMethod(name, paramJavaTypes, type)) != null) {
               // Note: right now isSetMethod includes both setX(ix, val) and setX(val) so need to do this here
               if (ModelUtil.isSetIndexMethod(name, paramJavaTypes, type) != null)
                  propertyMethodType = PropertyMethodType.SetIndexed;
               else
                  propertyMethodType = PropertyMethodType.Set;
            }
            else if ((propertyName = ModelUtil.isGetIndexMethod(name, paramJavaTypes, type)) != null)
               propertyMethodType = PropertyMethodType.GetIndexed;
         }
         isMain = name.equals("main") && (type == null || type.isVoid()) && paramJavaTypes != null && paramJavaTypes.length == 1 &&
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

      if (override && name != null) {
         Object prevMethod = getPreviousDefinition();
         if (prevMethod == null) {
            displayTypeError("No method: " + name + " for override: ");
         }
      }

      if (body != null) {
         Object retType = getTypeDeclaration();
         if (retType != null && !ModelUtil.typeIsVoid(retType)) {
            Statement last = body.statements == null || body.statements.size() == 0 ? null : body.statements.get(body.statements.size() - 1);
            ArrayList<Statement> ret = null;
            if (last != null) {
               ret = new ArrayList<Statement>();
               // TODO: rather than gather up all return statements, we should only get the ones for 'exit paths' - i.e. nothing that's followed by another valid statement
               last.addReturnStatements(ret, true);
            }
            // When you do have a glue statement as the method body, it stands in place of the return <string>
            if (!(last instanceof GlueStatement)) {
               if (ret == null || ret.size() == 0 || isEmptyReturn(ret.get(ret.size() - 1))) {
                  // The start and end index here are just stored in the ErrorRangeInfo and used by the IDE to mark the end of the method, rather than the name of the method
                  displayRangeError(1, 1,  false,"Missing return statement: ");
               }
            }
         }
      }
      super.start();

      // If we've been replaced by another method on this same type, let that method do the processing since it will
      // have the completed merged annotations
      Object mainSettings = replacedByMethod == null ? getAnnotation("sc.obj.MainSettings") : null;
      if (mainSettings != null) {
         Boolean disabled = (Boolean) ModelUtil.getAnnotationValue(mainSettings, "disabled");
         if (disabled == null || !disabled) {
            this.mainSettings = mainSettings;

            // Note: used to also check that the class is public but turns out that's not required - just for the method to be public
            if (!ModelUtil.hasModifier(this, "public"))
               displayError("Method with @MainSettings must be public for: ");
         }
      }
   }

   private static boolean isEmptyReturn(Object retObj) {
      // Not returning at all!
      if (retObj instanceof ThrowStatement)
         return false;
      if (!(retObj instanceof ReturnStatement))
         return true;

      ReturnStatement ret = (ReturnStatement) retObj;
      return ret.expression == null;
   }

   public void validate() {
      if (validated) return;

      TypeDeclaration methodType = getEnclosingType();

      // We are probably just a fragment so none of this stuff is required.
      if (methodType == null) {
         super.validate();
         return;
      }
      // TODO: For the layer type itself, not sure how or whether we should inherit methods from downstream layers.  Right now, the derived type will be Layer.class which is the base at least.
      Object extendsType = methodType.isLayerType ? null : methodType.getExtendsTypeDeclaration();
      Object modType = methodType.getDerivedTypeDeclaration();
      if (extendsType == null)
         extendsType = Object.class;
      Object overridden = name == null ? null : ModelUtil.definesMethod(extendsType, name, getParameterList(), null, null, false, false, null, null, getLayeredSystem());
      superMethod = overridden;

      boolean inDynamicType = false;

      /* Dynamic methods need to find any overridden method and make sure calls to that one are also made dynamic.
       * This is here in the validate because the isDynamic method on our parents is not valid until we've propagated
       * the dynamicType flag through the type hierarchy.
       */
      if (isDynamicType()) {
         inDynamicType = true;
         if (overridden != null) {
            if (ModelUtil.isCompiledMethod(overridden)) {
               // If we inherit this method through a dynamic stub, that type will have already made a stub for this method.
               // We can't have one dynamic stub extending another one because of the _super_x methods - there can be only one of them in the type hierarchy.  This
               // basically requires us to only have one dynamic stub
               if (!methodType.getSuperIsDynamicStub())
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
               enclType.addPropertyToMakeBindable(propertyName, this, null, false, this);
         }
      }

      Object[] ifaces = methodType.getAllImplementsTypeDeclarations();

      // We can't modify an interface method but if we override a method in a compile interface we
      // need that interface in the dynamic stub of the enclosing type.
      if (ifaces != null) {
         Object implMeth;
         LayeredSystem sys = getLayeredSystem();
         for (Object impl:ifaces) {
            implMeth = name == null ? null : ModelUtil.definesMethod(impl, name, getParameterList(), null, null, false, false, null, null, sys);
            if (implMeth != null) {
               if (ifaceMethod == null) {
                  ifaceMethod = implMeth;
               }
               // else - TODO: multiple values for ifaceMethod: do we need to pick 'the first' one if we could have inherited it from multiple interfaces?  Or should we store a list here?
               if (inDynamicType && overridden == null && ModelUtil.isCompiledMethod(implMeth)) {
                  methodType.setNeedsDynamicStub(true);
                  overridesCompiled = true;
               }
            }
         }
      }

      // TODO: shouldn't this be moved to the start method?
      if (modType != extendsType && name != null) {
         overridden = ModelUtil.definesMethod(modType, name, getParameterList(), null, null, false, false, null, null, getLayeredSystem());
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
      overrides = overriddenMethod;
      super.validate();
   }

   public Object definesMember(String name, EnumSet<MemberType> mtype, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed) {
      if (!isInitialized())
         init();
      if (propertyName != null && propertyName.equals(name) &&
          ((mtype.contains(MemberType.GetMethod) && (propertyMethodType == PropertyMethodType.Get ||
                                                     propertyMethodType == PropertyMethodType.Is)) ||
          (mtype.contains(MemberType.SetMethod) && propertyMethodType == PropertyMethodType.Set) ||
          (mtype.contains(MemberType.GetIndexed) && propertyMethodType == PropertyMethodType.GetIndexed) ||
          (mtype.contains(MemberType.SetIndexed) && propertyMethodType == PropertyMethodType.SetIndexed)))
         return this;
      return super.definesMember(name, mtype, refType, ctx, skipIfaces, isTransformed);
   }

   public String getVariableName() {
      return propertyName;
   }

   /** The method's return type */
   public Object getTypeDeclaration() {
      if (override && type == null) {
         Object prev = getPreviousDefinition();
         if (prev instanceof MethodDefinition)
            return ((MethodDefinition) prev).getTypeDeclaration();
         else if (prev != null)
            return ModelUtil.getReturnType(prev, true);
      }
      Object res = type == null ? null : type.getTypeDeclaration();
      if (res != null && arrayDimensions != null) {
         res = new ArrayTypeDeclaration(getLayeredSystem(), getEnclosingType(), type, arrayDimensions);
      }
      return res;
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
         else if (prev != null)
            return ModelUtil.getReturnType(prev, resolve);
      }

      Object returnType = getTypeDeclaration();
      if (typeParameters == null)
         return returnType;

      return returnType;
   }

   public String getPropertyName() {
      return propertyName;
   }

   class JavaCommandInfo {
      String javaArgs, restartJavaArgs, sharedArgs, debugArgs;
      Boolean produceJar;
      String jarFileName;
      Boolean produceScript;
      Boolean produceBAT;
      String execCommandTemplateName;
      String execBATTemplateName;
      Boolean debug;
      Integer debugPort;
      Boolean debugSuspend;
      String execName;
      String defaultArgs;
      Integer maxMem;
      Integer minMem;
      String scriptSuffix;
      Boolean includeDepsInJar;
      String stopMethod;

      JavaModel model;
      String fullTypeName;
      LayeredSystem lsys;

      String shellType;

      private void initSettings(Object mainSettings, JavaModel model, String fullTypeName) {
         lsys = model.getLayeredSystem();
         this.model = model;
         this.fullTypeName = fullTypeName;
         produceJar = (Boolean) ModelUtil.getAnnotationValue(mainSettings, "produceJar");
         jarFileName = (String) ModelUtil.getAnnotationValue(mainSettings, "jarFileName");
         produceScript = (Boolean) ModelUtil.getAnnotationValue(mainSettings, "produceScript");
         produceBAT = (Boolean) ModelUtil.getAnnotationValue(mainSettings, "produceBAT");
         execCommandTemplateName = (String) ModelUtil.getAnnotationValue(mainSettings, "execCommandTemplate");
         if (execCommandTemplateName != null && execCommandTemplateName.equals(""))
            execCommandTemplateName = null;
         execBATTemplateName = (String) ModelUtil.getAnnotationValue(mainSettings, "execBATTemplate");
         if (execBATTemplateName != null && execBATTemplateName.equals(""))
            execBATTemplateName = null;
         debug = (Boolean) ModelUtil.getAnnotationValue(mainSettings, "debug");
         debugPort = (Integer) ModelUtil.getAnnotationValue(mainSettings, "debugPort");
         debugSuspend = (Boolean) ModelUtil.getAnnotationValue(mainSettings, "debugSuspend");
         execName = (String) ModelUtil.getAnnotationValue(mainSettings, "execName");
         defaultArgs = (String) ModelUtil.getAnnotationValue(mainSettings, "defaultArgs");
         if (defaultArgs == null)
            defaultArgs = "";
         maxMem = (Integer) ModelUtil.getAnnotationValue(mainSettings, "maxMemory");
         minMem = (Integer) ModelUtil.getAnnotationValue(mainSettings, "minMemory");
         includeDepsInJar = (Boolean) ModelUtil.getAnnotationValue(mainSettings, "includeDepsInJar");
         stopMethod = (String) ModelUtil.getAnnotationValue(mainSettings, "stopMethod");
         if (stopMethod != null && stopMethod.length() == 0)
            stopMethod = null;
      }

      /**
       * The defaultArgs can be a template against this object.  It may need a temp file and can get one portably through this
       * method.
       */
      public String getTempDir(String baseName, String suffix) {
         if (shellType.equals("sh")) {
            return "/tmp/" + baseName + "$$" + "." + suffix;
         }
         else if (shellType.equals("bat")) {
            return "%TEMP%\\" + baseName + "%SCPID%" + "." + suffix;
         }
         else
            throw new UnsupportedOperationException();
      }

      private String varString(String varName) {
         if (shellType.equals("sh"))
            return "$" + varName;
         else if (shellType.equals("bat"))
            return '%' + varName + '%';
         else
            throw new UnsupportedOperationException();
      }

      private String argsString() {
         if (shellType.equals("sh"))
            return "$*";
         else if (shellType.equals("bat"))
            return "%*";
         else
            throw new UnsupportedOperationException();
      }

      private void setShellType(String shellType, boolean addMain) {
         this.shellType = shellType;
         scriptSuffix = "";
         String extraArgs = defaultArgs;
         if (defaultArgs != null && defaultArgs.length() > 0) {
            if (defaultArgs.length() > 0 && !defaultArgs.startsWith(" ")) {
               try {
                  extraArgs = " " + TransformUtil.evalTemplate(this, defaultArgs, false);
               }
               catch (IllegalArgumentException exc) {
                  displayError("Failed to parse defaultArgs as a template string: " + defaultArgs);
               }
            }
         }
         if (execName == null || execName.length() == 0) {
            // Exec name is a normalized path name since it comes from configuration
            execName = model.getModelTypeName().replace('.', '/');
            scriptSuffix = "." + shellType;
         }
         // jarFileName lets you override the jar name used.  Defaults to execName.
         if (jarFileName == null || jarFileName.length() == 0)
            jarFileName = FileUtil.addExtension(execName, "jar");
         debugArgs = debug != null && debug ?
                 " -Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=" + (debugSuspend == null || !debugSuspend ? "n" : "y") +  ",address=" + (debugPort == null ? "5005": debugPort)  :
                 "";

         String dynStr = "";
         if (lsys.hasDynamicLayers()) {
            dynStr = "sc.layer.LayeredSystem " + lsys.getLayerNames() + " -lp " + lsys.getLayerPath() + " -r ";
         }

         String sepStr = shellType.equals("bat") ? "\\" : "/";

         String memStr = (minMem != null && minMem != 0 ? " -ms" + minMem + "m ": "") + (maxMem != null && maxMem != 0 ? " -mx" + maxMem + "m " : "");
         String vmParams = lsys.getVMParameters();
         String[] defaultArgList = defaultArgs.length() == 0 ? null : defaultArgs.trim().split(" ");
         // TODO: put this code into a configurable template
         if (produceJar != null && produceJar) {
            // TODO: need to find a way to inject command line args into the jar process
            if (dynStr.length() > 0)
               System.err.println("*** Unable to produce script with jar option for dynamic layers");
            if (addMain)
               lsys.buildInfo.addModelJar(model, model.getModelTypeName(), jarFileName, null, false, includeDepsInJar == null || includeDepsInJar);
            sharedArgs =  memStr + vmParams +
                    " -jar \"" + varString("DIRNAME") + sepStr + getFileNamePart(jarFileName, "/") + "\"";
         }
         else {
            if (addMain)
               lsys.buildInfo.addMainCommand(model, execName, defaultArgList, stopMethod);
            sharedArgs = vmParams + memStr + " -cp \"" + lsys.userClassPath + "\" " + dynStr + fullTypeName + " ";
         }
         sharedArgs += extraArgs;
         javaArgs = sharedArgs + " " + argsString();
         restartJavaArgs = sharedArgs + " -restart";
      }

   }

   JavaCommandInfo createJavaCommandInfo(Object mainSettings, JavaModel model, String fullTypeName) {
      JavaCommandInfo ci = new JavaCommandInfo();
      ci.initSettings(mainSettings, model, fullTypeName);
      return ci;
   }

   public static String getFileNamePart(String pathName, String sep) {
      while (pathName.endsWith(sep))
         pathName = pathName.substring(0, pathName.length() - 1);

      int ix = pathName.lastIndexOf(sep);
      if (ix != -1)
         pathName = pathName.substring(ix+1);

      return pathName;
   }

   public void process() {
      if (processed) return;

      JavaModel model;
      if (isMain && (model = getJavaModel()) != null) {
         // If we've been replaced by another method on this same type, let that method do the processing since it will
         // have the completed merged annotations
         if (mainSettings != null) {

               LayeredSystem lsys = getJavaModel().getLayeredSystem();

               String fullTypeName = getEnclosingType().getFullTypeName();
               JavaCommandInfo ci = createJavaCommandInfo(mainSettings, model, fullTypeName);
               ci.setShellType("sh", true);

               // TODO: should we make this more flexible?
               boolean doRestart = fullTypeName.contains("LayeredSystem");

               if (ci.produceScript != null && ci.produceScript) {
                  String runScriptFile = FileUtil.concat(lsys.buildDir, ci.execName + ci.scriptSuffix);
                  String startCommand;

                  Template templ = null;
                  if (ci.execCommandTemplateName != null)
                     templ = getEnclosingType().findTemplatePath(ci.execCommandTemplateName, "exec command template", ExecCommandParameters.class);
                  if (templ == null) {
                     String restartCommands = !doRestart ? "" :
                                     "while [ \"$?\" = \"33\" ] ; do" +
                                     FileUtil.LINE_SEPARATOR +
                                     "   java" + ci.restartJavaArgs +
                                     FileUtil.LINE_SEPARATOR +
                                     "SC_EXIT_STATUS=$?" +
                                     FileUtil.LINE_SEPARATOR +
                                     "done" +
                                     FileUtil.LINE_SEPARATOR;

                     boolean includeDebug = ci.debug != null && ci.debug;

                     String debugCommands = !includeDebug ? "# @MainSettings(debug not enabled)" :
                                     "DBG_ARGS=" +
                                     FileUtil.LINE_SEPARATOR +
                                     "if [ \"$1\" == \"-dbg\" ] ; then" +
                                     FileUtil.LINE_SEPARATOR +
                                     "   DBG_ARGS=\"" + ci.debugArgs + "\"" +
                                     FileUtil.LINE_SEPARATOR +
                                     "fi" +
                                     FileUtil.LINE_SEPARATOR;

                     String debugArgs = !includeDebug ? "" : " ${DBG_ARGS}";

                     startCommand = "#!/bin/sh\n" +
                                    "# This file is generated by the MainSettings annotation on class " + fullTypeName + " - warning changes made here will be lost" +
                                    FileUtil.LINE_SEPARATOR +
                                    // This sometimes allows us to kill the main script and have it kill the java process
                                    // For some reason, it does not work when we try to kill it from a test script
                                    // so instead, we add in a layer to have the process exit when it's idle.  It's
                                    // probably better than killing it from a reliability standpoint anyway.
                                    //"trap \"kill -- $$\" EXIT SIGINT SIGTERM\n" +
                                    //FileUtil.LINE_SEPARATOR +
                                    "CMDPATH=\"`type -p $0`\"" +
                                    FileUtil.LINE_SEPARATOR +
                                    "DIRNAME=`dirname $CMDPATH`" +
                                    FileUtil.LINE_SEPARATOR + debugCommands +
                                    FileUtil.LINE_SEPARATOR +
                                    "java" + debugArgs + ci.javaArgs +
                                    FileUtil.LINE_SEPARATOR +
                                    "SC_EXIT_STATUS=$?" +
                                    FileUtil.LINE_SEPARATOR +
                                    restartCommands +
                                    "exit $SC_EXIT_STATUS" +
                                    FileUtil.LINE_SEPARATOR;
                  }
                  else {
                     ExecCommandParameters params = new ExecCommandParameters();
                     params.javaArgs = ci.javaArgs;
                     params.fullTypeName = fullTypeName;
                     params.restartJavaArgs = ci.restartJavaArgs;

                     startCommand = TransformUtil.evalTemplate(params, templ);
                  }

                  // TODO: include comment about not modifying, set exec bit
                  FileUtil.saveStringAsFile(runScriptFile, startCommand, true);
                  new File(runScriptFile).setExecutable(true, true);
               }

               ci.setShellType("bat", false);

               if (ci.produceBAT != null && ci.produceBAT) {
                  String runBATFile = FileUtil.concat(lsys.buildDir, ci.execName + ".bat");
                  String startBAT;

                  Template templ = null;
                  if (ci.execBATTemplateName != null)
                     templ = getEnclosingType().findTemplatePath(ci.execBATTemplateName, "exec command template", ExecCommandParameters.class);

                  if (templ == null) {
                     String restartBAT = !doRestart ? "" :
                             "if %SC_EXIT_STATUS% neq 33 goto exitsc\r\n" +
                                     ":tryagain\r\n" +
                                     "java" + ci.restartJavaArgs + "\r\n" +
                                     "set SC_EXIT_STATUS=%errorlevel%\r\n" +
                                     "if %SC_EXIT_STATUS% equ 33 goto tryagain\r\n" +
                                     ":exitsc\r\n";
                     startBAT = "@ECHO off\r\n" +
                             "SETLOCAL ENABLEEXTENSIONS\r\n" +
                             // No way I could find to get a process id of the bat file so just use a random number for the temp file
                             "SET SCPID=%RANDOM%\r\n" +
                             "SET DIRNAME=%~dp0\r\n" +
                             "java" + ci.javaArgs + "\r\n" +
                             restartBAT +
                             "EXIT /B %SC_EXIT_STATUS%\r\n";
                  }
                  else {
                     ExecCommandParameters params = new ExecCommandParameters();
                     params.javaArgs = ci.javaArgs;
                     params.fullTypeName = fullTypeName;
                     params.restartJavaArgs = ci.restartJavaArgs;

                     startBAT = TransformUtil.evalTemplate(params, templ);
                  }

                  // TODO: include comment about not modifying, set exec bit
                  FileUtil.saveStringAsFile(runBATFile, startBAT, true);
                  new File(runBATFile).setExecutable(true, true);

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

   public boolean hasSetIndexMethod() {
      return isSetIndexMethod() || getEnclosingType().definesMember(propertyName, MemberType.SetIndexMethodSet, null, null) != null;
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

   public Object getFieldFromGetSetMethod() {
      if (isSetMethod() || isGetMethod())
         return getEnclosingType().definesMember(propertyName, MemberType.FieldSet, null, null);
      return null;
   }

   public boolean isGetMethod() {
      return propertyName != null && name != null && ((name.startsWith("get") || name.startsWith("is")) || (origName != null && (origName.startsWith("get") || origName.startsWith("is"))));
   }

   public boolean isSetMethod() {
      // Make sure to include the orig name in case we do the _bind_set transformation
      return propertyName != null && name != null && (name.startsWith("set") || (origName != null && origName.startsWith("set")));
   }

   public boolean isGetIndexMethod() {
      return propertyMethodType == PropertyMethodType.GetIndexed;
   }

   public boolean isSetIndexMethod() {
      return propertyMethodType == PropertyMethodType.SetIndexed;
   }

   @Override
   public boolean isConstructor() {
      return false;
   }

   public Object getReturnType(boolean boundParams) {
      return getTypeDeclaration(null, boundParams);
   }

   public Object getPreviousDefinition() {
      BodyTypeDeclaration btd = getEnclosingType();
      Object base = btd.getDerivedTypeDeclaration();
      Object ext = btd.getExtendsTypeDeclaration();

      Object res;
      if (ext != null) {
         res = ModelUtil.definesMethod(ext, name, getParameterList(), null, null, false, false, null, null, getLayeredSystem());
         if (res != null)
            return res;
      }
      if (ext != base && base != null) {
         res = ModelUtil.definesMethod(base, name, getParameterList(), null, null, false, false, null, null, getLayeredSystem());
         if (res != null)
            return res;
      }
      return null;
   }

   public Object callVirtual(Object thisObj, Object... values) {
      if (propertyName != null) {
         Object type = thisObj == null ? getEnclosingType() : DynUtil.getType(thisObj);

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
               return mapper.getPropertyValue(thisObj, false);
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

   public AccessLevel getAccessLevel(boolean explicitOnly) {
      AccessLevel level = super.getAccessLevel(explicitOnly);
      if (level == null && override) {
         Object prev = getPreviousDefinition();
         if (prev != null)
            return ModelUtil.getAccessLevel(prev, explicitOnly);
      }
      return level;
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
         res.overriddenMethod = overriddenMethod;
         res.overrides = overrides;
      }
      return res;
   }

   public List<Object> findOverridingMethods() {
      ArrayList<Object> res = new ArrayList<Object>();
      TypeDeclaration enclType = getEnclosingType();
      LayeredSystem sys = getLayeredSystem();
      List<?> parameterTypes = getParameterList();

      HashSet<Object> visited = new HashSet<Object>();

      if (sys == null)
         return null;
      addOverridingMethods(sys, enclType, res, parameterTypes, visited);

      return res;
   }

   private boolean resultListContainsMethod(ArrayList<Object> res, Object overMeth) {
      for (int i = 0; i < res.size(); i++) {
         if (ModelUtil.sameMethods(res.get(i), overMeth))
            return true;
      }
      return false;
   }

   private void addOverridingMethods(LayeredSystem sys, BodyTypeDeclaration enclType, ArrayList<Object> res, List<? extends Object> ptypes, HashSet<Object> visited) {
      if (name == null)
         return;
      ArrayList<BodyTypeDeclaration> modTypes = sys.getModifiedTypesOfType(enclType, false, false);
      if (modTypes != null) {
         for (BodyTypeDeclaration modType:modTypes) {
            if (visited.contains(modType))
               continue;
            visited.add(modType);
            Object overMeth = ModelUtil.definesMethod(modType, name, getParameterList(), null, null, false, false, null, null, getLayeredSystem());
            // Instead of overMeth != this it should be sameMethodsInLayers - i.e. where we compare the method's enclosing type's type-name and layer since we know the parameters and name match
            if (overMeth != null && !resultListContainsMethod(res, overMeth) && overMeth != this && !ModelUtil.sameMethodInLayer(sys, overMeth, this))
               res.add(overMeth);
         }
      }
      Iterator<BodyTypeDeclaration> subTypes = sys.getSubTypesOfType(enclType, enclType.getLayer(), false, true, false, false);
      while (subTypes.hasNext()) {
         BodyTypeDeclaration subType = subTypes.next();
         if (subType == enclType) {
            System.err.println("*** Loop in sub-type hierarchy");
            return;
         }

         if (visited.contains(subType))
            continue;
         visited.add(subType);

         // In this case, we need to consider all overriding methods - including those in modified types
         Object result = subType.declaresMethod(name, ptypes, null, enclType, false, false, null, null, false);
         if (result instanceof MethodDefinition && !resultListContainsMethod(res, result)) {
            res.add(result);
         }
         BodyTypeDeclaration modType = subType.getModifiedType();
         do {
            // If we hit this same type in the list of modified types need to stop since anything below this type is not an overriding method
            if (modType == null || ModelUtil.sameTypes(modType, enclType) && modType.layer.getLayerName().equals(subType.layer.getLayerName()))
               break;
            result = modType.declaresMethod(name, ptypes, null, enclType, false, false, null, null, false);
            if (result instanceof MethodDefinition && !resultListContainsMethod(res, result))
               res.add(result);
            modType = modType.getModifiedType();
         } while (true);

         addOverridingMethods(sys, subType, res, ptypes, visited);
      }
   }

   public boolean isAbstractMethod() {
      return body == null;
   }

   public static final String UNRESOLVED_INFERRED_TYPE = new String("<unresolved-inferred-type>");

   public Object getInferredReturnType(boolean bodyOnly) {
      Object infRetType = null;
      if (body != null) {
         ArrayList<Statement> returns = new ArrayList<Statement>();
         body.addReturnStatements(returns, false);
         if (returns.size() > 0) {
            for (int i = 0; i < returns.size(); i++) {
               ReturnStatement ret = (ReturnStatement) returns.get(i);
               if (ret.expression != null) {
                  Object newRetType = ret.expression.getGenericType();
                  if (newRetType != null) {
                     if (infRetType == null) {
                        //infRetType = ModelUtil.findCommonSuperClass(newRetType, getTypeDeclaration());
                        infRetType = newRetType;
                     }
                     else if (!ModelUtil.isTypeVariable(newRetType))
                        infRetType = ModelUtil.findCommonSuperClass(getLayeredSystem(), newRetType, infRetType);
                  }
                  else
                     return UNRESOLVED_INFERRED_TYPE;
               }
            }
         }
      }
      // When matching for lambdas, we want to make sure the body's return type matches the method's so need to skip
      // the logic to factor in the method's real return type.
      if (bodyOnly)
         return infRetType;
      Object retType = getTypeDeclaration();
      // Make sure the inferredType is more specific than the actual return type
      if (retType == null || (infRetType != null && infRetType != NullLiteral.NULL_TYPE && ModelUtil.isAssignableFrom(retType, infRetType)))
         return infRetType;
      return retType;
   }

   /** Does this method's body get stripped out during the transform? */
   public boolean suppressInterfaceMethod() {
      return body != null && !hasModifier("static") && !hasModifier("default");
   }

   public void addMembersByName(Map<String,List<Statement>> membersByName) {
      if (propertyName != null) {
         addMemberByName(membersByName, propertyName);
      }
   }

   public Object getAnnotation(String annotation) {
      Object thisAnnot = super.getAnnotation(annotation);
      if (overriddenMethod != null) {
         Object overriddenAnnotation = ModelUtil.getAnnotation(overriddenMethod, annotation);

         if (thisAnnot == null) {
            return overriddenAnnotation == null ? null : Annotation.toAnnotation(overriddenAnnotation);
         }
         if (overriddenAnnotation == null)
            return thisAnnot;

         thisAnnot = ModelUtil.mergeAnnotations(thisAnnot, overriddenAnnotation, false);
         return thisAnnot;
      }
      return thisAnnot;
   }

   public Object[] getExtraModifiers() {
      TypeDeclaration enclType = getEnclosingType();
      // Methods that are part of an interface are implicitly public in Java
      if (enclType != null && enclType.getDeclarationType() == DeclarationType.INTERFACE) {
         return new Object[] {"public"};
      }
      return null;
   }

   public void stop() {
      super.stop();
      superMethod = null;
      isMain = false;
      dynamicType = false;
      overridesCompiled = false;
      needsDynAccess = false;
   }

   public String getReturnTypeName() {
      Object type = getReturnType(false);
      if (type != null)
         return ModelUtil.getTypeName(type);
      return null;
   }
}
