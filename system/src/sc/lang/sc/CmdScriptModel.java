/*
 * Copyright (c) 2018. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sc;

import sc.lang.*;
import sc.lang.java.*;
import sc.lang.java.Package;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.layer.SrcEntry;
import sc.parser.ParseError;
import sc.parser.ParseUtil;
import sc.type.CTypeUtil;
import sc.type.DynType;

import java.util.*;

/**
 * CmdScriptModel is the top-level model for an SC script file (or 'scr' file).  It is used for tool support for test scripts parsed using the CommandSCLanguage.  At runtime, a command script is
 * parsed a line at a time and executed so this model reflects differences to how we map grammar elements for this type of language.  Rather than defining a TypeDeclaration tree like a normal JavaModel,
 * it produces a linear list of 'start/end' type declaration commands which must be traversed to find the current type for any given statement.  This CmdScriptModel also has logic for handling 'cmd.include/includeSuper()', so it can resolve members of an
 * included script.
 */
public class CmdScriptModel extends JavaModel implements ITypeDeclaration {
   // Produced by the topLevelCommands parselet of the CommandSCLanguage - Package, ImportDeclaration, Statement, ClassDeclaration or ModifyDeclaration but representing the 'open' type only.  So you have
   // to find the currentType by walking through the entire list to build up the type by matching with the EndTypeDeclarations.
   public SemanticNodeList<Object> commands;

   public transient AbstractInterpreter.DefaultCmdClassDeclaration cmdObject;

   public boolean isAssignableFrom(ITypeDeclaration other, boolean assignmentSemantics) {
      return getCmdObject().isAssignableFrom(other, assignmentSemantics);
   }

   public boolean isAssignableTo(ITypeDeclaration other) {
      return getCmdObject().isAssignableTo(other);
   }

   public boolean isAssignableFromClass(Class other) {
      return getCmdObject().isAssignableFromClass(other);
   }

   public String getTypeName() {
      return getCmdObject().getTypeName();
   }

   public String getFullTypeName(boolean includeDims, boolean includeTypeParams) {
      return getCmdObject().getFullTypeName(includeDims, includeTypeParams);
   }

   public String getFullTypeName() {
      return getCmdObject().getFullTypeName();
   }

   public String getJavaFullTypeName() {
      return getCmdObject().getJavaFullTypeName();
   }

   public String getFullBaseTypeName() {
      return getCmdObject().getFullBaseTypeName();
   }

   public String getInnerTypeName() {
      return getCmdObject().getInnerTypeName();
   }

   public Class getCompiledClass() {
      return getCmdObject().getCompiledClass();
   }

   public String getCompiledClassName() {
      return getCmdObject().getCompiledClassName();
   }

   public String getCompiledTypeName() {
      return getCmdObject().getCompiledTypeName();
   }

   public Object getRuntimeType() {
      return getCmdObject().getRuntimeType();
   }

   public boolean isDynamicStub(boolean includeExtends) {
      return getCmdObject().isDynamicStub(includeExtends);
   }

   public Object definesMember(String name, EnumSet<MemberType> type, Object refType, TypeContext ctx) {
      Object res = super.definesMember(name, type, refType, ctx, false, false);
      if (res != null)
         return res;
      return getCmdObject().definesMember(name, type, refType, ctx);
   }

   public Object getInnerType(String name, TypeContext ctx) {
      return getCmdObject().getInnerType(name, ctx);
   }

   public boolean implementsType(String otherTypeName, boolean assignment, boolean allowUnbound) {
      return getCmdObject().implementsType(otherTypeName, assignment, allowUnbound);
   }

   public Object getInheritedAnnotation(String annotationName, boolean skipCompiled, Layer refLayer, boolean layerResolve) {
      return getCmdObject().getInheritedAnnotation(annotationName, skipCompiled, refLayer, layerResolve);
   }

   public ArrayList<Object> getAllInheritedAnnotations(String annotationName, boolean skipCompiled, Layer refLayer, boolean layerResolve) {
      return getCmdObject().getAllInheritedAnnotations(annotationName, skipCompiled, refLayer, layerResolve);
   }

   public Object getDerivedTypeDeclaration() {
      return getCmdObject().getDerivedTypeDeclaration();
   }

   public Object getExtendsTypeDeclaration() {
      return getCmdObject().getExtendsTypeDeclaration();
   }

   public Object getExtendsType() {
      return getCmdObject().getExtendsType();
   }

   public List<?> getImplementsTypes() {
      return getCmdObject().getImplementsTypes();
   }

   public List<Object> getAllMethods(String modifier, boolean hasModifier, boolean isDyn, boolean overridesComp) {
      return getCmdObject().getAllMethods(modifier, hasModifier, isDyn, overridesComp);
   }

   public List<Object> getMethods(String methodName, String modifier, boolean includeExtends) {
      return getCmdObject().getMethods(methodName, modifier, includeExtends);
   }

   public Object getConstructorFromSignature(String sig) {
      return getCmdObject().getConstructorFromSignature(sig);
   }

   public Object getMethodFromSignature(String methodName, String signature, boolean resolveLayer) {
      return getCmdObject().getMethodFromSignature(methodName, signature, resolveLayer);
   }

   public List<Object> getAllProperties(String modifier, boolean includeAssigns) {
      return getCmdObject().getAllProperties(modifier, includeAssigns);
   }

   public List<Object> getAllFields(String modifier, boolean hasModifier, boolean dynamicOnly, boolean includeObjs, boolean includeAssigns, boolean includeModified) {
      return getCmdObject().getAllFields(modifier, hasModifier, dynamicOnly, includeObjs, includeAssigns, includeModified);
   }

   public List<Object> getAllInnerTypes(String modifier, boolean thisClassOnly) {
      return getCmdObject().getAllInnerTypes(modifier, thisClassOnly);
   }

   public DeclarationType getDeclarationType() {
      return DeclarationType.OBJECT;
   }

   public boolean isLayerType() {
      return false;
   }

   public List<?> getClassTypeParameters() {
      return getCmdObject().getClassTypeParameters();
   }

   public Object[] getConstructors(Object refType) {
      return getCmdObject().getConstructors(refType);
   }

   public boolean isComponentType() {
      return getCmdObject().isComponentType();
   }

   public DynType getPropertyCache() {
      return getCmdObject().getPropertyCache();
   }

   public boolean isEnumeratedType() {
      return getCmdObject().isEnumeratedType();
   }

   public Object getEnumConstant(String nextName) {
      return getCmdObject().getEnumConstant(nextName);
   }

   public boolean isCompiledProperty(String name, boolean fieldMode, boolean interfaceMode) {
      return getCmdObject().isCompiledProperty(name, fieldMode, interfaceMode);
   }

   public List<JavaType> getCompiledTypeArgs(List<JavaType> typeArgs) {
      return getCmdObject().getCompiledTypeArgs(typeArgs);
   }

   public boolean needsOwnClass(boolean checkComponents) {
      return getCmdObject().needsOwnClass(checkComponents);
   }

   public boolean isDynamicNew() {
      return getCmdObject().isDynamicNew();
   }

   public void initDynStatements(Object inst, ExecutionContext ctx, TypeDeclaration.InitStatementMode mode) {
      getCmdObject().initDynStatements(inst, ctx, mode);
   }

   public void clearDynFields(Object inst, ExecutionContext ctx) {
      getCmdObject().clearDynFields(inst, ctx);
   }

   public Object[] getImplementsTypeDeclarations() {
      return getCmdObject().getImplementsTypeDeclarations();
   }

   public Object[] getAllImplementsTypeDeclarations() {
      return getCmdObject().getAllImplementsTypeDeclarations();
   }

   public boolean isRealType() {
      return getCmdObject().isRealType();
   }

   public void staticInit() {
      getCmdObject().staticInit();
   }

   public boolean isTransformedType() {
      return getCmdObject().isTransformedType();
   }

   public Object getArrayComponentType() {
      return getCmdObject().getArrayComponentType();
   }

   public ITypeDeclaration resolve(boolean modified) {
      return getCmdObject().resolve(modified);
   }

   public boolean useDefaultModifier() {
      return true;
   }

   static class IncludeCommand {
      int commandIx; // Index in commands of the include
      CmdScriptModel includedModel;
   }

   // Sorted in order of commandIx
   public transient ArrayList<IncludeCommand> includeCommands = null;

   public void init() {
      if (commands != null) {
         for (Object command:commands) {
            // Accumulate the imports in the JavaModel so we can handle them there
            if (command instanceof ImportDeclaration) {
               if (imports == null)
                  imports = new SemanticNodeList<ImportDeclaration>();
               imports.add((ImportDeclaration) command);
            }
         }
      }
      super.init();
   }

   public void start() {
      if (commands != null) {
         LayeredSystem sys = getLayeredSystem();
         int ix = 0;
         for (Object command:commands) {
            if (command instanceof IdentifierExpression && layer != null) {
               IdentifierExpression ie = (IdentifierExpression) command;
               if (ie.identifiers != null && ie.identifiers.size() == 2 && ie.identifiers.get(0).equals("cmd")) {
                  String meth = ie.identifiers.get(1).toString();
                  if (meth.equals("include")) {
                     SemanticNodeList<Expression> args = ie.arguments;
                     if (args != null && args.size() == 1) {
                        Expression fileArg = args.get(0);
                        String path = (String) fileArg.eval(String.class, null);
                        if (path != null) {
                           SrcEntry includeSrcEnt = layer.getLayerFileFromRelName(path, true);
                           if (includeSrcEnt != null) {
                              addInclude(sys, ix, includeSrcEnt);
                           }
                           else {
                              fileArg.displayError("Missing include file: " + path + " for: ");
                           }
                        }
                     }
                  }
                  else if (meth.equals("includeSuper")) {
                     SemanticNodeList<Expression> args = ie.arguments;
                     if (args != null && args.size() == 0) {
                        String srcFile = getSrcFile().baseFileName;
                        SrcEntry includeSrcEnt = layer.getBaseLayerFileFromRelName(srcFile);
                        if (includeSrcEnt != null) {
                           addInclude(sys, ix, includeSrcEnt);
                        }
                        else {
                           ie.displayError("Missing includeSuper - no file: " + srcFile + " in base layer of: " + layer.getLayerName() + " for: ");
                        }
                     }
                  }
               }
            }
            ix++;
         }
      }
      super.start();
   }

   /** By default, all scripts use the public modifier */
   public Object getModelDefaultModifier() {
      return "public";
   }

   private void addInclude(LayeredSystem sys, int ix, SrcEntry includeSrcEnt) {
      Layer layer = getLayer();
      ILanguageModel model;
      if (layer == null || !layer.activated)
         model = sys.getAnnotatedModel(includeSrcEnt);
      else {
         Object incFile = sys.parseActiveFile(includeSrcEnt);
         if (incFile instanceof ParseError) {
            displayError("Error parsing include file: " + includeSrcEnt + " for: ");
            return;
         }
         else if (incFile instanceof CmdScriptModel)
            model = (CmdScriptModel) incFile;
         else {
            displayError("Expected 'scr' file not: " + incFile);
            return;
         }
      }
      if (model instanceof CmdScriptModel) {
         if (includeCommands == null)
            includeCommands = new ArrayList<IncludeCommand>();
         IncludeCommand ic = new IncludeCommand();
         ic.commandIx = ix;
         ic.includedModel = (CmdScriptModel) model;
         includeCommands.add(ic);
      }

   }

   private IncludeCommand getNextIncludeIx(int fromIx) {
      int nextIncIx = -1;
      if (includeCommands != null) {
         for (int incIx = includeCommands.size() - 1; incIx >= 0; incIx--) {
            IncludeCommand incCmd = includeCommands.get(incIx);
            if (incCmd.commandIx < fromIx)
               return incCmd;
         }
      }
      return null;
   }

   private void initCmdObject() {
      cmdObject = (AbstractInterpreter.DefaultCmdClassDeclaration) AbstractInterpreter.defaultCmdObject.deepCopy(ISemanticNode.CopyNormal, null);
      cmdObject.parentNode = this;
      if (!cmdObject.isStarted())
         ParseUtil.initAndStartComponent(cmdObject);

   }

   /** Edit-time type resolution for the CmdScriptModel - resolves fields in the 'commands' list, preserves 'order of resolution' for script's line-by-line model,
    * resolves cmd.include and cmd.includeSuper */
   public Object findMember(String name, EnumSet<MemberType> mtype, Object fromChild, Object refType, TypeContext ctx, boolean skipIfaces) {
      if (mtype.contains(MemberType.ObjectType) && name.equals("cmd"))
         return getCmdObject();
      Object res = super.findMember(name, mtype, fromChild, refType, ctx, skipIfaces);
      if (res != null)
         return res;

      res = getCmdObject().definesMember(name, mtype, refType, ctx, skipIfaces, false);
      if (res != null)
         return res;

      if (commands != null) {
         int fromIx;
         if (fromChild != null) {
            fromIx = commands.indexOf(fromChild);
            if (fromIx == -1)
               return null;
         }
         else
            fromIx = commands.size();

         IncludeCommand nextInclude = getNextIncludeIx(fromIx);

         for (int i = fromIx - 1; i >= 0; i--) {
            if (nextInclude != null && i == nextInclude.commandIx) {
               res = nextInclude.includedModel.findMember(name, mtype, null, refType, ctx, skipIfaces);
               if (res != null)
                  return res;
               nextInclude = getNextIncludeIx(i);
            }

            Object command = commands.get(i);
            if (command instanceof JavaSemanticNode) {
               JavaSemanticNode javaSemNode = (JavaSemanticNode) command;
               res = javaSemNode.definesMember(name, mtype, refType, ctx, skipIfaces, false);
               if (res != null)
                  return res;
            }
         }

         LayeredSystem sys = getLayeredSystem();
         Object currentType = getCurrentTypeAt(fromIx);
         if (currentType != null) {
            do {
               res = ModelUtil.definesMember(currentType, name, mtype, refType, ctx, sys);
               if (res != null)
                  return res;
               currentType = ModelUtil.getEnclosingType(currentType);
            } while (currentType != null);
         }
      }
      return null;
   }

   public Object findMethod(String name, List<? extends Object> params, Object fromChild, Object refType, boolean staticOnly, Object inferredType) {
      Object res = super.findMethod(name, params, fromChild, refType, staticOnly, inferredType);
      if (res != null)
         return res;

      if ((res = getCmdObject().definesMethod(name, params, null, refType, false, staticOnly, inferredType, null)) != null)
         return res;

      if (commands != null) {
         int fromIx;
         if (fromChild != null) {
            fromIx = commands.indexOf(fromChild);
            if (fromIx == -1)
               return null;
         }
         else
            fromIx = commands.size();

         IncludeCommand nextInclude = getNextIncludeIx(fromIx);

         for (int i = fromIx - 1; i >= 0; i--) {
            if (nextInclude != null && i == nextInclude.commandIx) {
               res = nextInclude.includedModel.findMethod(name, params, null, refType, staticOnly, inferredType);
               if (res != null)
                  return res;
            }

            Object command = commands.get(i);
            if (command instanceof JavaSemanticNode) {
               JavaSemanticNode javaSemNode = (JavaSemanticNode) command;
               res = javaSemNode.definesMethod(name, params, null, refType, false, staticOnly, inferredType, null);
               if (res != null)
                  return res;
            }
         }

         LayeredSystem sys = getLayeredSystem();
         Object currentType = getCurrentTypeAt(fromIx);
         if (currentType != null) {
            do {
               res = ModelUtil.definesMethod(currentType, name, params, null, refType, false, staticOnly, inferredType, null, sys);
               if (res != null)
                  return res;
               currentType = ModelUtil.getEnclosingType(currentType);
            } while (currentType != null);
         }
      }
      return null;
   }

   public Object findType(String name, Object refType, TypeContext ctx) {
      Object res = super.findType(name, refType, ctx);
      if (res != null)
         return res;

      if ((res = getCmdObject().definesType(name, ctx)) != null)
         return res;

      if (commands != null) {
         // TODO: would it be helpful to pass fromChild to findType so we can be more accurate here?
         int fromIx = commands.size();

         IncludeCommand nextInclude = getNextIncludeIx(fromIx);

         for (int i = fromIx - 1; i >= 0; i--) {
            if (nextInclude != null && i == nextInclude.commandIx) {
               res = nextInclude.includedModel.findType(name, refType, ctx);
               if (res != null)
                  return res;
            }

            Object command = commands.get(i);
            if (command instanceof JavaSemanticNode) {
               JavaSemanticNode javaSemNode = (JavaSemanticNode) command;
               res = javaSemNode.definesType(name, ctx);
               if (res != null)
                  return res;
            }
         }

         LayeredSystem sys = getLayeredSystem();
         Object currentType = getCurrentTypeAt(fromIx);
         if (currentType != null) {
            do {
               res = ModelUtil.getInnerType(currentType, name, ctx);
               if (res != null)
                  return res;
               currentType = ModelUtil.getEnclosingType(currentType);
            } while (currentType != null);
         }
      }
      return null;
   }

   private Object getCurrentTypeAt(int fromIx) {
      ArrayList<String> pathNameList = new ArrayList<String>();

      for (int i = 0; i < fromIx; i++) {
         Object command = commands.get(i);
         if (command instanceof Package) {
            pathNameList.clear();
            pathNameList.add(((Package) command).name);
         }
         if (command instanceof TypeDeclaration) {
            pathNameList.add(((TypeDeclaration)command).getTypeName());
         }
         else if (command instanceof EndTypeDeclaration) {
            if (pathNameList.size() > 0)
               pathNameList.remove(pathNameList.size()-1);
         }
      }
      if (pathNameList.size() == 0)
         return null;
      StringBuilder typeName = new StringBuilder();
      for (String pathName:pathNameList) {
         if (typeName.length() != 0)
            typeName.append(".");
         typeName.append(pathName);
      }
      return findTypeDeclaration(typeName.toString(), false);
   }

   public String getChildTypeName(BodyTypeDeclaration childType, String useTypeName) {
      int fromIx = commands == null ? -1 : commands.indexOf(childType);
      if (fromIx == -1)
         return super.getChildTypeName(childType, useTypeName);
      Object currentType = fromIx == 0 ? null : getCurrentTypeAt(fromIx);
      if (currentType != null) {
         return CTypeUtil.prefixPath(ModelUtil.getTypeName(currentType), useTypeName);
      }
      // The command line interface allows an absolute type reference in a modify declaration.  if we can resolve a type with the whole type name
      // do not prepend the JavaModel's package like we do in the super.
      else {
         LayeredSystem sys = getLayeredSystem();
         if (sys != null) {
            Object absType = sys.getTypeDeclaration(useTypeName, false, sys.buildLayer, false);
            if (absType != null)
               return useTypeName;
         }
      }
      return super.getChildTypeName(childType, useTypeName);
   }

   public AbstractInterpreter.DefaultCmdClassDeclaration getCmdObject() {
      if (cmdObject == null)
         initCmdObject();
      return cmdObject;
   }

   public Object definesType(String typeName, TypeContext ctx) {
      if (typeName.equals("cmd"))
         return getCmdObject();
      return super.definesType(typeName, ctx);
   }

   public void findMatchingGlobalNames(String prefix, String prefixPkgName, String prefixBaseName, Set<String> candidates) {
      super.findMatchingGlobalNames(prefix, prefixPkgName, prefixBaseName, candidates);
      // Also for the command line need to include any properties defined in the cmdObject
      ModelUtil.suggestMembers(this, cmdObject, prefixBaseName, candidates, false, true, true, false);

      if ("cmd".startsWith(prefixBaseName))
         candidates.add("cmd");

      if (commands != null) {
         for (Object command:commands) {
            if (command instanceof FieldDefinition) {
               FieldDefinition field = (FieldDefinition) command;
               if (field.variableDefinitions != null) {
                  for (VariableDefinition varDef:field.variableDefinitions) {
                     if (varDef.variableName.startsWith(prefixBaseName))
                        candidates.add(varDef.variableName);
                  }
               }
            }
         }
      }
      if (includeCommands != null) {
         for (int incIx = 0; incIx < includeCommands.size(); incIx++) {
            IncludeCommand incCmd = includeCommands.get(incIx);
            incCmd.includedModel.findMatchingGlobalNames(prefix, prefixPkgName, prefixBaseName, candidates);
         }
      }
   }

   public TypeDeclaration getPreviousDeclaration(String fullClassName) {
      // For the command scripts, modify is used to just select the most-specific type so we are passing fromLayer = null and
      return (TypeDeclaration) layeredSystem.getSrcTypeDeclaration(fullClassName, null, prependLayerPackage, false, true, layer, isLayerModel);
   }

   public List<SrcEntry> getProcessedFiles(Layer buildLayer, String buildSrcDir, boolean generate) {
      return getLanguage().getProcessedFiles(this, buildLayer, buildSrcDir, generate);
   }

   public CommandSCLanguage getLanguage() {
      return CommandSCLanguage.getCommandSCLanguage();
   }

   public boolean enableExtensions() {
      return true;
   }
}
