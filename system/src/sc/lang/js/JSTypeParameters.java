/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.js;

import sc.dyn.RDynUtil;
import sc.lang.ISemanticNode;
import sc.lang.ISrcStatement;
import sc.lang.SemanticNodeList;
import sc.lang.java.*;
import sc.lang.sc.ModifyDeclaration;
import sc.lang.sc.PropertyAssignment;
import sc.layer.LayeredSystem;
import sc.layer.SrcEntry;
import sc.parser.FormatContext;
import sc.parser.GenFileLineIndex;
import sc.parser.ParseUtil;
import sc.sync.SyncLayer;
import sc.sync.SyncProperties;
import sc.type.CTypeUtil;
import sc.type.MethodBindSettings;
import sc.util.FileUtil;
import sc.util.PerfMon;
import sc.util.StringUtil;

import java.lang.annotation.RetentionPolicy;
import java.util.*;

/**
 * An instance of this class is created to convert a Java type to a JS type.  It's available to the JSTypeTemplate
 * which is used to do the actual conversion and supplies the model-methods to make that somewhat convenient.
 */
public class JSTypeParameters extends ObjectTypeParameters {
   public BodyTypeDeclaration type;
   private JSMethod[] jsTypeMethods;
   /** Is this a merge operation - i.e. either a sync or a type update using the "merged type" - i.e. the model in the model stream with just the changes */
   public boolean mergeTemplate = false;
   /** Is this a sync operation - used for sharing templates between sync and type update */
   public boolean syncTemplate = false;

   /** IS this a type-update operation.  Once we've processed the UpdateMerge template with the changes, we process the new type with this template to extract the code parts we need to send to the client */
   public boolean updateTemplate = false;

   List<JSStatement> jsPreInitStatements;
   List<JSStatement> jsInitStatements = null;
   public boolean needsConstructor = true;

   /** When mergeTemplate is true, we are updating the type and so only need to include the changed methods */
   public TreeSet<String> changedMethods = null;

   public GenFileLineIndex lineIndex = null;

   public JSTypeParameters() {
   }

   public void init(BodyTypeDeclaration type) {
      super.init(type.getLayeredSystem(), type);
      this.type = type;
   }

   public JSTypeParameters(BodyTypeDeclaration td) {
      super(td.getLayeredSystem(), td);
      type = td;
      if (td.changedMethods != null)
         changedMethods = td.changedMethods;
   }

   static AbstractMethodDefinition[] createJSMeths(AbstractMethodDefinition[] meths, BodyTypeDeclaration type) {
      AbstractMethodDefinition[] jsMeths;
      if (meths == null)
         jsMeths = null;
      else {
         jsMeths = new AbstractMethodDefinition[meths.length];
         for (int i = 0; i < jsMeths.length; i++) {
            AbstractMethodDefinition meth = meths[i];
            if (ModelUtil.sameTypes(type, meth.getEnclosingType()))
               jsMeths[i] = (AbstractMethodDefinition) JSUtil.convertToJS(meth);
            else
               jsMeths[i] = meth;
         }
      }
      return jsMeths;
   }

   public class JSMethod {
      AbstractMethodDefinition[] methods;
      AbstractMethodDefinition defaultMethod;
      AbstractMethodDefinition[] jsMethods;
      AbstractMethodDefinition jsDefaultMethod;
      ArrayList<List<JSMethod>> methsByParamNum;
      boolean constructor = false;

      JSMethod(AbstractMethodDefinition[] meths, AbstractMethodDefinition[] jsMeths) {
         methods = meths;
         jsMethods = jsMeths;
         if (meths != null) {
            defaultMethod = meths[0];
            jsDefaultMethod = jsMeths[0];
         }
      }

      public boolean isVarArgs() {
         return defaultMethod != null && defaultMethod.isVarArgs();
      }

      /**
       * Allows the code generation to handle the case where there's a getX(foo) method.  The runtime needs a way to tell that there's no getX() method so we'll
       * inject code to return undefined if arguments.length == 0.   When there are variable parameter numbers, we already check the parameters and will return undefined
       * cause no case will match.
       */
      public boolean isGetWithArgs() {
         boolean val = defaultMethod != null && ((defaultMethod.name.startsWith("get") && defaultMethod.name.length() > 3) ||
                                          (defaultMethod.name.startsWith("is") && defaultMethod.name.length() > 2)) && defaultMethod.getNumParameters() > 0 &&
                                          !getVariableParamNum();
         if (val)
            return true;
         return false;
      }

      public String getVarArgsParamName() {
         List<Parameter> params = jsDefaultMethod.getParameterList();
         int sz = params.size();
         return params.get(sz-1).variableName;
      }

      public boolean isAnyVarArgs() {
         if (defaultMethod != null && defaultMethod.isVarArgs())
            return true;
         if (methods == null)
            return false;
         for (AbstractMethodDefinition meth:methods)
            if (meth.isVarArgs())
               return true;
         return false;
      }

      JSMethod(AbstractMethodDefinition[] meths) {
         this(meths, createJSMeths(meths, type));
      }

      public String getName() {
         return jsDefaultMethod.name;
      }

      public boolean getNeedsClassInit() {
         return defaultMethod != null && defaultMethod.isStatic() && JSTypeParameters.this.getNeedsClassInit();
      }

      public MethodBindSettings getBindSettings() {
         MethodBindSettings ret = RDynUtil.getMethodBindSettings(defaultMethod);
         return ret;
      }

      public boolean isInherited() {
         return !ModelUtil.sameTypes(type, defaultMethod.getEnclosingType());
      }

      public String getDefaultSuperConstr() {
         if (constructor) {
            Object superMethod = null;

            if (defaultMethod == null) {
               superMethod = type.declaresConstructor(null, null);
            }
            else if (!defaultMethod.callsSuper()) {
               superMethod = ModelUtil.getSuperMethod(defaultMethod);
            }

            if (superMethod != null) {
               Object superType = ModelUtil.getEnclosingType(superMethod);
               String superTypeName = JSUtil.convertTypeName(type.getLayeredSystem(), ModelUtil.getTypeName(superType));
               Object extType = type.getExtendsTypeDeclaration();
               int superCt = superType == null ? 0 : ModelUtil.getOuterInstCount(superType);

               int ct = extType != null ? ModelUtil.getOuterInstCount(extType) : 0;

               StringBuilder toAdd = new StringBuilder();
               if (superCt < ct && extType != null) {
                  Object thisEnclType = ModelUtil.getEnclosingType(type);
                  Object extEnclType = ModelUtil.getEnclosingType(extType);
                  while (extEnclType != null && thisEnclType != null && !ModelUtil.isAssignableFrom(extEnclType, thisEnclType)) {
                     toAdd.append(".outer");
                     thisEnclType = ModelUtil.getEnclosingType(thisEnclType);
                  }
               }

               return superTypeName + ".call(this" +
                       (ct != 0 ? ", outer" : "") + toAdd + ");\n" + type.getIndentStr() + FormatContext.INDENT_STR;
            }
         }
         return null;
      }

      public boolean isClassMethod() {
         if (defaultMethod == null)
            return false;
         return classMethods.contains(defaultMethod.name);
      }

      public String getMethodBody(int indent) {
         PerfMon.start("getJSMethodBody"); try {
         StringBuilder sb = new StringBuilder();

         boolean superCalled = false; // Have we found that the super has been called explicitly/
         boolean superAppended = false; // Have we appended a super call yet
         String constructorInit = null;

         LayeredSystem sys = type.getLayeredSystem();
         if (constructor) {
            String defaultSuper = getDefaultSuperConstr();
            if (((defaultSuper == null && defaultMethod == null) || methods == null || (methods.length == 1 && !methods[0].callsSuper()) && !methods[0].callsThis())) {
               // Even if there's no constructor, we still need to insert a call to the base class to run any initializers
               Object extType = type.getDerivedTypeDeclaration();
               if (extType != null) {
                  sb.append(StringUtil.indent(indent));
                  sb.append(JSUtil.convertTypeName(type.getLayeredSystem(), ModelUtil.getTypeName(extType)) + ".call(this");
                  int superCt = ModelUtil.getOuterInstCount(extType);
                  StringBuilder toAdd = new StringBuilder();
                  int thisCt = ModelUtil.getOuterInstCount(type);
                  if (superCt > 0) {
                     if (thisCt > superCt) {
                        Object thisEnclType = ModelUtil.getEnclosingType(type);
                        // Get the type of the outer type for the super type -
                        Object extEnclType = ModelUtil.getEnclosingType(extType);
                        int curCt = thisCt - 1;
                        // Count the number of levels till we reach this type in the this type's outer types.  Need to use the right class
                        // at each level to be sure we have the right path in the weird cases where you have an inner class which extends another
                        // class inside of the same root class but at a different level in the hierarchy.
                        while (extEnclType != null && thisEnclType != null && !ModelUtil.isAssignableFrom(extEnclType, thisEnclType)) {
                           toAdd.append("._outer");
                           toAdd.append(curCt);
                           thisEnclType = ModelUtil.getEnclosingType(thisEnclType);
                           curCt--;
                        }
                     }
                  }
                  if (ModelUtil.getEnclosingInstType(extType) != null) {
                     sb.append(", this._outer");
                     sb.append(thisCt);
                  }
                  sb.append(toAdd);
                  sb.append(");\n");
                  superCalled = true;
                  superAppended = true;
               }
            }
            else if (methods != null && methods.length == 1 && (methods[0].callsSuper() || methods[0].callsThis()))
               superCalled = true;
            if (defaultSuper != null && !superCalled) {
               sb.append(replaceIndent(indent, defaultSuper));
               superCalled = true;
               superAppended = true;
            }

            if ((methods == null || (methods.length == 1 && !defaultMethod.callsThis())) && getNeedsInstInit()) {
               //if (defaultMethod != null && defaultMethod.callsSuper()) {
               //    Special case handled somewhat awkwardly using the constructorInit and superCalled variables.  Need to figure out where to put the
               //    instance init call.  It should be right after the super.  But the super can come from above, it can come from below.   So we use these
               //    flags to emit the constructorInit call at the right spot.
               //}
               constructorInit = "   this._" + JSUtil.convertTypeName(sys, ModelUtil.getTypeName(type)) + "Init();\n";
               if (superAppended) {
                  addGenLineMapping(type, constructorInit, ParseUtil.countCodeLinesInNode(sb));
                  sb.append(constructorInit);
                  constructorInit = null;
               }
            }
         }
         if (defaultMethod == null || defaultMethod.hasModifier("abstract") || defaultMethod.body == null) {
            if (constructorInit != null) {
               addGenLineMapping(type, constructorInit, ParseUtil.countCodeLinesInNode(sb));
               sb.append(constructorInit);
            }
            return replaceIndent(indent, sb.toString()); // default constructor - there's no actual method and no code to run to init
         }

         // If this is an inherited method, it will always be a sub-method, like in an alias.  Instead of the body return the super call.
         if (isInherited()) {
            StringBuilder superSt = new StringBuilder();
            superSt.append(StringUtil.indent(indent));
            if (!constructor) {
               Object retType = ModelUtil.getReturnJavaType(defaultMethod);
               if (!ModelUtil.typeIsVoid(retType))
                  superSt.append("return ");
            }
            else if (constructorInit != null) {
               superSt.append(constructorInit);
               constructorInit = null;
            }
            Object methEnclType = defaultMethod.getEnclosingType();
            superSt.append(JSUtil.convertTypeName(type.getLayeredSystem(), ModelUtil.getTypeName(methEnclType)));
            superSt.append(getJSRuntimeProcessor().typeNameSuffix);
            superSt.append('.');
            superSt.append(defaultMethod.name);
            boolean firstComma = false;
            if (!ModelUtil.hasModifier(defaultMethod, "static")) {
               superSt.append('.');
               superSt.append("call(this ");
               firstComma = true;
            }
            else {
               superSt.append('(');
            }
            int np = getNumParameters();
            for (int i = 0; i < np; i++) {
               if (i != 0 || firstComma)
                  superSt.append(", ");
               superSt.append("arguments[");
               superSt.append(i);
               superSt.append("]");
            }
            superSt.append(");\n");

            String superStStr = superSt.toString();
            addGenLineMapping(type, superStStr, ParseUtil.countCodeLinesInNode(sb));
            sb.append(superStStr);
            return sb.toString();
         }

         // TODO: should we put a newline on the sb when we start or does that mess up lines for the previous returns here?
         StringBuilder newSb = new StringBuilder();
         newSb.append("\n");
         newSb.append(sb);
         sb = newSb;

         BlockStatement jsBody = jsDefaultMethod.body;

         // TODO: we need to check to be sure that the method signature matches the one that's defined on the class.
         // Weird special case.  For instance methods like hashCode, equals, getName() which exist on the class as well as the instance,
         // we need to detect when we get called with the class and pass it off to the right method.  With JS there's one name space - the object and class
         // name spaces get merged which leads to this weird requirement.
         if (defaultMethod != null && !defaultMethod.isStatic() && isClassMethod()) {
            sb.append("   if (this.hasOwnProperty(\"$protoName\")) {\n");
            sb.append("      return jv_Class" + getJSRuntimeProcessor().typeNameSuffix + "." + defaultMethod.name + ".apply(this, arguments);\n");
            sb.append("   }\n");
         }

         jsDefaultMethod.childNestingDepth = indent;
         boolean needsIndent = true;
         SemanticNodeList<Statement> sts = jsBody.statements;
         if (sts != null) {
            int sz = sts.size();
            for (int s = 0; s < sz; s++) {
               Statement st = sts.get(s);
               // Normally statements will add indent but not always so need to do this manually to stitch together these statements
               String nextStatement = st.formatToJS(JSFormatMode.InstInit, JSTypeParameters.this, ParseUtil.countLinesInNode(sb)).toString();
               if (needsIndent && !nextStatement.startsWith(" "))
                  nextStatement = StringUtil.indent(indent) + nextStatement;
               needsIndent = !nextStatement.endsWith(" ");
               /*
               if (needsIndent && s == sz - 1) {
                  nextStatement = nextStatement.replaceAll("\\s+$", "");
               }
               */
               sb.append(nextStatement);
               if (constructorInit != null && st.callsSuper()) {
                  sb.append(constructorInit);
                  constructorInit = null;
               }
            }
         }
         if (constructorInit != null) {
            addGenLineMapping(type, constructorInit, ParseUtil.countLinesInNode(sb));
            sb.append(constructorInit);
         }
         return sb.toString();
         //return "\n" + replaceIndent(indent, sb.toString());
         }
         finally {
            PerfMon.end("getJSMethodBody");
         }
      }

      public String getMethodClassInit() {
         if (this.getNeedsClassInit()) {
            String methClassInit = "\n   " + getJSTypeName() + "._clInit();";
            // We want to attach this statement in the debugger or else it floats to the previous statement
            addGenLineMapping(getNeedsDispatch() ? type : defaultMethod, methClassInit, 0);
            return methClassInit;
         }
         return "";
      }

      public boolean getNeedsDispatch() {
         if (methods == null)
            return false;
         return methods.length > 1;
      }

      public int getOuterParamOffset() {
         int startCt = 0;
         // We add the "outer" parameter for inner instance classes.  So the JS methods's arguments.length will include that
         if (constructor && type.getEnclosingInstType() != null)
            startCt = 1;
         return startCt;
      }

      public int getNumParameters() {
         if (defaultMethod == null)
            return 0;
         return defaultMethod.getNumParameters();
      }

      public int getNumNonVarArgsParameters() {
         int numParams = getNumParameters();
         if (isVarArgs())
            return numParams - 1;
         return numParams;
      }

      /** Returns true if there are methods with this name with different number of parameters.  Used to optimize method dispatch in the generated code */
      public boolean getVariableParamNum() {
         if (isAnyVarArgs())
            return false;
         int numParams = 0;
         boolean variable = false;
         for (int i = 0; i < methods.length; i++) {
            int newNum = methods[i].getNumParameters();
            if (i == 0)
               numParams = newNum;
            else if (numParams != newNum) {
               variable = true;
               break;
            }
         }
         return variable;
      }

      public List<JSMethod> getMethAliases() {
         ArrayList<JSMethod> res = new ArrayList<JSMethod>(methods.length);
         for (int i = 0; i < methods.length; i++) {
            AbstractMethodDefinition[] def = new AbstractMethodDefinition[] { methods[i]};
            AbstractMethodDefinition[] jsDef = new AbstractMethodDefinition[] { jsMethods[i] };

            JSMethod alias = new JSMethod(def, jsDef);
            alias.constructor = constructor;
            addMethodToList(alias, res);
         }
         return res;
      }

      public ArrayList<List<JSMethod>> getMethsByParamNum() {
         if (methsByParamNum != null)
            return methsByParamNum;

         methsByParamNum = new ArrayList<List<JSMethod>>();
         for (AbstractMethodDefinition meth:methods) {
            List<JSMethod> found = null;
            for (List<JSMethod> methByParam:methsByParamNum) {
               if (methByParam.get(0).getNumParameters() == meth.getNumParameters())
                  found = methByParam;
            }
            if (found == null) {
               JSMethod subMeth = new JSMethod(Collections.singletonList(meth).toArray(new AbstractMethodDefinition[1]));
               subMeth.constructor = constructor;
               methsByParamNum.add(new ArrayList<JSMethod>(Collections.singletonList(subMeth)));
            }
            else {
               JSMethod subMeth = new JSMethod(Collections.singletonList(meth).toArray(new AbstractMethodDefinition[1]));
               subMeth.constructor = constructor;
               addMethodToList(subMeth, found);
            }
         }
         return methsByParamNum;
      }

      public String getArgNames() {
         StringBuilder sb = new StringBuilder();

         boolean outer = false;
         if (constructor && type.getEnclosingInstType() != null) {
            sb.append("_outer");
            outer = true;
         }

         if (defaultMethod == null)
            return sb.toString();

         // Only the simple case when there's one Java method for one JS method uses the actual arg names.  Originally they were left in but
         // apparently var name does not override a parameter of the same name.  That actually messed up the arguments[] array when we assigned the
         // parameter's value itself. weird...  now suppressing them to avoid that bizarrity.
         if (getNeedsDispatch())
            return sb.toString();

         List<Parameter> params = jsDefaultMethod.getParameterList();
         int sz = params.size();
         for (int i = 0; i < sz; i++) {
            // Skip the varargs parameter in the arg names since we have to assign it manually anyway
            if (i == sz - 1 && isVarArgs())
               break;
            if (i != 0 || outer)
               sb.append(", ");
            Parameter p = params.get(i);
            sb.append(p.variableName);
         }
         return sb.toString();
      }

      public List<JSParameter> getParameters() {
         List<Parameter> params = jsDefaultMethod == null ? null : jsDefaultMethod.getParameterList();
         int sz = params == null ? 0 : params.size();
         ArrayList<JSParameter> res = new ArrayList<JSParameter>(sz);
         boolean isVarArgs = isVarArgs();
         // Not returning the var args parameter.  That is just special cased on the isVarArgs flag.
         if (isVarArgs)
            sz--;
         for (int i = 0; i < sz; i++) {
            Parameter p = params.get(i);
            res.add(new JSParameter(p));
         }
         return res;
      }

      public String toString() {
         if (defaultMethod != null)
            return defaultMethod.toString();

         return "js method - no default";
      }
   }

   public class JSParameter {
      Parameter param;

      JSParameter(Parameter p) {
         param = p;
      }

      public String getType() {
         return JSUtil.convertTypeName(type.getLayeredSystem(), param.type.getAbsoluteBaseTypeName());
      }

      public String getName() {
         return param.variableName;
      }

      public int getNdims() {
         return param.type.getNdims();
      }

      /** Primitive types should not match with a null value but object types should.  This does not fix the problems with dynamic dispatch but helps make it work more like compiled time dispatch. */
      public String getInstanceOf() {
         Object type = param.getTypeDeclaration();
         if (ModelUtil.isArray(type))
            return "sc_arrayInstanceOf";
         if (ModelUtil.isCharacter(type))
            return "sc_instanceOfChar";
         return ModelUtil.isPrimitive(type) ? "sc_instanceOf" : "sc_paramType";
      }

      public String getNumDimsStr() {
         int ndims = getNdims();
         if (ndims > 0)
            ndims = ndims - 1;
         // TODO: Note: number of dimensions is 0 for a 1D array and 1 for a 2D array.  See also change to BinaryExpression, sccore.js
         return ndims == -1 ? "" : ", " + ndims;
      }
   }

   public class JSStatement {
      Statement orig;
      Statement jsStatement;
      JSFormatMode mode;

      public JSStatement(Statement st, Statement jsS, JSFormatMode mode) {
         this.orig = st;
         this.jsStatement = jsS;
         this.mode = mode;
      }

      public String toString() {
         if (jsStatement instanceof BodyTypeDeclaration) {
            BodyTypeDeclaration btd = (BodyTypeDeclaration) jsStatement;
            LayeredSystem sys = btd.getLayeredSystem();
            JSRuntimeProcessor rt = getJSRuntimeProcessor();
            StringBuilder sb = new StringBuilder();
            rt.appendInnerJSMergeTemplate(btd, sb, syncTemplate);
            return sb.toString();
         }
         return jsStatement.formatToJS(mode, JSTypeParameters.this, 0).toString();
      }
   }

   JSMethod cachedConstructor = null;

   public JSMethod getConstructor() {
      if (cachedConstructor != null)
         return cachedConstructor;
      Object[] constrs = type.getConstructors(null);
      JSMethod constr;
      if (constrs == null) {
         constr = new JSMethod(null);
         constr.constructor = true;
      }
      else {
         ArrayList<AbstractMethodDefinition> res = new ArrayList<AbstractMethodDefinition>(constrs.length);
         for (Object javaConstr:constrs) {
            if (javaConstr instanceof AbstractMethodDefinition)
               res.add((AbstractMethodDefinition) javaConstr);
            else
               System.err.println("*** Compiled constructor??");
         }
         constr = new JSMethod(res.toArray(new AbstractMethodDefinition[res.size()]));
         constr.constructor = true;
      }
      cachedConstructor = constr;
      return constr;
   }

   public String getExtendsClass() {
      JavaType extType = type.getExtendsType();
      if (extType == null)
         return "jv_Object";
      return JSUtil.convertTypeName(type.getLayeredSystem(), ModelUtil.getTypeName(extType.getTypeDeclaration()));
   }

   public String getImplementsClasses() {
      Object[] impls = type.getImplementsTypeDeclarations();
      if (impls == null || impls.length == 0)
         return "null";

      LayeredSystem sys = type.getLayeredSystem();
      StringBuilder sb = new StringBuilder();
      sb.append("[");
      boolean first = true;
      for (Object imp:impls) {
         if (!first)
            sb.append(",");
         first = false;
         sb.append(JSUtil.convertTypeName(sys, ModelUtil.getTypeName((imp))));
      }
      sb.append("]");
      return sb.toString();
   }

   public int getOuterInstCount() {
      return type.getOuterInstCount();
   }

   /** This is a special flag used by the sync system so it can run code outside of a type altogether */
   public boolean getGlobalType() {
      return type != null && type.typeName.equals(SyncLayer.GLOBAL_TYPE_NAME);
   }

   public List<JSStatement> getPreInitStatements() {
      if (jsPreInitStatements == null) {
         List<Statement> initSts = type.getInitStatements(InitStatementsMode.PreInit, true);
         jsPreInitStatements = convertStatements(initSts, JSFormatMode.PreInit);
      }
      return jsPreInitStatements;
   }

   public List<JSStatement> getInitStatements() {
      if (jsInitStatements == null) {
         List<Statement> initSts = type.getInitStatements(InitStatementsMode.Init, true);
         jsInitStatements = convertStatements(initSts, JSFormatMode.InstInit);
      }
      return jsInitStatements;
   }

   public String getSuperClassInit() {
      StringBuilder sb = null;
      Object[] impls = type.getImplementsTypeDeclarations();
      LayeredSystem sys = type.getLayeredSystem();
      JSRuntimeProcessor rp = getJSRuntimeProcessor();
      if (impls != null) {
         sb = new StringBuilder();
         for (Object impl:impls) {
            impl = ModelUtil.resolveSrcTypeDeclaration(sys, impl, false, false);
            if (rp.needsClassInit(impl)) {
               sb.append("\n   sc_clInit(");
               sb.append(rp.getStaticPrefix(impl, type));
               sb.append(");");
            }
         }
      }
      /*
      Object extType = type.getExtendsTypeDeclaration();
      if (extType != null) {
         if (sb == null)
            sb = new StringBuilder();
         if (ModelUtil.needsClassInit(extType)) {
            sb.append("\n   sc_clInit(");
            sb.append(getExtendsClass());
            sb.append(rp.typeNameSuffix);
            sb.append(");");
         }
      }
      */
      return sb == null ? "" : sb.toString();
   }

   /** The code segment we add for code in the class init section */
   public String getClassInit() {
      if (getNeedsClassInit()) {
         // TODO: shouldn't this indent be based on the type's indent levels?
         String classInit = "\n   " + getJSTypeName() + "._clInit();\n";
         addGenLineMapping(type, classInit, 0);
         return classInit;
      }
      return "";
   }

   public boolean getNeedsClassDef() {
      return !updateTemplate;
   }

   public boolean getNeedsInstInit() {
      if (updateTemplate && !needsConstructor)
         return false;

      List<JSStatement> sts = getInitStatements();
      if (sts != null && sts.size() > 0)
         return true;
      return false;
   }

   public boolean getNeedsInit() {
      return getPreInitStatements().size() > 0 || getInitStatements().size() > 0;
   }

   public String getUpdateInstSuper() {
      if (type.implementsType("sc.obj.ITypeUpdateHandler", false, false)) {
         if (getRuntimeTypeName().equals(getExtendsClass())) {
            // Error rather than printing out code that will infinite loop.
            System.out.println("*** Error - invalid extends for update inst in JS conversion");
            return "";
         }
         return getExtendsClass() + getJSRuntimeProcessor().typeNameSuffix + "._updateInst.call(this);\n   ";
      }
      return "";
   }

   public boolean getNeedsRuntimeInit() {
      if (getNeedsInit())
         return true;
      List<Object> innerObjs = type.getLocalInnerTypes(null);
      if (innerObjs != null && innerObjs.size() > 0)
         return true;
      return false;
   }

   public boolean getNeedsClassInit() {
      List<JSStatement> sts = getStaticInitStatements();
      if (sts != null && sts.size() > 0)
         return true;

      // For merge templates, don't iterate on the methods since the type may not be a compiled type and that will
      // lead to unnecessary errors.
      if (mergeTemplate)
         return false;
      JSMethod[] meths = getMethods();
      if (meths != null) {
         for (JSMethod meth:meths)
            if (meth.getBindSettings() != null)
               return true;
      }
      return false;
   }

   private List<JSStatement> staticStatements;

   public List<JSStatement> getStaticInitStatements() {
      if (staticStatements == null) {
         List<Statement> initSts = type.getInitStatements(InitStatementsMode.Static, true);
         staticStatements = convertStatements(initSts, JSFormatMode.Static);
      }
      return staticStatements;
   }

   public String getClassName() {
      return type.getTypeName();
   }

   private List<JSStatement> convertStatements(List<Statement> initSts, JSFormatMode mode) {
      int sz = initSts.size();
      ArrayList<JSStatement> res = new ArrayList<JSStatement>(sz);
      for (int i = 0; i < sz; i++) {
         Statement st = initSts.get(i);
         res.add(new JSStatement(st, (Statement) JSUtil.convertToJS(st), mode));
      }
      return res;
   }

   public JSMethod[] getMethods() {
      if (jsTypeMethods != null)
         return jsTypeMethods;

      if (mergeTemplate || (updateTemplate && changedMethods == null)) {
         return jsTypeMethods = new JSMethod[0];
      }

      List<Object> methods = type.getAllMethods(null, false, false, false);
      ArrayList<List<AbstractMethodDefinition>> methsByName = new ArrayList<List<AbstractMethodDefinition>>();
      if (methods == null)
         return jsTypeMethods = new JSMethod[0];
      for (Object methObj:methods) {
         // Skip abstract methods
         if (ModelUtil.isAbstractMethod(methObj))
            continue;


         if (!(methObj instanceof AbstractMethodDefinition)) {
            // The enclosing type of the compiled method might have a library or something so this is not an error unless the method is for this type.
            if (ModelUtil.sameTypes(type, ModelUtil.getEnclosingType(methObj))) {
               System.err.println("*** Skipping compiled method in JS code generation: " + methObj);
            }
            continue;
         }
         AbstractMethodDefinition meth = (AbstractMethodDefinition) methObj;
         Boolean omit = (Boolean) ModelUtil.getAnnotationValue(meth, "sc.js.JSSettings", "omit");
         if (omit != null && omit)
            continue;
         List<AbstractMethodDefinition> found = null;
         String methName = ModelUtil.getMethodName(meth);

         if (updateTemplate && !changedMethods.contains(methName))
            continue;

         for (int i = 0; i < methsByName.size(); i++) {
            List<AbstractMethodDefinition> toCheck = methsByName.get(i);
            if (ModelUtil.getMethodName(toCheck.get(0)).equals(methName)) {
               found = toCheck;
               break;
            }
         }
         if (found == null) {
            found = new ArrayList<AbstractMethodDefinition>();
            methsByName.add(found);
         }
         found.add(meth);
      }
      ArrayList<JSMethod> res = new ArrayList<JSMethod>();
      for (int i = 0; i < methsByName.size(); i++) {
         List<AbstractMethodDefinition> byName = methsByName.get(i);
         boolean allInherited = true;
         int ix = 0;
         for (AbstractMethodDefinition meth:byName) {
             if (ModelUtil.sameTypes(ModelUtil.getEnclosingType(meth), type)) {
                allInherited = false;
                if (ix != 0) {
                   byName.remove(ix);
                   byName.add(0, meth);
                }
                break;
             }
            ix++;
         }

         // Only need to process methods where at least one method with this name is defined in this type
         if (allInherited)
            continue;
         JSMethod jsmeth = new JSMethod(byName.toArray(new AbstractMethodDefinition[byName.size()]));

         res.add(jsmeth);
      }
      jsTypeMethods = res.toArray(new JSMethod[res.size()]);
      return jsTypeMethods;
   }

   public String getRuntimeTypeName() {
      JavaModel model = type.getJavaModel();
      String runtimeTypeName = null;
      // When we have an object x extends foo tag the x is a runtime object name, not a type name so we need
      // to use the extends class for the _update method.
      if (syncTemplate && !model.mergeDeclaration && type instanceof ClassDeclaration) {
         ClassDeclaration classDecl = (ClassDeclaration) type;
         if (classDecl.operator.equals("object")) {
            Object extType = classDecl.getExtendsTypeDeclaration();
            if (extType == null)
               runtimeTypeName = "java.lang.Object";
            else {
               runtimeTypeName = ModelUtil.getRuntimeTypeName(extType);
            }
         }
      }
      // Layer when synchronized are not defined relative to the base layers.
      if (syncTemplate && type instanceof ModifyDeclaration) {
         ModifyDeclaration modType = (ModifyDeclaration) type;
         Object modModType;
         do {
            modModType = modType.getModifiedType();
            // Sometimes we are modifying multiple levels before the layer base type.
            if (modModType instanceof ModifyDeclaration && ((ModifyDeclaration) modModType).isLayerType)
               runtimeTypeName = "sc.layer.Layer";
            if (modModType instanceof ModifyDeclaration) {
               modType = ((ModifyDeclaration) modModType);
            }
            else
               modType = null;
         } while (modType != null);
      }
      if (runtimeTypeName == null) {
         runtimeTypeName = ModelUtil.getRuntimeTypeName(type);
      }

      if (type.typeName != null && type.getEnclosingType() != null && type.typeName.contains("editorModel"))
         System.out.println("*** editorModel - runtimeTypeName: " + runtimeTypeName);

      String rtTypeName = JSUtil.convertTypeName(type.getLayeredSystem(), runtimeTypeName);
      return rtTypeName;
   }

   public String getStaticPrefix() {
      LayeredSystem sys = type.getLayeredSystem();
      JSRuntimeProcessor rp = (JSRuntimeProcessor) sys.runtimeProcessor;
      return rp.getStaticPrefix(type, null);
   }

   public String getAnnotations() {
      if (updateTemplate)
         return "";
      LayeredSystem sys = type.getLayeredSystem();
      StringBuilder sb = new StringBuilder();
      List modifiers = type.getComputedModifiers();
      if (modifiers != null) {
         for (Object modifier:type.getComputedModifiers()) {
            if (modifier instanceof Annotation) {
               Annotation annot = (Annotation) modifier;
               if (isRuntimeJSAnnotation(annot)) {
                  String annotTypeName = annot.getFullTypeName();
                  //sb.append(getStaticPrefix());
                  sb.append(getShortJSTypeName());
                  sb.append("._A_");
                  sb.append(CTypeUtil.getClassName(annotTypeName));
                  Object elementValue = annot.elementValue;
                  sb.append(" = ");
                  if (elementValue != null) {
                     appendAnnotValue(sb, elementValue);
                  }
                  else
                     sb.append("null");
                  sb.append(";\n");
               }
            }
         }
         if (sb.length() > 0)
            sb.append("\n");
      }
      return sb.toString();
   }

   private static void appendAnnotValue(StringBuilder sb, Object elementValue) {
      if (elementValue instanceof List) {
         List elementList = (List) elementValue;
         sb.append("{");
         int i = 0;
         for (Object annotObj:elementList) {
            if (i != 0)
               sb.append(", ");
            AnnotationValue av = (AnnotationValue) annotObj;
            sb.append(av.identifier);
            sb.append(": ");
            if (av.elementValue instanceof AbstractLiteral)
               sb.append(((AbstractLiteral)av.elementValue).getExprValue());
            else if (av.elementValue instanceof Expression) {
               Expression attValExpr = (Expression) av.elementValue;

               sb.append(JSUtil.convertAndFormatExpression(attValExpr));
            }
            else
               sb.append(av.elementValue.toString());
            i++;
         }
         sb.append("}");
      }
      else {
         sb.append(elementValue.toString());
      }
   }

   private boolean isRuntimeJSAnnotation(Annotation annot) {
      String annotTypeName = annot.getFullTypeName();
      LayeredSystem sys = type.getLayeredSystem();
      Object cl = sys.getTypeDeclaration(annotTypeName);
      if (cl == null)
         System.err.println("*** JS conversion - no annotation: " + annotTypeName);
      else {
         // First look for a specific JS retention policy
         Boolean runtimePolicy = getRuntimeRetention(cl, "sc.js.JSRetention");
         if (runtimePolicy == null) // Default to Java's retention policy
            runtimePolicy = getRuntimeRetention(cl, "java.lang.annotation.Retention");
         if (runtimePolicy != null && runtimePolicy) {
            return true;
         }
      }
      return false;
   }

   // When we replace one type with another in JS land, we may need to register the old type name as an alias so we know
   // what type to create when de-serializing an instance sent across from the server.
   public String getTypeAliases() {
      if (updateTemplate)
         return "";

      List<String> typeAliases = getJSRuntimeProcessor().jsBuildInfo.typeAliases.get(getTypeName());
      if (typeAliases == null)
         return "";
      StringBuilder aliasArray = new StringBuilder();
      aliasArray.append("[");
      for (String typeAlias:typeAliases) {
         aliasArray.append('"');
         aliasArray.append(typeAlias);
         aliasArray.append('"');
      }
      aliasArray.append("]");
      return "sc_addTypeAliases(" + getShortJSTypeName() + ", " + aliasArray + ");\n";
   }

   public String getPropertyMetadata() {
      if (updateTemplate)
         return "";
      List<Object> props = type.getDeclaredProperties();
      StringBuilder sb = null;
      TreeSet<String> visited = new TreeSet<String>();
      if (props != null) {
         for (Object prop:props) {
            String propName = ModelUtil.getPropertyName(prop);
            Object propType = ModelUtil.getPropertyType(prop);
            if (propType != null && needsJSMetadata(propType) && type.isSynced(propName)) {
               // getDeclaredProperties returns more than one representation for the same property
               if (visited.contains(propName))
                  continue;
               JSRuntimeProcessor proc = getJSRuntimeProcessor();
               visited.add(propName);
               if (sb == null) {
                  sb = new StringBuilder();
                  sb.append(getShortJSTypeName());
                  sb.append("._PROPS = {");
               }
               else
                  sb.append(", ");
               sb.append('"');
               sb.append(propName);
               sb.append("\":\"");
               sb.append(ModelUtil.getTypeName(propType));
               sb.append("\"");
            }
         }
         if (sb != null)
            sb.append("};\n");
         StringBuilder asb = null;
         visited = new TreeSet<String>();
         for (Object prop:props) {
            String propName = ModelUtil.getPropertyName(prop);
            if (prop instanceof Definition) {
               List<Object> modifiers = ((Definition) prop).getComputedModifiers();
               if (modifiers != null) {
                  boolean firstModifier = true;
                  for (Object modifier:modifiers) {
                     if (modifier instanceof Annotation) {
                        Annotation annot = (Annotation) modifier;
                        String annotTypeName = annot.getTypeName();
                        if (isRuntimeJSAnnotation(annot)) {
                           if (visited.contains(annotTypeName))
                              continue;
                           visited.add(annotTypeName);
                           if (asb == null) {
                              asb = new StringBuilder();
                              asb.append(getShortJSTypeName());
                              asb.append("._PT = {");
                           }
                           else
                              asb.append(", ");
                           if (firstModifier) {
                              firstModifier = false;
                              asb.append(propName);
                              asb.append(":{");
                           }
                           asb.append(CTypeUtil.getClassName(annotTypeName));
                           Object elementValue = annot.elementValue;
                           if (elementValue != null) {
                              asb.append(": ");
                              appendAnnotValue(asb, elementValue);
                           }
                           else
                              asb.append(": true");
                        }
                     }
                  }
                  if (!firstModifier)
                     asb.append("}");
               }
            }
         }
         if (asb != null) {
            asb.append("};\n");
            if (sb != null)
               sb.append(asb);
            else
               sb = asb;
         }
      }
      return sb == null ? "" : sb.toString();
   }

   private boolean needsJSMetadata(Object propType) {
      return ModelUtil.isArray(propType) || ModelUtil.isAssignableFrom(Collection.class, propType);
   }

   private Boolean getRuntimeRetention(Object cl, String retentionAnnotTypeName) {
      Object rtAnnotation = ModelUtil.getAnnotation(cl, retentionAnnotTypeName);
      Object policy = null;
      if (rtAnnotation != null) {
         //if (rtAnnotation instanceof IAnnotation) {
            policy = ModelUtil.getAnnotationSingleValue(rtAnnotation);
            policy = Annotation.convertElementValue(policy);
            return (policy == RetentionPolicy.RUNTIME);
         //} {
         //   Retention rt = (Retention) rtAnnotation;
         //   return rt.value() == RetentionPolicy.RUNTIME;
         //}
      }
      return null;
   }

   public boolean isInnerClass() {
      return type.getEnclosingType() != null;
   }

   public String getNewClassMethodName() {
      String objClass = ModelUtil.isObjectType(type) ? "Obj" : "Class";
      // TODO: expose these as configurable properties of the JSRuntimeProcessor or make them settable via some annotation processor or something?  Do they need to be unique or global?  If global maybe frameworks just replaace the sccore.js file to override the implementations of these methods
      return isInnerClass() ? "sc_newInner" + objClass : "sc_new" + objClass;
   }

   /** Returns the extra argument for specifying the outer type name when defining an innerClass */
   public String getOuterClassArg() {
      return isInnerClass() ? " " + getAccessorTypeName() + "," : "";
   }

   public String getAccessorTypeName() {
      BodyTypeDeclaration enclType = type.getEnclosingType();
      if (enclType == null)
         return getTypeName();
      else
         return JSUtil.convertTypeName(type.getLayeredSystem(), enclType.getFullTypeName());
   }

   private void addMethodToList(JSMethod subMeth, List<JSMethod> found) {
      int insertIx = -1;
      AbstractMethodDefinition meth = subMeth.defaultMethod;
      for (int m = 0; m < found.size(); m++) {
         JSMethod otherJSMeth = found.get(m);
         int numParams = meth.getNumParameters();
         int otherNumParams = otherJSMeth.getNumParameters();
         AbstractMethodDefinition otherMeth = otherJSMeth.defaultMethod;

         List<Parameter> otherList = otherMeth.getParameterList();
         List<Parameter> thisList = meth.getParameterList();

         for (int p = 0; p < numParams; p++) {
            if (p >= otherNumParams) {
               // If we are sorting EnumSet.of(E) and EnumSet.of(E, E...) put the single parameter version first.  Otherwise we do the repeating version which infinite loops calling itself.
               if (p == numParams - 1 && thisList.get(p).repeatingParameter) {
                  int o;
                  for (o = 0; o < otherNumParams; o++) {
                     Object otherParamType = otherList.get(o).getTypeDeclaration();
                     Object thisParamType = thisList.get(o).getTypeDeclaration();
                     if (!ModelUtil.isAssignableFrom(otherParamType, thisParamType))
                        break;

                  }
                  if (o == otherNumParams)
                     break;
               }
               insertIx = m;
               break;
            }
            Object otherParamType = otherList.get(p).getTypeDeclaration();
            Object thisParamType = thisList.get(p).getTypeDeclaration();
            // Sort based on the first non-common parameter - put the more specific methods ahead of the less specific ones
            // Primitive types should go ahead because they cannot match against null but the other guys can (and this fixes the all important "remove(int)" versus "remove(Object)" case.
            if (otherParamType != thisParamType) {
               if (ModelUtil.isPrimitive(thisParamType) && !ModelUtil.isPrimitive(otherParamType) || (ModelUtil.isAssignableFrom(otherParamType, thisParamType) && !ModelUtil.isAssignableFrom(thisParamType, otherParamType))) {
                  insertIx = m;
                  break;
               }
            }
         }
         if (insertIx != -1)
            break;
      }
      if (insertIx == -1)
         found.add(subMeth);
      else
         found.add(insertIx, subMeth);
   }


   public boolean getNeedsSync() {
      return type.needsSync();
   }

   public boolean getNewObject() {
      return type instanceof ClassDeclaration && ((ClassDeclaration) type).operator.equals("object");
   }

   static TreeSet<String> classMethods = new TreeSet<String>();
   static {
      classMethods.add("hashCode");
      classMethods.add("equals");
      classMethods.add("getName");
      classMethods.add("toString");
   }


   /** TODO: either remove this or fix this approach.  It's goal is to mark the sync'd properties at code-gen time and use that info to
    * determine whether we are applying sync'd changes or detecting new ones (instead of the nested binding count).  Nested binding count
    * does not work well when the setX method directly makes a change to another property which we'd like recorded.  But this approach
    * does not work for properties initialized in the class initialization part.  Stuff that's statically initialized gets recorded even if it's
    * init flag is false.
    */
   public String getSyncPendingProp(JSStatement jst) {
      Statement st = jst.orig;
      String propName = null;
      if (st instanceof PropertyAssignment) {
         PropertyAssignment pa = (PropertyAssignment) st;
         propName = pa.propertyName;
      }
      else if (st instanceof IdentifierExpression) {
         IdentifierExpression ie = (IdentifierExpression) st;
         Object propertyType = ie.getAssignedProperty();
         if (propertyType != null)
            propName = ModelUtil.getPropertyName(propertyType);
      }
      if (propName != null && type.isSynced(propName))
         return "_ctx.pendingSyncProp = \"" + propName + "\";\n   ";
      return "";
   }

   public boolean getNeedsConstructor() {
      return !updateTemplate || needsConstructor;
   }

   /** Do we need to define the _c variable (aka ? */
   public boolean getHasTypeChanges() {
      return getMethods().length > 0 || getNeedsClassInit() || getNeedsInstInit();
   }

   public static String replaceIndent(int indent, String str) {
      String res = str.trim();
      boolean append = false;
      if (res.length() > 0) {
         int semiIx = res.lastIndexOf(";");
         if (semiIx != -1) {
            boolean found = false;
            for (int i = semiIx + 1; i < res.length(); i++) {
               if (res.charAt(i) == '\n') {
                  found = true;
                  break;
               }
            }
            if (!found)
               append = true;
         }
         res = StringUtil.indent(indent) + res + (append ? FileUtil.LINE_SEPARATOR : "");
      }
      return res;
   }

   public void addGenLineMapping(Statement st, CharSequence genStatementCode, int extraLines) {
      if (lineIndex != null && st.isLineStatement()) {
         int genStartLine = getGenLineCount();
         if (genStartLine == -1) // We can either compile or interpret the JSTypeTemplate (where compiling is faster) - if interpreting, we don't support line number registration right now
            return;

         Statement.addMappingForStatement(lineIndex, st, genStartLine + extraLines, genStatementCode);
      }
   }

   public int getGenLineCount() {
      return -1;
   }

   public JSRuntimeProcessor getJSRuntimeProcessor() {
      return ((JSRuntimeProcessor) system.runtimeProcessor);
   }

   public boolean getUseShortTypeNames() {
      return getJSRuntimeProcessor().useShortTypeNames;
   }

   public String getTypeNameSuffix() {
      return getJSRuntimeProcessor().typeNameSuffix;
   }

   public String getShortJSTypeName() {
      return getUseShortTypeNames() ? getJSRuntimeProcessor().typeNameSuffix : getJSTypeName();
   }

   public String getJSTypeName() {
      return getTypeName() + getJSRuntimeProcessor().typeNameSuffix;
   }
}
