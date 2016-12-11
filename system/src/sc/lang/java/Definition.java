/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.java;

import sc.lang.*;
import sc.lang.sc.IScopeProcessor;
import sc.lang.sc.ScopeModifier;
import sc.layer.Layer;
import sc.layer.LayeredSystem;
import sc.obj.ScopeDefinition;

import java.lang.annotation.ElementType;
import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;

public abstract class Definition extends JavaSemanticNode implements IDefinition {
   // String or Annotation
   public SemanticNodeList<Object> modifiers;

   // If this definition overrides another definition, this stores that other definition
   // We do the modify in one pass, then the transform in another.  During the modify, we reconcile who
   // is replacing whom.  This does not occur in a consistent order so we can't reliably propagate modifiers
   // during that step.  During the transform phase, we can then copy over any modifiers which we need.
   public transient Definition overrides;

   private transient Object defaultModifier;

   public void init() {
      if (initialized)
         return;
      super.init();

      /*
       * Retiring the 'init' function for the scope processor.  So far we have not needed it and implementing it means resolving
       * the annotation type names (by importing classes etc).  That requires us to 'complete the type info' which is not
       * easy during init.
       *
      LayeredSystem sys = null;
      if (modifiers != null) {
         for (int i = 0; i < modifiers.size(); i++) {
            Object mod = modifiers.get(i);
            if (mod instanceof Annotation) {
               if (sys == null) {
                  sys = getLayeredSystem();
                  if (sys == null)
                     return;
               }
               Annotation annot = (Annotation) mod;
               IAnnotationProcessor p = sys.annotationProcessors.get(annot.getFullTypeName());
               if (p != null) {
                  p.init(this, annot);
               }
            }
            // Technically this should be in a CCake specific package but it seems like overkill to try and hook
            // this in.
            else if (mod instanceof ScopeModifier) {
               if (sys == null) {
                  sys = getLayeredSystem();
                  if (sys == null)
                     return;
               }
               ScopeModifier scope = (ScopeModifier) mod;
               IScopeProcessor p = sys.getScopeProcessor(model.getLayer(), scope.scopeName);
               if (p != null) {
                  p.init(this);
               }
               else
                  error("No scope processor registered for scope:" + scope.scopeName + " Registered scopes are: " + sys.getScopeNames() + " for ");
            }
         }
      }
      */
   }

   public void start() {
      if (started)
         return;
      super.start();

      LayeredSystem sys = null;
      boolean hasDefault = false;
      if (modifiers != null) {
         for (int i = 0; i < modifiers.size(); i++) {
            Object mod = modifiers.get(i);
            if (mod instanceof Annotation) {
               if (sys == null) {
                  sys = getLayeredSystem();
                  if (sys == null)
                     return;
               }
               JavaModel model = getJavaModel();
               if (model == null)
                  return;
               Annotation annot = (Annotation) mod;
               IAnnotationProcessor p = sys.getAnnotationProcessor(model.getLayer(), annot.getFullTypeName());
               if (p != null) {
                  p.start(this, annot);
               }
            }
            else if (mod instanceof ScopeModifier) {
               if (sys == null) {
                  sys = getLayeredSystem();
                  if (sys == null)
                     return;
               }
               JavaModel model = getJavaModel();
               if (model == null)
                  return;
               ScopeModifier scope = (ScopeModifier) mod;
               IScopeProcessor p = sys.getScopeProcessor(model.getLayer(), scope.scopeName);
               if (p != null) {
                  p.start(this);
                  String requiredParentType;
                  if ((requiredParentType = p.getRequiredParentType()) != null) {
                     Object ourEncType = getEnclosingType();
                     Object reqParent = sys.getTypeDeclaration(requiredParentType, false, model.getLayer(), model.isLayerModel);
                     if (reqParent == null) {
                        displayError("No type for requiredParentType: ", requiredParentType, " as set on scope: ", scope.scopeName, " for: ");
                     }
                     else if (!(ModelUtil.isAssignableFrom(reqParent, ourEncType)))
                        displayError("Scope: ", scope.scopeName, " only valid on child objects of type: ", requiredParentType, " definition: ");
                  }
               }
            }
            else if (AccessLevel.getAccessLevel(mod.toString()) != null)
               hasDefault = true;
         }
      }
      if (!hasDefault) {
         defaultModifier = getDefaultModifier();
      }
   }

   /** Overridden  */
   protected void addInheritedAnnotationProcessor(IAnnotationProcessor process, String annotName) {
      System.err.println("*** No support for inherited annotations on: " + this.getClass() + ": " + this + " annotation: " + annotName);
   }

   protected void processModifiers(List<Object> modifiers) {
      LayeredSystem sys = null;
      if (modifiers != null) {
         for (int i = 0; i < modifiers.size(); i++) {
            Object mod = modifiers.get(i);
            if (mod instanceof Annotation) {
               if (sys == null) {
                  sys = getLayeredSystem();
                  if (sys == null)
                     return;
               }
               JavaModel model = getJavaModel();
               if (model == null)
                  return;
               Annotation annot = (Annotation) mod;
               String annotName = annot.getFullTypeName();
               IAnnotationProcessor p = sys.getAnnotationProcessor(model.getLayer(), annotName);
               if (p != null) {
                  if (!p.getSubTypesOnly())
                     p.process(this, annot);
                  if (p.getInherited())
                     addInheritedAnnotationProcessor(p, annotName);
               }
            }
            else if (mod instanceof ScopeModifier) {
               if (sys == null) {
                  sys = getLayeredSystem();
                  if (sys == null)
                     return;
               }
               JavaModel model = getJavaModel();
               if (model == null)
                  return;
               ScopeModifier scope = (ScopeModifier) mod;
               IScopeProcessor p = sys.getScopeProcessor(model.getLayer(), scope.scopeName);
               if (p != null)
                  p.process(this);
            }
         }
      }
   }

   public void process() {
      if (processed)
         return;
      super.process();

      processModifiers(modifiers);
   }

   public String getScopeName() {
      if (modifiers != null) {
         for (int i = 0; i < modifiers.size(); i++) {
            Object mod = modifiers.get(i);
            if (mod instanceof ScopeModifier)
               return ((ScopeModifier) mod).scopeName;
            // Do this at the same time so @Scope and scope<...> can override each other in a modify or extends
            else if (mod instanceof Annotation) {
               Annotation annotation = (Annotation) mod;
               String ftn;
               if (annotation.typeName.equals("sc.obj.Scope") || (ftn = annotation.getFullTypeName()) != null && ftn.equals("sc.obj.Scope")) {
                  return (String) annotation.getAnnotationValue("name");
               }
            }
         }
      }
      // Also check for the annotation - in case we've generated it
      // Doing this check here is no good cause it messes up the precedence. Need to do it with the modifiers test above.
      //return (String) ModelUtil.getAnnotationValue(this, "sc.obj.Scope", "name");
      return null;
   }

   public ScopeDefinition getScopeDefinition() {
      String scopeName = getScopeName();
      if (scopeName == null)
         return null;
      return ScopeDefinition.getScopeByName(scopeName);
   }

   public ScopeModifier getScope() {
      if (modifiers != null) {
         for (int i = 0; i < modifiers.size(); i++) {
            Object mod = modifiers.get(i);
            if (mod instanceof ScopeModifier)
               return ((ScopeModifier) mod);
         }
      }
      return null;
   }

   public IScopeProcessor getScopeProcessor() {
      LayeredSystem sys = getLayeredSystem();
      if (sys == null)
         return null;
      JavaModel model = getJavaModel();
      if (model == null)
         return null;

      String scopeName = getScopeName();
      return sys.getScopeProcessor(model.getLayer(), scopeName);
   }

   public IDefinitionProcessor[] getDefinitionProcessors() {
      ArrayList<IDefinitionProcessor> defProcs = null;
      if (modifiers != null) {
         LayeredSystem sys = null;
         for (int i = 0; i < modifiers.size(); i++) {
            Object mod = modifiers.get(i);
            if (mod instanceof ScopeModifier) {
               if (sys == null)
                  sys = getLayeredSystem();
               if (sys == null) return null;
               JavaModel model = getJavaModel();
               if (model == null)
                  return null;
               String scopeName = ((ScopeModifier)mod).scopeName;
               IScopeProcessor sproc = sys.getScopeProcessor(model.getLayer(), scopeName);
               if (sproc != null) {
                  if (defProcs == null)
                     defProcs = new ArrayList<IDefinitionProcessor>(1);
                  defProcs.add(sproc);
               }
               /* TODO: should this be a warning or error?  global is not mapped currently since it does not need any processing.
               else if (!scopeName.equals("global"))
                  System.out.println("*** Missing processor for scope: " + scopeName);
               */
            }
            else if (mod instanceof Annotation) {
               Annotation annot = (Annotation) mod;
               String annotTypeName = annot.getFullTypeName();
               JavaModel model = getJavaModel();
               if (model == null || model.layeredSystem == null)
                  return null;
               IAnnotationProcessor aproc = model.layeredSystem.getAnnotationProcessor(model.getLayer(), annotTypeName);
               if (aproc != null) {
                  if (defProcs == null)
                     defProcs = new ArrayList<IDefinitionProcessor>(1);
                  defProcs.add(aproc);
               }
            }
         }
      }
      return defProcs == null ? null : defProcs.toArray(new IDefinitionProcessor[defProcs.size()]);
   }

   public boolean isDefaultSync() {
      return false;
   }

   public boolean hasModifier(String modifier) {
      if (defaultModifier != null && defaultModifier.equals(modifier))
         return true;
      if (modifiers == null)
         return false;
      for (Object o:modifiers)
         if (o.equals(modifier))
            return true;
      return false;
   }

   public void addModifier(Object modifier) {
      if (modifiers == null)
         setProperty("modifiers", new SemanticNodeList());

      if (modifier instanceof Annotation)
         modifiers.add(0, modifier);
      else if (modifier instanceof ScopeModifier) {
         for (int i = 0; i < modifiers.size(); i++) {
            if (modifiers.get(i) instanceof ScopeModifier) {
               modifiers.set(i, modifier);
               return;
            }
         }
         modifiers.add(modifier);
      }
      else {
         modifiers.add(modifier);
      }
   }

   public AccessLevel getInternalAccessLevel() {
      if (modifiers != null) {
         for (Object o:modifiers) {
            if (o instanceof Annotation)
               continue;
            AccessLevel al = AccessLevel.getAccessLevel(o.toString());
            if (al != null)
               return al;
         }
      }
      return null;
   }

   public Object[] getExtraModifiers() {
      return null;
   }

   public AccessLevel getAccessLevel(boolean explicitOnly) {
      AccessLevel lev = getInternalAccessLevel();
      if (lev != null || explicitOnly)
         return lev;
      Object defMod = getDefaultModifier();
      if (defMod != null && (lev = AccessLevel.getAccessLevel(defMod.toString())) != null)
         return lev;
      TypeDeclaration type = getEnclosingType();
      // Members that are in interfaces in Java need to return 'public' if nothing is set
      if (type != null) {
         lev = type.getDefaultAccessLevel();
         if (lev != null)
            return lev;
      }
      return null;
   }

   public List<Object> getComputedModifiers() {
      List<Object> modifiersToUse;
      if (!transformed && defaultModifier != null && getInternalAccessLevel() == null) {
         modifiersToUse = new ArrayList<Object>();
         modifiersToUse.add(defaultModifier);
         if (modifiers != null)
            modifiersToUse.addAll(modifiers);
      }
      else
         modifiersToUse = modifiers;

      Object[] extraModifiers = getExtraModifiers();
      if (extraModifiers != null) {
         modifiersToUse = modifiersToUse == null ? new ArrayList<Object>() : new ArrayList<Object>(modifiersToUse);
         for (Object o:extraModifiers) {
            if (!modifiersToUse.contains(o))
               modifiersToUse.add(o);
         }
      }
      return modifiersToUse;
   }

   // Includes a trailing " " to make it easier to insert without adding an extra space 
   public String modifiersToString(boolean includeAnnotations, boolean includeAccess, boolean includeFinal, boolean includeScopes, boolean absolute, MemberType type) {
      List<Object> modifiersToUse = getComputedModifiers();

      if (modifiersToUse == null || modifiersToUse.size() == 0)
         return "";

      StringBuilder sb = new StringBuilder();
      for (Object m:modifiersToUse) {
         if (!(m instanceof Annotation) || (includeAnnotations &&
              (type == null || includeForType((Annotation) m, type)))) {

            if (!(m instanceof Annotation)) {
               if (m instanceof ScopeModifier) {
                  if (!includeScopes)
                     continue;
                  sb.append(((ScopeModifier) m).toLanguageString(SCLanguage.getSCLanguage().scopeModifier));
               }
               if (!includeAccess && AccessLevel.getAccessLevel(m.toString()) != null)
                  continue;

               if (!includeFinal && m.toString().equals("final"))
                  continue;

               sb.append(m);
            }
            else {
               if (absolute)
                  sb.append(((Annotation) m).toAbsoluteString());
               else
                  sb.append(((Annotation) m).toLanguageString(JavaLanguage.getJavaLanguage().annotation));
            }
            sb.append(" ");
         }
      }
      return sb.toString();
   }

   private boolean includeForType(Annotation annot, MemberType type) {
      JavaModel model = getJavaModel();
      Layer layer = model == null ? null : model.getLayer();
      IAnnotationProcessor p = getLayeredSystem().getAnnotationProcessor(layer, annot.getFullTypeName());
      if (p == null) {
         // Right now for the Bindable annotation, we'll move it from the field to the Get/Set during conversion since
         // those become the public contract for the property.
         if (annot.getTypeName().equals("Bindable") || annot.getTypeName().equals("sc.bind.Bindable")) {
           if (type == MemberType.Field)
              return false;
            else if (type == MemberType.SetMethod || type == MemberType.GetMethod)
              return true;
         }
         EnumSet<ElementType> targets = ModelUtil.getAnnotationTargets(annot);
         if (targets != null) {
            switch (type) {
               case GetMethod:
               case SetMethod:
               case SetIndexed:
                  return targets.contains(ElementType.METHOD);
               case Field:
                  return targets.contains(ElementType.FIELD);
            }
         }
         // TODO: is this a valid default?
         return type == MemberType.Field;
      }
      else {
         switch (type) {
            case GetMethod:
               return p.setOnGetMethod();
            case SetMethod:
               return p.setOnSetMethod();
            case Field:
               return p.setOnField();
            case SetIndexed:
               return false; // TODO - do we need an option here?
            default:
               throw new UnsupportedOperationException();
         }
      }
   }

   // TODO: should be using the full type name here and in getAnnotation
   // otherwise, two annotations with the same name but in different packages will get merged.
   public Object getAnnotation(String annotationName) {
      if (modifiers == null)
         return null;
      for (Object modifier:modifiers) {
         if (modifier instanceof Annotation) {
            Annotation annotation = (Annotation) modifier;
            String ftn;
            if (annotation.typeName.equals(annotationName) || (ftn = annotation.getFullTypeName()) != null && ftn.equals(annotationName))
               return annotation;
         }
      }
      return null;
   }

   public void removeAnnotation(String annotationName) {
      if (modifiers == null)
         return;
      for (int i = 0; i < modifiers.size(); i++) {
         Object modifier = modifiers.get(i);
         if (modifier instanceof Annotation) {
            Annotation annotation = (Annotation) modifier;
            if (annotation.typeName.equals(annotationName)) {
               modifiers.remove(i);
               break;
            }
         }
      }
   }

   public boolean mergeModifiers(Object overridden, boolean replace, boolean mergeDefaultModifiers) {
      boolean any = false;
      // Access level: public, protected, etc.
      AccessLevel myAL;
      if ((myAL = getInternalAccessLevel()) == null || replace) {
         AccessLevel otherAL = ModelUtil.getAccessLevel(overridden, !mergeDefaultModifiers); // only get explicit modifiers if not merging the default
         if (otherAL != null) {
            if (replace && myAL != null) {
               for (int i = 0; i < modifiers.size(); i++) {
                  Object o = modifiers.get(i);
                  if (!(o instanceof Annotation)) {
                     if (o.toString().equals(myAL.levelName)) {
                        modifiers.remove(i);
                        break;
                     }
                  }
               }
            }
            addModifier(otherAL.levelName);
            any = true;
         }
      }

      if (overridden instanceof VariableDefinition)
         overridden = ((VariableDefinition) overridden).getDefinition();

      // Now merge the modifiers
      if (overridden instanceof Definition) {
         Definition overrideDef = (Definition) overridden;
         if (overrideDef.modifiers != null) {
            for (Object modifier:overrideDef.modifiers) {
               if (modifier instanceof Annotation) {
                  Annotation overriddenAnnotation = (Annotation) modifier;
                  Object thisAnnotation = getAnnotation(overriddenAnnotation.typeName);
                  if (thisAnnotation == null) {
                     any = true;
                     if (modifiers == null) {
                        SemanticNodeList newMods = new SemanticNodeList();
                        newMods.add(overriddenAnnotation);
                        setProperty("modifiers", newMods);
                     }
                     else
                        addModifier(overriddenAnnotation);
                  }
                  else {
                     // I don't think we'll get to this case with a compiled annotation.  The only case I know of when this method returns compiled annotations is for Modify declarations on compiled types - for an annotation layer.  Those we do not transform.
                     if (!(thisAnnotation instanceof Annotation))
                        System.err.println("*** Unable to transform compiled annotation: " + thisAnnotation);
                     else if (((Annotation) thisAnnotation).mergeAnnotation(overriddenAnnotation, replace))
                        any = true;
                  }
               }
               else if (modifier instanceof ScopeModifier) {
                  addModifier(((ScopeModifier)modifier).deepCopy(ISemanticNode.CopyNormal, null));
               }
            }
         }
      }
      else if (overridden instanceof AnnotatedElement) {
         AnnotatedElement overrideElem = (AnnotatedElement) overridden;
         java.lang.annotation.Annotation[] annotations = overrideElem.getAnnotations();
         if (annotations != null) {
            for (java.lang.annotation.Annotation annot:annotations) {
               // Can this be a compiled annotation?  That should only happen if there's a modify on a compiled type I think and don't think we transform those as above.
               Annotation thisAnnotation = (Annotation) getAnnotation(annot.getClass().getName());
               Annotation overrideAnnotation = Annotation.createFromElement(annot);
               if (thisAnnotation == null) {
                  any = true;
                  if (modifiers == null) {
                     SemanticNodeList newMods = new SemanticNodeList();
                     newMods.add(overrideAnnotation);
                     setProperty("modifiers", newMods);
                  }
                  else
                     addModifier(overrideAnnotation);
               }
               else {
                  if (thisAnnotation.mergeAnnotation(overrideAnnotation, replace))
                     any = true;
               }

            }

         }
      }

      // Now merge the final attribute
      if (!hasModifier("final") && ModelUtil.hasModifier(overridden,"final")) {
         addModifier("final");
         any = true;
      }

      return any;
   }

   public boolean needsTransform() {
      boolean val = overrides != null || super.needsTransform() || defaultModifier != null;
      transformAnnotationProcessors();
      return val;
   }

   private void transformAnnotationProcessors() {
      LayeredSystem sys = null;
      if (modifiers != null) {
         for (int i = 0; i < modifiers.size(); i++) {
            Object mod = modifiers.get(i);
            if (mod instanceof Annotation) {
               if (sys == null)
                  sys = getLayeredSystem();
               JavaModel model = getJavaModel();
               if (model == null)
                  return;
               Annotation annot = (Annotation) mod;
               IAnnotationProcessor p = sys.getAnnotationProcessor(model.getLayer(), annot.getFullTypeName());
               if (p != null) {
                  p.transform(this, annot, null);
               }
            }
            else if (mod instanceof ScopeModifier) {
               if (sys == null) {
                  sys = getLayeredSystem();
                  if (sys == null)
                     return;
               }
               JavaModel model = getJavaModel();
               if (model == null)
                  return;
               ScopeModifier scope = (ScopeModifier) mod;
               IScopeProcessor p = sys.getScopeProcessor(model.getLayer(), scope.scopeName);
               if (p != null) {
                  // Warning: this might add to the modifiers list above - for example, when transforming a scope we might add an annotation to mark the runtime class for that scope
                  p.transform(this, null);
               }
               i = modifiers.indexOf(mod);
               modifiers.remove(i);
               i--;
            }
         }
      }

   }

   private Object getDefaultModifier() {
      if (useDefaultModifier() && getInternalAccessLevel() == null) {
         JavaModel m = getJavaModel();
         String defaultModifier;
         if (m == null)
            return null;
         Layer l = m.getLayer();
         if (l != null && (defaultModifier = l.defaultModifier) != null)
            return defaultModifier;
      }
      return null;
   }

   public boolean transformDefaultModifier() {
      if (useDefaultModifier() && defaultModifier != null && getInternalAccessLevel() == null) {
         addModifier(defaultModifier);
         defaultModifier = null;
         return true;
      }
      return false;
   }

   /**
    * All definitions share the ability to inherit their definitions.  They must set the "overrides" property
    * when they figure out if they are replacing another definition.  
    */
   public boolean transform(ILanguageModel.RuntimeType runtime) {
      boolean any = false;

      transformAnnotationProcessors();

      if (overrides != null) {
         any = mergeModifiers(overrides, false, true);
      }
      if (transformDefaultModifier())
         any = true;
      if (super.transform(runtime))
         any = true;

      return any;
   }

   public boolean useDefaultModifier() {
      return false;
   }


   public Definition deepCopy(int options, IdentityHashMap<Object,Object> oldNewMap) {
      Definition res = (Definition) super.deepCopy(options, oldNewMap);

      if ((options & CopyInitLevels) != 0) {
         res.overrides = overrides;
         res.defaultModifier = defaultModifier;
      }
      return res;
   }
}
