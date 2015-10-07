/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * CompilerSettings is placed on classes in sc layers to affect how they are compiled.  Some of these settings are inherited
 * or set on annotation layers so you can control the settings for an entire class hierarchy even when you do not have the source code for the object and without generating a new class for it.  The effects of the
 * CompilerSettings are broad.   You can specify code templates
 * for component definitions used in object tags or with the new expression.  You also can set a template for non-component definitions
 * used in object tags.  Specifiy framework classes to use for implementing dynamic features on sub-types.   Set the compiled-only feature,
 * disabling the dynamic type even when this type is in a dynamic layer.
 * <p>
 * CompilerSettings also lets you specify a set of packages to be placed into a jar file when this class is included in
 * a project.
 * </p>
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface CompilerSettings {
   /** Specifies the type name for a template file to use for obj defs */
   String objectTemplate() default "";      
   /** Specifies the type name for a template file to use for class components */
   String newTemplate() default "";         
  /** A template merged into any subclass of this class, useful for collecting children */
   String mixinTemplate() default "";       
   /** A template merged into any subclass of this class that includes static code.  This code gets put into a static section of the outer-most class because inner classes cannot have static code sections. */
   String staticMixinTemplate() default ""; 
   /** A template used to define the default output method's definition for a type defined in the TemplateLanguage */
   String outputMethodTemplate() default "";
   /** For List or other container types, the name of the type param for children */
   String childTypeParameter() default "";  
   /** Set to true if your class has to be initialized in a zero-arg constructor */
   boolean constructorInit() default false;  
   /** For Components where the template hides the constructor parameters */
   boolean automaticConstructor() default false; 
   /** For components that are created externally (e.g. android's activity), specifies the name of a method that will be called which is used for component initialization.
       The template should define a method called _init where all sc' init code goes.  A call to _init is inserted into the onInitMethod. */
   String onInitMethod() default "";        
   /** Setting this flag tells sc to propagate a constructor with the given set of parameter types in any classes unless a constructor is defined explicitly */
   String propagateConstructor() default ""; 
   /** If your class has a final start method but calls some other method, it can still be a component.  Set this property to the name of a method called by start and that method is overridden instead of start. */
   String overrideStartName() default "";    
   /** Name of a jar file relative to the build directory */
   String jarFileName() default "";          
   /** Name of a jar file relative to the build directory for packaging the generated Java source */
   String srcJarFileName() default "";       
   /** List of package names to be put into the jar file.  Defaults to all packages in the buildDir */
   String[] jarPackages() default {};
   /** If jarFileName is set, should we also include dependent classes in the jar file (excluding system classes) */
   boolean includeDepsInJar() default true;
   /** Class implementing IDynChildManager, used to implement obj children on dynamic types */
   String dynChildManager() default "";      
   /** Class implementing IDynObjManager - used to define how objects are constructed */
   String dynObjManager() default "";      
   // TODO: rename these two?
   /** Set to true for classes that even when dynamic need a specific .class generated for them */
   boolean needsCompiledClass() default false; 
   /** Set to true for classes that must be compiled classes because their implementation won't allow them to be implemented using the SC dynamic stub paradigm. (e.g. a class used by Hibernate or called from code that's no managed as source within StrataCode).  */
   boolean compiledOnly() default false;     
   /** If this type does not support liveDynamicTypes, you can turn this off via this flag */
   boolean liveDynamicTypes() default true; 
   /** If false, compile in extra type info to avoid need for reflection */
   boolean useRuntimeReflection() default true; 
   /** If true, use a separate class to hold reflective code when useRuntimeReflection = false (i.e. if you are replacing a JDK class whose signature can't be modified or do not have source to the class you are using in data binding or reflection, and so can't generate a new version of it) */
   boolean useExternalDynType() default false; 
   /** If true, altInit, altStart, etc. methods are used and the interface AltComponent is implemented */
   boolean useAltComponent() default false;      
   /** If true, this type's static variables are initialized at process startup. */
   boolean initOnStartup() default false;      
   /** If true, the object or class is instantiated when the process starts */
   boolean createOnStartup() default false;     
   /** Controls the order in which and initOnStartup and createOnStartup types are initialized or started.  Higher priority types are initialized or started first. */
   int startPriority() default 0;              
}
