/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.ISemanticNode;
import sc.lang.JavaLanguage;
import sc.lang.html.Element;
import sc.lang.template.Template;
import sc.layer.LayeredSystem;
import sc.parser.IParseNode;
import sc.type.PTypeUtil;
import sc.lang.SemanticNode;

import java.util.*;

/**
 * This is the basic class for all major Java program elements, types, statements, expressions, etc.  They are organized into
 * a tree so this method abstracts many of the tree-based "find" operations and core node information.
 *
 * TODO: As we add more languages it may make sense to split out LanguageNode and LanguageModel base classes from JavaSemanticNode and JavaModel
 * to hold features that are shared between Java and the rest of the major languages without picking up features that are definitely specific to Java.
 */
public abstract class JavaSemanticNode extends SemanticNode {

   /** When debugging problems in toLangaugeString - i.e. generating a language description from a changed model, set this to true to prevent the debugger from generating the thing during it's variable display */
   public static boolean debugDisablePrettyToString = false;

   /** Return from findMemberInBody when an Object has been found */
   public final static String STOP_SEARCHING_SENTINEL = "<stop-searching-sentinel>";

   public enum MemberType {
      // Presumably the iterator order for an EnumSet is this order which is the order in which we'll
      // do precedence... getMethod needs to be ahead of field.
      Variable, GetMethod, SetMethod, Field, Enum, Assignment, ObjectType, GetIndexed, SetIndexed, Initializer,
      // Forward assignment is to retrieve the most specific initializer object - either the variable definition or the property assignment, so we can tell how this property is initialized.
      ForwardAssignment;

      public static EnumSet<MemberType> FieldSet = EnumSet.of(Field);
      public static EnumSet<MemberType> FieldEnumSet = EnumSet.of(Field,Enum);
      public static EnumSet<MemberType> GetMethodSet = EnumSet.of(GetMethod);
      public static EnumSet<MemberType> SetMethodSet = EnumSet.of(SetMethod);
      public static EnumSet<MemberType> VariableSet = EnumSet.of(Variable);
      public static EnumSet<MemberType> AssignmentSet = EnumSet.of(Assignment);
      public static EnumSet<MemberType> PropertyGetSet = EnumSet.of(Field,GetMethod,Enum);
      public static EnumSet<MemberType> PropertySetSet = EnumSet.of(Field,SetMethod);
      public static EnumSet<MemberType> PropertyGetSetSet = EnumSet.of(Field,SetMethod,GetMethod);
      public static EnumSet<MemberType> ObjectTypeSet = EnumSet.of(ObjectType);
      public static EnumSet<MemberType> FieldOrObjectTypeSet = EnumSet.of(Field,ObjectType);
      public static EnumSet<MemberType> EnumOnlySet = EnumSet.of(Enum);
      public static EnumSet<MemberType> AllSet = EnumSet.of(Variable, GetMethod, SetMethod, Field, Enum, Assignment, ObjectType, GetIndexed, SetIndexed); // all but initializer which restricts the set to only include defs with an inititalizer
      public static EnumSet<MemberType> PropertyAssignmentSet = EnumSet.of(Field,GetMethod,Enum,Assignment);
      public static EnumSet<MemberType> PropertyAnySet = EnumSet.of(Field,SetMethod,GetMethod,Enum,Assignment);
      public static EnumSet<MemberType> PropertyGetObj = EnumSet.of(Field,GetMethod,Enum,ObjectType);
      public static EnumSet<MemberType> PropertyGetSetObj = EnumSet.of(Field,GetMethod,SetMethod,Enum,ObjectType);
      public static EnumSet<MemberType> InitializerSet = EnumSet.of(Field,SetMethod,GetMethod,Enum,Assignment,Initializer);
      public static EnumSet<MemberType> SyncSet = EnumSet.of(Field,SetMethod,GetMethod,Enum, ForwardAssignment, GetIndexed, SetIndexed);

      public static MemberType getMemberType(Object member, EnumSet<MemberType> types) {
         if (member instanceof ParamTypedMember)
            return ((ParamTypedMember) member).mtype;
         if (types.contains(Enum) && member instanceof EnumConstant)
            return Enum;
         if (types.contains(Field) && ModelUtil.isField(member))
            return Field;
         if (ModelUtil.isProperty(member)) {
            if (types.contains(GetMethod))
               return GetMethod;
            else if (types.contains(SetMethod))
               return SetMethod;
         }
         if (types.contains(ObjectType) && member instanceof ITypeDeclaration)
            return ObjectType;
         if (types.contains(Variable))
            return Variable;
         return null;
      }
   }

   static class MemberCacheEnt {
      EnumSet<MemberType> types;
      Object member;
      boolean stopSearching;
      int version;
      boolean isTransformed;

      // In case there is more than one with the same name.
      MemberCacheEnt next;
   }

   // MemberCache used for both largish BlockStatements and types to deal with deep extends/layered hierarchies.
   public class MemberCache {
      HashMap<String,MemberCacheEnt> cache = new HashMap<String,MemberCacheEnt>();

      // Adds to the cache.  Should be built from bottom up - i.e. do all derived types first, then do your body if you
      // are merging across the type hierarchy.
      public void addToCache(String name, Object member, EnumSet<MemberType> types, boolean stopSearching, int version, boolean isTransformed) {
         MemberCacheEnt newEnt = new MemberCacheEnt();
         newEnt.member = member;
         newEnt.types = types;
         newEnt.version = version;
         newEnt.stopSearching = stopSearching;
         newEnt.isTransformed = isTransformed;

         // Chain together all entries.
         newEnt.next = cache.put(name, newEnt);
      }

      // Do we have a valid entry for this cached member.
      /*
      public boolean hasEntry(String name, EnumSet<MemberType> types, boolean isTransformed) {
         MemberCacheEnt ent = cache.get(name);
         while (ent != null) {
            if (ent.types == types && ent.isTransformed == isTransformed)
               return true;
            ent = ent.next;
         }
         return false;
      }
      */

      public MemberCacheEnt getCacheEnt(String name, EnumSet<MemberType> types, boolean isTransformed) {
         MemberCacheEnt ent = cache.get(name);
         while (ent != null) {
            if (ent.types == null || types == ent.types) {
               if (isTransformed == ent.isTransformed)
                  return ent;
            }
            ent = ent.next;
         }
         return null;
      }

      public Object getCache(String name, Object refType, EnumSet<MemberType> types, boolean isTransformed) {
         MemberCacheEnt ent = cache.get(name);
         while (ent != null) {
            if (ent.types == null || types == ent.types) {
               if (isTransformed == ent.isTransformed) {
                  if (ent.stopSearching)
                     return STOP_SEARCHING_SENTINEL;

                  if (refType == null || ent.member == null || ent.member == STOP_SEARCHING_SENTINEL || ModelUtil.checkAccessList(refType, ent.member, types))
                     return ent.member;
               }
            }
            ent = ent.next;
         }
         return null;
      }
   }

   public JavaModel getJavaModel() {
      ISemanticNode parent = parentNode;
      while (parent != null) {
         if (parent instanceof JavaModel)
            return (JavaModel) parent;
         parent = parent.getParentNode();
      }
      return null;
   }

   public LayeredSystem getLayeredSystem() {
      JavaModel m = getJavaModel();
      if (m == null)
         return null;
      return m.getLayeredSystem();
   }

   public Object findMethod(String name, List<? extends Object> parametersOrExpressions, Object fromChild, Object refType, boolean staticOnly, Object inferredType) {
      for (ISemanticNode pnode = parentNode; pnode != null; pnode = pnode.getParentNode())
         if (pnode instanceof JavaSemanticNode)
            return ((JavaSemanticNode)pnode).findMethod(name, parametersOrExpressions, this, refType, staticOnly, inferredType);
      return null;
   }

   public Object definesMethod(String name, List<?> parametersOrExpressions, ITypeParamContext ctx, Object refType, boolean isTransformed, boolean staticOnly, Object inferredType, List<JavaType> methodTypeArgs) {
      return null;
   }

   public Object definesConstructor(List<?> parametersOrExpressions, ITypeParamContext ctx, boolean isTransformed) {
      return null;
   }

   public Object declaresConstructor(List<?> parametersOrExpressions, ITypeParamContext ctx) {
      return null;
   }

   public Object findMember(String name, EnumSet<MemberType> type, Object fromChild, Object refType, TypeContext ctx, boolean skipIfaces) {
      for (ISemanticNode pnode = parentNode; pnode != null; pnode = pnode.getParentNode())
         if (pnode instanceof JavaSemanticNode)
            return ((JavaSemanticNode)pnode).findMember(name, type, this, refType, ctx, skipIfaces);

      return null;
   }

   public Object findMemberOwner(String name, EnumSet<MemberType> type) {
      for (ISemanticNode pnode = parentNode; pnode != null; pnode = pnode.getParentNode())
         if (pnode instanceof JavaSemanticNode)
            return ((JavaSemanticNode)pnode).findMemberOwner(name, type);

      return null;
   }

   public Object definesMember(String name, EnumSet<MemberType> type, Object refType, TypeContext ctx, boolean skipIfaces, boolean isTransformed) {
      return null;
   }

   /*
   public void addToMemberCache(MemberCache cache, EnumSet<MemberType> filter) {
   }
   */

   public Object findType(String name) {
      return findType(name, null, null);
   }

   public Object findType(String name, Object refType, TypeContext context) {
      for (ISemanticNode pnode = parentNode; pnode != null; pnode = pnode.getParentNode()) {
         if (pnode instanceof JavaSemanticNode) {
            if (context != null && pnode instanceof BodyTypeDeclaration) {
               ITypeDeclaration override = context.getSubType((BodyTypeDeclaration) pnode);
               if (override != null)
                  return ((JavaSemanticNode) override).findType(name, refType, context);
            }
            return ((JavaSemanticNode)pnode).findType(name, refType, context);
         }
      }
      return null;
   }

   public Object definesType(String name, TypeContext ctx) {
      return null;
   }

   public TypeDeclaration getRootType() {
      TypeDeclaration last = null;
      for (ISemanticNode pnode = parentNode; pnode != null; pnode = pnode.getParentNode())
         if (pnode instanceof TypeDeclaration && (last = (TypeDeclaration) pnode).getEnclosingType() == null)
            return (TypeDeclaration) pnode;
      return last;
   }

   /** Used by the JavaType classes which may be parented by something other than a JavaModel */
   public ITypeDeclaration getEnclosingIType() {
      for (ISemanticNode pnode = parentNode; pnode != null; pnode = pnode.getParentNode()) {
         if (pnode instanceof ITypeDeclaration) {
            ITypeDeclaration typeDecl = (ITypeDeclaration) pnode;
            if (typeDecl.isRealType())
               return (ITypeDeclaration) pnode;
            else if (typeDecl instanceof TypeDeclaration)
               return ((TypeDeclaration) typeDecl).getEnclosingIType();
            return typeDecl;
         }
         if (pnode instanceof NewExpression) {
            NewExpression newEx = (NewExpression) pnode;
            if (newEx.classBody != null && !newEx.lambdaExpression) {
               // When you have a value in the parameters for a new anonymous class, it's enclosing type is not the
               // anonymous class but instead the anonymous classes enclosing class.
               if (newEx.arguments != null) {
                  boolean isParameter = false;
                  ISemanticNode parent = parentNode;
                  while (parent != null) {
                     if (parent == newEx.arguments) {
                        isParameter = true;
                        break;
                     }
                     if (parent == newEx)
                        break;
                     parent = parent.getParentNode();
                  }
                  if (!isParameter)
                     return newEx.getAnonymousType(false);
               }
               else
                  return newEx.getAnonymousType(false);
            }
         }
      }
      return null;
   }

   /** Like the regular getEnclosingType but does not try to initialize an anonymous type from a new expression */
   public BodyTypeDeclaration getStructuralEnclosingType() {
      for (ISemanticNode pnode = parentNode; pnode != null; pnode = pnode.getParentNode()) {
         if (pnode instanceof TypeDeclaration) {
            TypeDeclaration td = (TypeDeclaration) pnode;
            // Skip TemplateDeclarations which are not real
            if (td.isRealType())
               return (TypeDeclaration) pnode;
            else
               return td.getEnclosingType();
         }
      }
      return null;
   }

   public TypeDeclaration getEnclosingType() {
      for (ISemanticNode pnode = parentNode; pnode != null; pnode = pnode.getParentNode()) {
         if (pnode instanceof TypeDeclaration) {
            TypeDeclaration td = (TypeDeclaration) pnode;
            // Skip TemplateDeclarations which are not real
            if (td.isRealType())
               return (TypeDeclaration) pnode;
            else
               return td.getEnclosingType();
         }
         else if (pnode instanceof Element) {
            Element elem = (Element) pnode;
            TypeDeclaration res = elem.getElementTypeDeclaration();
            if (res == null)
               return elem.getEnclosingType();
            return res;
         }
         if (pnode instanceof NewExpression) {
            NewExpression newEx = (NewExpression) pnode;
            if (newEx.classBody != null && !newEx.lambdaExpression)
               return newEx.getAnonymousType(false);
         }
      }
      return null;
   }

   /** Will retrieve the node which holds the list of statements, for searching for variables, etc. */
   public IBlockStatement getEnclosingBlockStatement() {
      for (ISemanticNode pnode = parentNode; pnode != null; pnode = pnode.getParentNode())
         if (pnode instanceof IBlockStatement)
            return (IBlockStatement) pnode;
      return null;
   }

   public Statement getEnclosingStatement() {
      for (ISemanticNode pnode = parentNode; pnode != null; pnode = pnode.getParentNode())
         if (pnode instanceof Statement)
            return (Statement) pnode;
      return null;
   }

   public AbstractMethodDefinition getEnclosingMethod() {
      for (ISemanticNode pnode = parentNode; pnode != null; pnode = pnode.getParentNode()) {
         if (pnode instanceof AbstractMethodDefinition)
            return (AbstractMethodDefinition) pnode;
         if (pnode instanceof LambdaExpression) {
            return ((LambdaExpression) pnode).getLambdaMethod();
         }
      }
      return null;
   }

   public IMethodDefinition getEnclosingIMethod() {
      for (ISemanticNode pnode = parentNode; pnode != null; pnode = pnode.getParentNode()) {
         // Needed for CFMethods who define ClassType objects through the signature parser - those types are children of the CFMethod.  Of course
         // this also works like getEnclosingMethod for MethodDefinition.
         if (pnode instanceof IMethodDefinition)
            return (IMethodDefinition) pnode;
         if (pnode instanceof LambdaExpression) {
            return ((LambdaExpression) pnode).getLambdaMethod();
         }
      }
      return null;
   }

   /** Used for errors involving type resolution.  These errors can be disabled during certain operations like transform */
   public boolean displayTypeError(String...args) {
      JavaModel model = getJavaModel();
      if (model != null && !model.disableTypeErrors) {
         model.reportError(getMessageString(args), this);
         return true;
      }
      return false;
   }

   final static int FILE_MARGIN = 70;
   protected String getMessageString(String...args) {
      StringBuilder sb = new StringBuilder();
      sb.append(toFileString());
      int sz = sb.length();
      while (sz++ < FILE_MARGIN) {
          sb.append(" ");
      }
      sb.append(" - ");
      for (String arg:args)
         sb.append(arg);
      sb.append(toDefinitionString(0, false, true));
      return sb.toString();
   }

   public void runtimeError(Class<? extends RuntimeException> excClass, String...args) {
      JavaModel model = getJavaModel();
      if (model != null) {
         throw (RuntimeException) PTypeUtil.createInstance(excClass, null, getMessageString(args));
      }
   }

   /** Used for errors that should always be displayed */
   public void displayError(String...args) {
      JavaModel model = getJavaModel();
      if (model != null) {
         model.reportError(getMessageString(args), this);
      }
   }

   public void displayWarning(String...args) {
      JavaModel model = getJavaModel();
      if (model != null) {
         model.reportWarning(getMessageString(args), this);
      }
   }

   public void displayVerboseWarning(String...args) {
      JavaModel model = getJavaModel();
      if (model != null) {
         if (model.layeredSystem.options.verbose)
            System.out.println(getMessageString(args));
      }
   }

   public void displayFormattedError(String err) {
      JavaModel model = getJavaModel();
      if (model != null) {
         model.reportError(err, this);
      }
   }

   public String toFileString() {
      String file;
      JavaModel model = getJavaModel();
      if (model == null || model.getSrcFile() == null)
         file = "<no file>";
      else
         file = model.getSrcFile().toShortString();
      return "File: " + file + toLocationString(false, false);
   }

   public boolean isStatic() {
      for (ISemanticNode pnode = parentNode; pnode != null; pnode = pnode.getParentNode())
         if (pnode instanceof Statement)
            return ((Statement) pnode).isStatic();
      // Should only be calling this from a context where there is guaranteed to be at least one statement between
      // the caller and the root, unless this is part of a fragment where the context is not known. 
      return false;
   }

   public boolean canInsertStatementBefore(Expression fromExpr) {
      for (ISemanticNode pnode = parentNode; pnode != null; pnode = pnode.getParentNode())
         if (pnode instanceof JavaSemanticNode)
            return ((JavaSemanticNode) pnode).canInsertStatementBefore(fromExpr);
      return false;
   }

   /**
    * Used to create a list of suggestions to complete this node.  Returns the character offset into the parse-tree (or command string if there's no parse tree) where the completion
    * should start.
    * Parameters:
    * prefix is the starting sequence of chars in the identifier to complete.  All candidates returned will start with the prefix if it's provided.
    * currentType provides a context for the current type if the node is not embedded in a model - i.e. an identifier expression on its own but known to live in a specific type
    * If ctx is not null, it's an executation context we can use to evaluate the "root value" - i.e. in a.b the value of the variable 'a' is evaluated and the returned instance
    * can be used to suggest candidates.  This is useful in live-programming situations.
    * The command String parameter is used to determine the offset returned for where the completion starts for the case where the parseNode tree is not available.
    */

   public int suggestCompletions(String prefix, Object currentType, ExecutionContext ctx, String command, int cursor, Set<String> candidates, Object continuation) {
      return -1;
   }

   public String addNodeCompletions(JavaModel origModel, JavaSemanticNode origNode, String matchPrefix, int offset, String dummyIdentifier, Set<String> candidates) {
      return null;
   }

   /**
    * When Parser.enablePartialValues is set, and we do not complete parsing the input we'll have a set of
    * errors which represent model fragments.  In some cases, it would be nice to put the model together as
    * well as possible for code hinting.  This method can append fragments onto it... if so return true.
    */
   public boolean applyPartialValue(Object value) {
      return false;
   }

   /** overridden by nodes to visit values in the reference chain */
   public void visitTypeReferences(CycleInfo cycle, TypeContext ctx) {
   }

   /** Called to kick off the cycle detection for this node */
   public void detectCycles() {
      CycleInfo info = new CycleInfo(this);
      info.visit(new CycleInfo.ThisContext(getEnclosingType(), null), this, null, true);
      /*
      TypeDeclaration enclosing = getEnclosingType();
      if (enclosing.isReferenceValueObject()) {
         if (info.get(enclosing) != null)
            error("Cycle in object reference - illegal for non @Component types: ");
      }
      */
      String res = info.toString();
      if (res != null)
         displayFormattedError(res);
   }

   public boolean isReferenceValueObject() {
      return true;
   }


   public boolean isDynamicType() {
      ITypeDeclaration t = getEnclosingIType();
      if (t == null)
         return false;
      return t.isDynamicType();
   }

   public boolean needsDataBinding() {
      return false;
   }

   /** A pass over the semantic node tree to convert template statements into layer cake statements. */
   public int transformTemplate(int ix, boolean statefulContext) {
      return ix;
   }

   public Element getEnclosingTag() {
      Object parent = parentNode;
      while (parent != null) {
         if (parent instanceof Element)
            return (Element) parent;
         else if (parent instanceof ISemanticNode)
            parent = ((ISemanticNode) parent).getParentNode();
      }
      return null;
   }

   public Element getRootTag() {
      Element root = this instanceof Element ? (Element) this : null;
      Object parent = parentNode;
      while (parent != null) {
         if (parent instanceof Element)
            root = (Element) parent;

         if (parent instanceof ISemanticNode)
            parent = ((ISemanticNode) parent).getParentNode();
         else
            break;
      }
      return root;
   }

   public Template getEnclosingTemplate() {
      Object parent = parentNode;
      while (parent != null) {
         if (parent instanceof Template)
            return (Template) parent;
         else if (parent instanceof ISemanticNode)
            parent = ((ISemanticNode) parent).getParentNode();
         else
            break;
      }
      return null;

   }

   /** Elements of Template objects (tags) can either run on the client or on the server or both based on this flag.  Right now, it's set at the Template level since those are the only types which support mixed client/server mode. */
   public int getExecMode() {
      Template templ = getEnclosingTemplate();
      if (templ == null)
        return Element.ExecServer | Element.ExecClient;
      return templ.getGenerateExecFlags();
   }

   public String toGenerateString() {
      throw new UnsupportedOperationException();
   }

   public JavaLanguage getJavaLanguage() {
      if (parseNode != null)
         return (JavaLanguage) parseNode.getParselet().getLanguage();

      ISemanticNode parent = parentNode;
      while (parent != null) {
         if (parent instanceof JavaSemanticNode)
            return ((JavaSemanticNode) parent).getJavaLanguage();
         parent = parent.getParentNode();
      }
      return null;
   }

   /** Return true for nodes like simple ClassType expressions "a.b.c" which can collapse into a simpler node type in the IDE. */
   public boolean isCollapsibleNode() {
      return false;
   }

   /**
    * Needs to be implemented for IDE support on nodes which can be referenced outside of the same file.  If the model
    * in which this references lives has been removed (or really replaced by another version of the same model), we need
    * to find the node in the other model that corresponds to this node and return it.
    */
   public JavaSemanticNode refreshNode() {
      JavaModel myModel = getJavaModel();
      if (myModel != null) {
         JavaModel newModel = (JavaModel) myModel.getLayeredSystem().getAnnotatedModel(myModel.getSrcFile());
         // Our file model is not the current one managed by the system so we need to find the corresponding node.
         if (newModel != myModel && newModel != null) {
            IParseNode origParseNode = getParseNode();
            if (origParseNode != null) {
               int startIx = origParseNode.getStartIndex();
               if (startIx != -1) {
                  IParseNode newParseNode = newModel.getParseNode();
                  IParseNode newNode = newParseNode == null ? null : newParseNode.findParseNode(startIx, origParseNode.getParselet());
                  if (newNode != null) {
                     Object semValue = newNode.getSemanticValue();
                     if (semValue != null && semValue.getClass() == getClass())
                        return (JavaSemanticNode) semValue;
                  }
               }
            }
         }
      }
      return this;
   }

   public String getDependencyDisabledText() {
      JavaModel model = getJavaModel();
      if (model != null && model.layer != null && model.layer.getBaseLayerDisabled()) {
         String disabledLayerName = model.layer.getDisabledLayerName();
         return "Layer: " + disabledLayerName + " is disabled";
      }
      return null;
   }
}
