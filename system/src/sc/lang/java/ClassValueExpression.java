/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.bind.ConstantBinding;
import sc.lang.SemanticNodeList;
import sc.type.CTypeUtil;
import sc.type.Type;
import sc.util.StringUtil;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/* Something.class */
public class ClassValueExpression extends Expression {
   public String typeIdentifier;
   public String arrayBrackets;

   private transient Object boundType;

   private transient Object paramType;

   public void start() {
      if (started) return;

      super.start();

      if (typeIdentifier == null)
         return;

      String classIdentifier = CTypeUtil.getClassName(typeIdentifier);
      String packageName = CTypeUtil.getPackageName(typeIdentifier);

      JavaModel model = getJavaModel();
      if (model == null)
         return;

      if (StringUtil.isEmpty(packageName)) {
         boundType = findType(classIdentifier, null, null);
         if (boundType == null && Type.getPrimitiveType(classIdentifier) != null)
            boundType = PrimitiveType.create(classIdentifier);
      }

      if (boundType == null && (boundType = model.findTypeDeclaration(typeIdentifier, true)) == null) {
         displayTypeError("No class: " + typeIdentifier + " for ");
      }
      else {
         if (arrayBrackets != null) {
            boundType = new ArrayTypeDeclaration(getLayeredSystem(), getEnclosingType(), boundType, arrayBrackets);
         }
         ArrayList<Object> types = new ArrayList<Object>(1);
         types.add(boundType);
         paramType = new ParamTypeDeclaration(getLayeredSystem(), getEnclosingType(), ModelUtil.getTypeParameters(Class.class), types, Class.class);
      }
   }

   public void validate() {
      if (validated) return;

      super.validate();

      // Needs to be done in validate so we know that isDynamicType is properly set on the bound type
      if (boundType != null) {
         if (boundType instanceof BodyTypeDeclaration) {
            BodyTypeDeclaration typeDecl = (BodyTypeDeclaration) boundType;
            // Only set this to true if this is not already a dynamic stub.  We'll generate some ClassValueExpressions in DynObject.resolveName calls for example but we ensure there's already a compiled class.  We don't want to generate those extra constructors since it means more code gen caused by code gen
            if (typeDecl.isDynamicNew()) {
               if (!typeDecl.isDynamicStub(false))
                  typeDecl.enableNeedsCompiledClass();
            }
            else {
               // Do not optimize away this class when someone needs that class.
               // Don't set this during the transform phase for ClassValue expressions we create at runtime.  We'll already have compiled the code for the
               if (!typeDecl.getLayeredSystem().allTypesProcessed) {
                  typeDecl.needsOwnClass = true;
               }
            }
         }
      }
   }

   public Object eval(Class expectedType, ExecutionContext ctx) {
      if (boundType == null)
         return Object.class;

      Object useType = resolveRuntimeType();

      if (bindingDirection != null)
         return new ConstantBinding(useType);

      Object val = arrayBrackets == null ?
              (useType == null ? ctx.resolveUnboundName(typeIdentifier) : useType) :
              ctx.resolveUnboundName(typeIdentifier + arrayBrackets);
      /*
       * Dynamic types eval to an Object here
      if (!(val instanceof Class))
         throw new IllegalArgumentException("Value of class value express is not a class");
       */
      return val;
   }

   Object resolveClassType() {
      return boundType == null ? Object.class : boundType;
   }

   Object resolveRuntimeType() {
      if (boundType == null)
         return Object.class;
      Object useType = boundType;

      // For TypeDeclarations, check to see if the Class of this type matches the actual class.  In that case,
      // we can safely return the compiled type in its place.   More stuff works.  This should always be the case
      // if needCompiledClass is set.
      if (boundType instanceof TypeDeclaration) {
         // Need to resolve in case this type was replaced or modified
         TypeDeclaration bt = (TypeDeclaration) ModelUtil.resolve(boundType, true);
         boundType = bt;
         if (!bt.isStarted())
            ModelUtil.startType(bt);
         Object compType = ModelUtil.getCompiledClass(boundType);
         if (ModelUtil.sameTypes(compType, boundType))
            useType = compType;
      }
      return useType;
   }

   public void evalBindingArgs(List<Object> bindArgs, boolean isStatic, Class expectedType, ExecutionContext ctx) {
      bindArgs.add(resolveRuntimeType());
   }

   // Foobar.class should return Class<Foobar> so we provide the proper type information
   public Object getTypeDeclaration() {
      if (paramType != null)
         return paramType;
      return Class.class;
   }

   public Object getGenericArgumentType() {
      if (boundType != null)
         return boundType;
      return Class.class;
   }

   public static ClassValueExpression create(String typeName) {
      ClassValueExpression cv = new ClassValueExpression();
      if (typeName == null)
         throw new IllegalArgumentException("Invalid null class name to for ClassValueExpression");
      int ix;
      if ((ix = typeName.indexOf("[]")) != -1) {
         cv.typeIdentifier = typeName.substring(0, ix);
         cv.arrayBrackets = typeName.substring(ix);
      }
      else
         cv.typeIdentifier = typeName;
      return cv;
   }

   public static ClassValueExpression create(String typeName, String arrayDimensions) {
      ClassValueExpression cv = new ClassValueExpression();
      if (typeName == null)
         throw new IllegalArgumentException("Invalid null class name to for ClassValueExpression");
      cv.typeIdentifier = typeName;
      cv.arrayBrackets = arrayDimensions;
      return cv;
   }

   public boolean isConstant() {
      return true;
   }

   public boolean isStaticTarget() {
      return true;
   }

   public int suggestCompletions(String prefix, Object currentType, ExecutionContext ctx, String command, int cursor, Set<String> candidates, Object continuation, int max) {
      String typeName = typeIdentifier;
      if (typeName == null || typeName.length() == 0 || arrayBrackets != null)
         return -1;

      JavaModel model = getJavaModel();
      if (model == null)
         return -1;

      String pkgName = CTypeUtil.getPackageName(typeName);
      String leafName = typeName;
      if (pkgName != null) {
         leafName = CTypeUtil.getClassName(leafName);
         ModelUtil.suggestTypes(model, pkgName, leafName, candidates, false, false, max);
      }

      boolean endsWithDot = continuation != null && continuation instanceof Boolean;

      // For a.b. completions
      if (endsWithDot) {
         ModelUtil.suggestTypes(model, typeName, "", candidates, true);

         prefix = CTypeUtil.prefixPath(prefix, typeName);
         typeName = "";
      }

      ModelUtil.suggestTypes(model, prefix, typeName, candidates, true, false, max);

      int relPos = -1;

      if (parseNode != null && parseNode.getStartIndex() != -1)
         relPos = parseNode.getStartIndex() + parseNode.lastIndexOf(leafName);
      else
         relPos = command.lastIndexOf(leafName);

      if (endsWithDot)
         relPos += prefix.length() + 1;
      return relPos;
   }

   public void refreshBoundTypes(int flags) {
      if (boundType != null) {
         boundType = ModelUtil.refreshBoundType(getLayeredSystem(), boundType, flags);
      }
   }

   public void addDependentTypes(Set<Object> types, DepTypeCtx mode) {
      if (boundType != null)
         addDependentType(types, boundType, mode);
   }

   public Statement transformToJS() {
      Statement repl = IdentifierExpression.create(getLayeredSystem().runtimeProcessor.getStaticPrefix(boundType, this));
      parentNode.replaceChild(this, repl);
      return repl;
   }

   public String getBindingTypeName() {
      if (nestedBinding)
         return "constantP";
      else
         return "constant";
   }

   public void transformBindingArgs(SemanticNodeList<Expression> bindArgs, BindDescriptor bd) {
      bindArgs.add(this);

      // Need to clear this so we don't try to transform it again the second time.
      bindingStatement = null;
   }

   public String toGenerateString() {
      StringBuilder sb = new StringBuilder();
      sb.append(typeIdentifier);
      if (arrayBrackets != null)
         sb.append(arrayBrackets);
      sb.append(".class");
      return sb.toString();
   }

   public ClassValueExpression deepCopy(int options, IdentityHashMap<Object, Object> oldNewMap) {
      ClassValueExpression res = (ClassValueExpression) super.deepCopy(options, oldNewMap);

      if ((options & CopyInitLevels) != 0) {
         res.boundType = boundType;
      }
      return res;
   }

   public void stop() {
      super.stop();
      boundType = null;
      paramType = null;
   }
}
