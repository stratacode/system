/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import sc.dyn.DynUtil;
import sc.type.CTypeUtil;

import java.util.ArrayList;
import java.util.List;

/** 
 * Frameworks that need to implement a new scope - i.e. a new type of lifecycle extend this
 * class and ScopeContext.  There is one instance of this class for every scope implementation.
 * It holds metadata that help us manage objects of this scope. 
 */
@sc.js.JSSettings(jsModuleFile="js/scgen.js", prefixAlias="sc_")
public abstract class ScopeDefinition {
   public static ArrayList<ScopeDefinition> scopes = new ArrayList<ScopeDefinition>(4);

   public ArrayList<ScopeDefinition> parentScopes;
   public int scopeId;
   public String name;
   public List<String> aliases;

   /** High-level messages about object-level events */
   public static boolean verbose;

   /** Detailed messages about property-level events */
   public static boolean trace;

   public ScopeDefinition(int scopeId) {
      this.scopeId = scopeId;
      while (scopes.size() <= scopeId)
         ScopeDefinition.scopes.add(null);
      if (ScopeDefinition.scopes.set(scopeId, this) != null)
         System.err.println("*** Conflicting scopes for id: " + scopeId);
   }

   public ScopeDefinition() {
      this(Math.max(1, scopes.size()));
   }

   /**
    * Use this base class method to retrieve a scope context from temporary scope context state that's pushed
    * onto the stack (i.e. the CurrentScopeContext).   If this method returns null, each scope definition subclass
    * should use it's normal mechanism for looking up the current scope context.
    */
   public ScopeContext getScopeContext(boolean create) {
      CurrentScopeContext envCtx = CurrentScopeContext.getEnvScopeContextState();
      if (envCtx != null)
         return envCtx.getScopeContext(scopeId);
      return null;
   }

   public static ScopeContext getScope(int scopeId) {
      ScopeDefinition scopeDef = scopes.get(scopeId);
      return scopeDef.getScopeContext(true);
   }

   public void addParentScope(ScopeDefinition parent) {
      if (parentScopes == null)
         parentScopes = new ArrayList<ScopeDefinition>();
      parentScopes.add(parent);
   }

   public ArrayList<ScopeDefinition> getParentScopes() {
      return parentScopes;
   }

   public static ScopeDefinition getScopeDefinition(int scopeId) {
      return scopes.get(scopeId);
   }

   public boolean includesScope(ScopeDefinition other) {
      if (parentScopes == null)
         return false;
      for (ScopeDefinition parentScope:parentScopes) {
         if (parentScope == other || this == other)
            return true;
         if (parentScope != null)
            return parentScope.includesScope(other);
      }
      return false;
   }

   public static List<ScopeDefinition> getActiveScopes() {
      ArrayList<ScopeDefinition> res = null;
      int i = 0;
      // Optimize for the common case which all scopes are active.  just return the scopes list.
      for (ScopeDefinition scopeDef:scopes) {
         if (scopeDef.getScopeContext(false) != null) {
            if (res != null) {
               res.add(scopeDef);
            }
         }
         else if (res == null) {
            res = new ArrayList<ScopeDefinition>();
            for (int c = 0; c < i; c++) {
               res.add(scopes.get(c));
            }
         }
         i++;
      }
      if (res == null)
         return scopes;
      return res;
   }

   public static Object lookupName(String typeName) {
      // Optimize for the common case which all scopes are active.  just return the scopes list.
      for (ScopeDefinition scopeDef:scopes) {
         ScopeContext ctx = scopeDef.getScopeContext(false);
         if (ctx != null) {
            Object res = ctx.getValue(typeName);
            if (res != null)
               return res;
         }
      }
      return null;
   }

   public static Object resolveName(String typeName, boolean create) {
      String rootName = typeName;
      String childPath = "";
      Object res = lookupName(typeName);
      if (res != null)
         return res;
      do {
         String parentName = CTypeUtil.getPackageName(rootName);
         childPath = CTypeUtil.prefixPath(CTypeUtil.getClassName(rootName), childPath);
         if (parentName != null) {
            Object parentObj = resolveName(parentName, create);
            if (parentObj != null) {
               String nextPath = childPath;
               Object value = parentObj;
               do {
                  String propName = CTypeUtil.getHeadType(nextPath);
                  nextPath = CTypeUtil.getTailType(nextPath);
                  if (propName == null) {
                     propName = nextPath;
                     nextPath = null;
                  }
                  boolean enumFound = false;
                  boolean isEnum = false;
                  if (DynUtil.isType(value) && DynUtil.isAssignableFrom(java.lang.Enum.class, value)) {
                     isEnum = true;
                     if (value instanceof Class) {
                        Object[] enums = ((Class) value).getEnumConstants();
                        for (Object theEnum:enums) {
                           String enumName = DynUtil.getEnumName(theEnum);
                           if (enumName.equals(propName)) {
                              value = theEnum;
                              enumFound = true;
                              break;
                           }
                        }
                     }
                     else {
                        Object enumRes = DynUtil.getEnumConstant(value, propName);
                        if (enumRes != null) {
                           value = enumRes;
                           enumFound = true;
                           break;
                        }
                     }
                  }
                  if (!enumFound) {
                     if (!isEnum) {
                        // TODO: the precedence here seems off.  We should be testing for an inner type, enum etc. before
                        // we look for a lower case property that does not exist.
                        propName = CTypeUtil.decapitalizePropertyName(propName);
                        try {
                           value = DynUtil.getPropertyValue(value, propName);
                        }
                        catch (IllegalArgumentException exc) {
                           // This can happen since there may not be a property of that name.  Just return null.  Maybe use a hasProperty method to avoid the exception here?
                           value = null;
                        }
                     }
                     else {
                        value = null;
                     }
                  }
                  if (value == null)
                     break;
               }
               while (nextPath != null);
               if (value != null)
                  return value;
            }
         }
         rootName = parentName;
      } while (rootName != null);

      return DynUtil.resolveName(typeName, create);
   }

   public static ScopeDefinition getScopeByName(String scopeName) {
      for (ScopeDefinition scope:scopes)
         if (DynUtil.equalObjects(scope.name, scopeName) || (scope.matchesScope(scopeName)))
            return scope;
      return null;
   }

   public boolean matchesScope(String scopeName) {
      if (aliases == null)
         return false;
      for (String alias:aliases) {
         if (alias.equals(scopeName))
            return true;
      }
      return false;
   }

   public void registerInstance(String typeName, Object inst) {
      ScopeContext ctx = getScopeContext(true);
      ctx.setValue(typeName, inst);
   }

   public String toString() {
      return "scope<" + (name == null ? "global" : name) + ">";
   }

   public boolean isGlobal() {
      return this.name == null;
   }

   /** Initialize the scopes that are always available */
   public static void initScopes() {
      GlobalScopeDefinition.getGlobalScopeDefinition();
      AppGlobalScopeDefinition.getAppGlobalScopeDefinition();
   }
}
