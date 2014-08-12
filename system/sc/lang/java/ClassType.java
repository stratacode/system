/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ILanguageModel;
import sc.lang.JavaLanguage;
import sc.lang.SemanticNodeList;
import sc.lang.js.JSUtil;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.type.CTypeUtil;
import sc.type.Type;
import sc.util.StringUtil;

import java.util.*;

public class ClassType extends JavaType {
   public String typeName;
   public SemanticNodeList<JavaType> typeArguments;  // Note: only set on the last JavaType in the chain.  We pass around the first on in the chain so use getResolvedTypeArguments not this field.
   public SemanticNodeList<ClassType> chainedTypes;
   public boolean typeArgument;        // Is this type part of a type argument

   public String signatureCode;        // only set when this comes from a compiled type - L or T as per Java sig.. used only for model -> signature if we ever need that

   private boolean chained;            // Is this type part of another parent type
   transient Object runtimeClass;
   transient Object type;
   private transient String compiledTypeName;
   transient Object[] errorArgs;

   private final static Object FAILED_TO_INIT_SENTINEL = "Invalid type sentinel";

   public static JavaType create(String... arr) {
      // This really messes things up with the parselets system - there can't be a ClassType("int") as per the grammar - so make sure to create the right type.
      if (arr.length == 1 && Type.getPrimitiveType(arr[0]) != null)
         return PrimitiveType.create(arr[0]);
      ClassType root = new ClassType();
      String last = arr[arr.length-1];
      int ix = last.indexOf("[]");
      if (ix != -1) {
         root.arrayDimensions = last.substring(ix);
         arr[arr.length-1] = arr[arr.length-1].substring(0,ix);
      }
      String arg = arr[0];
      if (arg.indexOf(".") != -1) {
         String[] firstList = StringUtil.split(arg, '.');
         root.typeName = firstList[0];
         for (int i = 1; i < firstList.length; i++)
            addChainedType(root, firstList[i]);
      }
      else
         root.typeName = arg;

      for (int i = 1; i < arr.length; i++) {
         arg = arr[i];
         if (arg.indexOf('.') != -1) {
            String[] toAdd = StringUtil.split(arg, '.');
            for (String name:toAdd) {
               addChainedType(root, name);
            }
         }
         else {
            addChainedType(root, arr[i]);
         }
      }
      return root;
   }

   public static ClassType createStarted(Object boundType, String...args) {
      ClassType cl = (ClassType) ClassType.create(args);
      cl.type = boundType;
      cl.initialize();
      cl.started = true;
      return cl;
   }

   private static void addChainedType(ClassType root, String name) {
      ClassType ct = new ClassType();
      ct.typeName = name;
      ct.chained = true;
      SemanticNodeList<ClassType> newTypes = root.chainedTypes;
      if (newTypes == null) {
         newTypes = new SemanticNodeList<ClassType>(4);
         newTypes.add(ct);
         root.setProperty("chainedTypes", newTypes);
      }
      else
         newTypes.add(ct);
   }

   public void initialize() {
      if (initialized)
         return;

      if (chainedTypes != null)
         for (ClassType t:chainedTypes)
           t.chained = true;

      super.initialize();
   }

   public void start() {
      if (started)
         return;
      // Initialize our bound type to catch errors, register dependencies etc.  ClassDeclaration will already have
      // initialized its extend type with a different resolver to eliminate recursive lookups (though I think the
      // pre-init of the "type" will have fixed that already a different way).
      ITypeDeclaration itype = getEnclosingIType();
      if (itype != null && type == null)
         initType(itype, this, true, false);
      super.start();
   }

   /** Returns the complete type name including the import */
   public String getFullBaseTypeName() {
      if (chainedTypes == null)
          return typeName;

      StringBuilder sb = new StringBuilder();

      sb.append(typeName);
      for (ClassType t:chainedTypes) {
         if (t.typeName != null) {  // Skipping an empty path name - such as when we are suggesting completions for a.
            sb.append(".");
            sb.append(t.typeName);
         }
      }
      return sb.toString();
   }

   public String getAbsoluteGenericTypeName(Object resultType, boolean includeDims) {
      StringBuilder sb = new StringBuilder();
      sb.append(getAbsoluteBaseTypeName());
      if (typeArguments != null) {  // TODO: Should this be getResolvedTypeArguments?
         sb.append(typeArguments.toLanguageString());
      }
      if (includeDims && arrayDimensions != null)
         sb.append(arrayDimensions);
      return sb.toString();
   }

   public String getGenericTypeName(Object resultType, boolean includeDims) {
      StringBuilder sb = new StringBuilder();
      sb.append(getFullBaseTypeName());
      if (typeArguments != null) {  // TODO: Should this be getResolvedTypeArguments?
         sb.append(typeArguments.toLanguageString(JavaLanguage.getJavaLanguage().optTypeArguments));
      }
      if (includeDims && arrayDimensions != null)
         sb.append(arrayDimensions);
      return sb.toString();
   }

   public String getAbsoluteTypeName() {
      String baseName = getAbsoluteBaseTypeName();
      if (arrayDimensions == null)
         return baseName;
      return baseName + arrayDimensions;
   }

   public String getFullTypeName() {
      String baseName = getFullBaseTypeName();
      if (arrayDimensions == null)
         return baseName;
      return baseName + arrayDimensions;
   }

   /** Returns just the base class name from this class type, stripping off the package prefix (if any) */
   public String getClassName() {
      if (chainedTypes == null)
         return typeName;

      return chainedTypes.get(chainedTypes.size() - 1).typeName;
   }

   /** Returns just the package name part - or null if this is just is a simple type name */
   public String getPackageName() {
      if (chainedTypes == null)
         return null;

      StringBuffer sb = new StringBuffer();
      sb.append(typeName);
      for (int i = 0; i < chainedTypes.size() - 1; i++)
      {
         sb.append(".");
         sb.append(chainedTypes.get(i).typeName);
      }
      return sb.toString();
   }

   public Class getRuntimeBaseClass() {
      if (runtimeClass == null) {
         ITypeDeclaration itype = getEnclosingIType();
         if (itype == null)
            return null;
         runtimeClass = itype.getClass(getFullBaseTypeName(), true);
      }
      return runtimeClass instanceof Class ? (Class) runtimeClass : null;
   }

   public Class getRuntimeClass() {
      return ModelUtil.getCompiledClass(type);
      /*
      Class rtClass = getRuntimeBaseClass();
      if (arrayDimensions == null || rtClass == null)
         return rtClass;
      return Type.get(rtClass).getArrayClass(rtClass, arrayDimensions.length() >> 1);
      */
   }

   public Object getRuntimeType() {
      return ModelUtil.getRuntimeType(type);
      /*
      Class rtClass = getRuntimeBaseClass();
      if (arrayDimensions == null || rtClass == null)
         return rtClass;
      return Type.get(rtClass).getArrayClass(rtClass, arrayDimensions.length() >> 1);
      */
   }

   public boolean isVoid() {
      return false;
   }

   public Object getTypeDeclaration() {
      // We should only be accessing the top level ClassType's type declaration
      // since the chained types are just part of this definition.
      assert !chained;
      
      if (type == null) {
         ITypeDeclaration itd = getEnclosingIType();
         if (itd != null) {
            initType(itd, this, true, false);
         }
      }
      if (type == FAILED_TO_INIT_SENTINEL)
         return null;

      if (type instanceof TypeDeclaration) {
         TypeDeclaration td = (TypeDeclaration) type;
         type = td.resolve(true);
      }
      return type;
   }

   public void setTypeDeclaration(Object typeObj) {
      type = typeObj;
   }

   public void setResolvedTypeArguments(SemanticNodeList typeArgs) {
      ClassType ct = this;
      if (chainedTypes != null)
         ct = chainedTypes.get(chainedTypes.size()-1);
      ct.setProperty("typeArguments", typeArgs);
   }

   /** Our type arguments are always stored on the last node of a chained type */
   public List<JavaType> getResolvedTypeArguments() {
      if (!isInitialized())
         initialize();
      if (!isStarted() && type == null)
         start();
      if (chainedTypes != null) {
         if (!chainedTypes.isStarted())
            chainedTypes.start();
         return chainedTypes.get(chainedTypes.size()-1).typeArguments;
      }
      if (typeArguments != null && !typeArguments.isStarted())
         typeArguments.start();
      return typeArguments;
      
   }

   public List<Object> getTypeArgumentDeclarations() {
      List<JavaType> typeArgs = getResolvedTypeArguments();
      if (typeArgs == null)
         return null;
      List<Object> typeDefs = new ArrayList<Object>(typeArgs.size());
      for (int i = 0; i < typeArgs.size(); i++) {
         Object argType = typeArgs.get(i).getTypeDeclaration();
         // If it's unbound, we add the type parameter directly.
         typeDefs.add(argType);
      }
      return typeDefs;
   }

   public Object getTypeArgumentDeclaration(int ix) {
      List<JavaType> typeArgs = getResolvedTypeArguments();
      if (typeArgs == null || ix >= typeArgs.size())
         return null;
      return typeArgs.get(ix).getTypeDeclaration();
   }

   public Object getTypeArgument(int ix) {
      List<JavaType> typeArgs = getResolvedTypeArguments();
      if (typeArgs == null || ix >= typeArgs.size())
         return null;
      return typeArgs.get(ix);
   }

   public void displayError(String...args) {
      errorArgs = args;
      super.displayError(args);
   }

   public void displayTypeError(String...args) {
      Statement st;
      errorArgs = args;
      super.displayTypeError(args);
      /*
      if ((st = getEnclosingStatement()) != null)
         st.displayTypeError(args);
      else
         super.displayTypeError(args);
      */
   }

   /**
    * Initializes this type from the model and node specified.  The ClassDeclaration needs to resolve the type
    * name using itself, so that it will not recursively check the type that is being initialized.
    */
   public void initType(ITypeDeclaration it, JavaSemanticNode node, boolean displayError, boolean isLayer) {
      if (chained)
         return; // Don't need to init if we are just part of someone else's type

      String fullTypeName = getFullBaseTypeName();

      type = FAILED_TO_INIT_SENTINEL; // prevent recursive calls

      if (fullTypeName == null)
         return; // Invalid fragment

      if (fullTypeName.equals("IListener"))
         System.out.println("***");

      if (isLayer) {
         LayeredSystem sys = it.getLayeredSystem();
         if (sys != null){
            // If this is a reference from an annotated layer model we need to annotate the type so we cna make the layer
            // references work like normal types.
            if (it instanceof TypeDeclaration) {
               JavaModel curModel = it.getJavaModel();
               if (curModel != null && curModel.getUserData() != null) {
                  JavaModel layerModel = sys.getAnnotatedLayerModel(fullTypeName, CTypeUtil.getPackageName(curModel.getLayer().getLayerName()));
                  if (layerModel != null)
                     type = layerModel.getModelTypeDeclaration();
               }
            }
         }
         return;
      }

      type = node.findType(fullTypeName);

      if (type == null) { // not a relative name
         type = it.findTypeDeclaration(fullTypeName, true);
         if (type == null) {
            type = FAILED_TO_INIT_SENTINEL;
            if (displayError) {
               displayTypeError("No type: ", getFullTypeName(), " for ");
            }
         }
      }

      // Since there is a class reference to this type, we cannot optimize away that class for inner objects
      if (type instanceof BodyTypeDeclaration) {
         BodyTypeDeclaration btype = (BodyTypeDeclaration) type;
         LayeredSystem sys = btype.getLayeredSystem();

         // Once we start transforming things, it seems we pull in type references to object types which we do not count to decide if we need the class
         if (sys != null && !sys.allTypesProcessed) {
            btype.needsOwnClass = true;
         }
      }

      // Before we return the type, check and see if this definition adds any bound type parameters -
      // i.e. concrete types instead of parameter names.  If so, we need to wrap the returned type with
      // an object which knows how to resolve type parameters to do the proper type matching when generics are used.
      List<Object> typeDefs = getTypeArgumentDeclarations();
      if (typeDefs != null && type != FAILED_TO_INIT_SENTINEL && anyBoundParameters(typeDefs)) {
         List<?> typeParams = ModelUtil.getTypeParameters(type);
         if (typeParams == null || typeParams.size() != typeDefs.size())
            displayError("Wrong number of type arguments for type: ", ModelUtil.getTypeName(type)," for ");
         else
            type = new ParamTypeDeclaration(it, typeParams, typeDefs, type);
      }

      if (arrayDimensions != null && type != FAILED_TO_INIT_SENTINEL) {
         type = new ArrayTypeDeclaration(it, type, arrayDimensions);
      }
   }

   private boolean anyBoundParameters(List<Object> typeDefs) {
      // TODO: remove this code.  We used to optimize things and only return ParamTypeDeclarations when they were bound.  But we need the type parameters in the type because they may be bound in the
      // current type context.  Need to get this info out of the type itself all of the time, or at least when we are binding through a type context.
      /*
      for (int i = 0; i < typeDefs.size(); i++) {
         Object argType = typeDefs.get(i);
         if (!(argType instanceof TypeParameter) && argType != null)
            return true;
      }
      return false;
      */
      return true;
   }

   public void setCompiledTypeName(String compiledTypeName) {
      this.compiledTypeName = compiledTypeName;
      setFullTypeName(compiledTypeName.replace('$','.').replace('/','.'));
   }

   public String getCompiledTypeName() {
      return compiledTypeName;
   }

   public void setFullTypeName(String fullTypeName) {
      int ix = fullTypeName.indexOf(".");
      String rootType;
      if (ix != -1) {
         rootType = fullTypeName.substring(0,ix);
         String rest = fullTypeName.substring(ix+1);

         SemanticNodeList<ClassType> newChainedTypes = new SemanticNodeList<ClassType>();

         int ci = 0;
         while ((ix = rest.indexOf(".")) != -1) {
            String next = rest.substring(0,ix);
            ClassType ct = new ClassType();
            ct.typeName = next;
            ct.chained = true;
            newChainedTypes.add(ct);
            rest = rest.substring(ix+1);
         }
         ClassType ct = new ClassType();
         ct.typeName = rest;
         ct.chained = true;
         newChainedTypes.add(ct);

         setProperty("chainedTypes", newChainedTypes);
      }
      else {
         rootType = fullTypeName;
         if (chainedTypes != null)
            setProperty("chainedTypes", null);
      }
      setProperty("typeName", rootType);
   }

   public static String getWrapperClassName(Object type) {
      String typeName = ModelUtil.getTypeName(type);
      return getWrapperClassFromTypeName(typeName);
   }

   public static String getWrapperClassFromTypeName(String typeName) {
      if (ModelUtil.isPrimitiveNumberType(typeName))
         return getPrimitiveWrapperName(CTypeUtil.getClassName(typeName));
      else
         return typeName;
   }

   public static String getPrimitiveWrapperName(String prim) {
      String name;
      switch (prim.charAt(0)) {
         case 'i':
            name = "Integer";
            break;
         case 'b':
            if (prim.charAt(1) == 'o')
               name = "Boolean";
            else
               name = "Byte";
            break;
         case 's':
            name = "Short";
            break;
         case 'd':
            name = "Double";
            break;
         case 'c':
            name = "Character";
            break;
         case 'f':
            name = "Float";
            break;
         case 'l':
            name = "Long";
            break;
         case 'v':
            name = "Void";
            break;
         default:
            throw new UnsupportedOperationException();
      }
      return name;
   }

   public static Object createPrimitiveWrapper(String prim) {
      return create(getPrimitiveWrapperName(prim));
   }

   public String getBaseSignature() {
      return (signatureCode == null ? "L" : signatureCode) + getAbsoluteBaseTypeName().replace(".", "/")  + (signatureCode == null || signatureCode.equals("L") ? ";" : "");
   }

   public boolean transform(ILanguageModel.RuntimeType rt) {
      boolean any = super.transform(rt);
      if (chained)
         return false;

      if (type != null && type instanceof TypeDeclaration && (ModelUtil.isDynamicType(type) || !ModelUtil.needsOwnClass(type, false))) {
         String runtimeTypeName = ((TypeDeclaration) type).getCompiledClassName();
         setProperty("chainedTypes", null);
         if (runtimeTypeName.indexOf(".") != -1) {
            String[] firstList = StringUtil.split(runtimeTypeName, '.');
            setProperty("typeName", firstList[0]);
            for (int i = 1; i < firstList.length; i++)
               addChainedType(this, firstList[i]);
         }
         else {
            setProperty("typeName", runtimeTypeName);
         }
         any = true;
      }
      return any;
   }

   public void refreshBoundType() {
      if (chained)
         return;
      
      if (type != null && type != FAILED_TO_INIT_SENTINEL) {
         if (type instanceof TypeDeclaration) {
            TypeDeclaration td = (TypeDeclaration) type;
            if (td.getTransformed()) {
               type = ModelUtil.refreshBoundType(type);
            }
         }
      }
   }

   public String toSafeLanguageString() {
      if (parseNode == null || parseNodeInvalid)
         return toString();
      return super.toSafeLanguageString();
   }

   public String toString() {
      try {
         return getFullTypeName();
      }
      catch (RuntimeException exc) {
         return "<uninitialized ClassType>";
      }
   }

   public String toCompiledString(Object refType) {
      StringBuilder sb = new StringBuilder();
      if (type instanceof TypeParameter) {
         if (refType == null)
            return typeName;
         return ModelUtil.evalParameterForType(refType, getEnclosingType(), typeName);
      }
      List<JavaType> typeArgs = getResolvedTypeArguments();
      if (type != null && type instanceof TypeDeclaration && ModelUtil.isDynamicType(type)) {
         TypeDeclaration td = (TypeDeclaration) type;
         String runtimeTypeName = ((TypeDeclaration) type).getCompiledClassName();
         // As we skip classes in the extends hierarchy, we also may be eliminating one or all type args.
         typeArgs = td.getCompiledTypeArgs(typeArgs);
         if (!runtimeTypeName.equals("sc.dyn.IDynObject"))
            sb.append(runtimeTypeName);
         else
            return null;
      }
      else {
         if (type == null || type == FAILED_TO_INIT_SENTINEL)
            return typeName; // Returns the only sensible thing though this should only happen for errors

         // Using the absolute type here because we do not add imports from the main file into the stub file
         typeArgs = ModelUtil.getCompiledTypeArgs(type, typeArgs);
         String compName = ModelUtil.getCompiledClassName(type);
         if (!compName.equals("sc.dyn.IDynObject"))
            sb.append(compName);
         else // baseClassName needs to be null in this case.  Perhaps the default compiled class name is wrong though?
            return null;
      }
      if (typeArgs != null && typeArgs.size() > 0) {
         StringBuilder argSB = new StringBuilder();
         int i = 0;
         for (JavaType ta:typeArgs) {
            String res = ta.toCompiledString(refType);
            if (res == null)
               res = "sc.dyn.IDynObject";  // Or just Object?  We can't just drop the type arg as that ends up changing the classes signature in some weird way - see the paramTypeOverride test
            if (i != 0)
               argSB.append(", ");

            argSB.append(res);
            i++;
         }

         if (i != 0) {
            sb.append("<");
            sb.append(argSB);
            sb.append(">");
         }
      }

      if (arrayDimensions != null)
         sb.append(arrayDimensions);
      return sb.toString();
   }

   static void appendTypeArguments(StringBuilder sb, SemanticNodeList<JavaType> typeArgs) {
      if (typeArgs != null) {
         sb.append("<");
         int taix = 0;
         for (JavaType typeArg:typeArgs) {
            if (taix != 0)
               sb.append(", ");
            sb.append(typeArg.toGenerateString());
            taix++;
         }
         sb.append(">");
      }
   }

   public String toGenerateString() {
      StringBuilder sb = new StringBuilder();
      sb.append(typeName);
      if (chainedTypes != null) {
         for (JavaType ct:chainedTypes) {
            sb.append(".");
            sb.append(ct.toGenerateString());
         }
      }
      appendTypeArguments(sb, typeArguments);
      if (arrayDimensions != null) {
         sb.append(arrayDimensions);
      }
      return sb.toString();
   }

   public void compileTypeArgs() {
      List<?> tas = getResolvedTypeArguments();
      if (tas != null) {
         for (Object o:tas) {
            if (o instanceof ClassType) {
               ClassType co = (ClassType) o;
               Object td = co.getTypeDeclaration();
               if (td instanceof BodyTypeDeclaration)
                  ((BodyTypeDeclaration) td).getCompiledClass();
               co.compileTypeArgs();
            }
         }
      }
   }

   public boolean isTypeParameter() {
      return type instanceof TypeParameter;
   }

   public void transformToJS() {
      Object td = getTypeDeclaration();
      if (td == null) {
         System.err.println("*** Transform to JS: failed to resolve type: " + this);
         return;
      }
      setProperty("typeName", JSUtil.convertTypeName(getLayeredSystem(), ModelUtil.getTypeName(td, false)));
      if (typeArguments != null) // ClassType in a throws does not have a type argument so trying to set this when it's null will cause an error!
         setProperty("typeArguments", null);
      setProperty("chainedTypes", null);
   }

   /** Preserve the type when we do a deepCopy.  This speeds things up and also makes the system more flexible.  For example, when converting to JS, we may define a new enum class for each constant which is not resolveable in the normal Java model.  It's easy to assign the ClassType's type manually so it does not resolve but then we clone that model.  This ensures that the clone preserves the type and won't try to resolve it (unsuccessfully). */
   public ClassType deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap) {
      ClassType res = (ClassType) super.deepCopy(options, oldNewMap);
      if ((options & CopyState) != 0) {
         res.type = type;
      }

      if ((options & CopyInitLevels) != 0) {
         res.chained = chained;
         res.runtimeClass = runtimeClass;
         res.compiledTypeName = compiledTypeName;
      }
      return res;
   }

   public int suggestCompletions(String prefix, Object currentType, ExecutionContext ctx, String command, int cursor, Set<String> candidates, Object continuation) {
      JavaModel model = getJavaModel();
      if (model == null)
         return -1;

      String typeName = getFullTypeName();
      if (typeName == null)
         return -1;

      String leafName = CTypeUtil.getClassName(typeName);

      int pos = -1;
      if (parseNode != null) {
         pos = parseNode.getStartIndex();
         if (pos != -1) {
            int offset = parseNode.lastIndexOf(leafName);
            if (offset == -1)
               pos = -1;
            else
               pos += offset;
         }
      }

      if (pos == -1) {
         pos = command.lastIndexOf(leafName);
      }
      if (pos == -1)
         return -1;

      // When the identifier expression ends with "." so we match everything defined for the prefix only
      //boolean endsWithDot = continuation != null && continuation instanceof Boolean;
      boolean endsWithDot = chainedTypes != null && chainedTypes.get(chainedTypes.size()-1).typeName == null;

      if (endsWithDot)
         pos = pos + leafName.length() + 1;

      String pkgName = endsWithDot ? typeName : CTypeUtil.getPackageName(typeName);
      if (pkgName != null) {
         if (endsWithDot)
            leafName = "";
         ModelUtil.suggestTypes(model, pkgName, leafName, candidates, false);
         int csize = candidates.size();
         if (csize > 0) {
            // TODO: we had this code commented out for the rtext thing but now need it for IntelliJ
            // Need to test that "pos - pkgName.length()" is right for rtext now.
            HashSet<String> absCandidates = new HashSet<String>();
            for (String cand:candidates) {
               absCandidates.add(CTypeUtil.prefixPath(pkgName, cand));
            }
            candidates.clear();
            candidates.addAll(absCandidates);
            return pos - pkgName.length() - 1;
         }
      }

      if (endsWithDot) {
         prefix = CTypeUtil.prefixPath(prefix, typeName);
         typeName = "";
      }

      boolean includeProps = parentNode instanceof CastExpression;

      ModelUtil.suggestTypes(model, prefix, typeName, candidates, true);
      if (currentType != null)
         ModelUtil.suggestMembers(model, currentType, typeName, candidates, true, includeProps, includeProps);

      IBlockStatement enclBlock = getEnclosingBlockStatement();
      if (enclBlock != null)
         ModelUtil.suggestVariables(enclBlock, typeName, candidates);

      if (candidates.size() > 0)
         return pos;

      return -1;
   }

   public boolean applyPartialValue(Object value) {
      if (value instanceof ClassType) {
         ClassType other = (ClassType) value;

         if (typeName == null && other.typeName != null) {
            setProperty("typeName", other.typeName);
            return true;
         }
         if (typeName != null && other.typeName != null) {
            if (typeName.equals(other.typeName) && other.chainedTypes != null) {
               if (chainedTypes == null) {
                  setProperty("chainedTypes", other.chainedTypes);
                  return true;
               }
               // TODO: any more cases here?
            }
         }
      }
      else if (value instanceof List) {
         setProperty("chainedTypes", value);
      }
      return false;
   }

   public String getNodeErrorText() {
      if (errorArgs != null) {
         StringBuilder sb = new StringBuilder();
         for (Object arg:errorArgs)
            sb.append(arg.toString());
         sb.append(this.toString());
         return sb.toString();
      }
      return null;
   }

   public boolean isCollapsibleNode() {
      if (typeArguments != null)
         return false;
      if (chainedTypes != null) {
         for (ClassType chained:chainedTypes)
            if (!chained.isCollapsibleNode())
               return false;
      }
      return true;
   }
}
