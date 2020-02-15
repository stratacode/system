/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * CompilerSettings is placed on classes in sc layers to affect the generated code for this class when it's processed by scc.
 * Many of the attributes are inherited by a subclass.   So you can set it on a base-class to affect the whole type hierarchy - e.g. all
 * swing components.   You can set this annotation for a compiled class using an annotation layers - a layer which only annotates classes
 * for special processing during code-generation.  That way, you can control the code-generated for libraries even when you do not have the source code.
 * <p>
 * Via the objectTemplate and newTemplate attributes, you can specify code templates used to turn this class into a component or object instance.
 * <p>
 * Set the liveDynamicTypes attribute to enable or disable the liveDynamicTypes feature.
 * <p>
 * Set compiledOnly=true/false to disable the dynamic types feature entirely for this class.
 * <p>
 * Set needsCompiledClass to force a dynamic stub to be generated for this class if it's made dynamic.
 * <p>
 * CompilerSettings also lets you specify a set of packages to be placed into a jar file when this class is included in
 * a project.
 * </p>
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface CompilerSettings { // TODO: rename to GeneratorSettings?
   /** Specifies the type name for a template file to use for obj defs */
   String objectTemplate() default "";      
   /** Specifies the type name for a template file to use for class components */
   String newTemplate() default "";         
   /** A template merged into any subclass of this class, useful for collecting children */
   String mixinTemplate() default "";
   /** A template merged into any subclass of this class that includes code that changes the contract for the type - i.e. is applied during start. */
   String defineTypesMixinTemplate() default "";
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
   /**
    * Setting this for a class generates a constructor with the given set of parameter types in any sub-classes of the
    * given type unless that sub-class has a matching constructor defined explicitly.
    * When you set this on a class, it should define a matching constructor
    */
   String propagateConstructor() default "";
   /** Specify a list of strings where each string contains the comma separated list of parameter types for constructor signatures that should be propagated */
   // TODO: should we add support for propagating more than one constructor, either with propagateConstructors or with an @Propagate annotation on the method itself?
   //String[] propagateConstructors() default {};
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
   // TODO: Find better names for these two?  They are different features - needsCompiledClass forces a dynamic stub when the class is dynamic and compiledOnly disables dynamic mode entirely
   /** Set to true for classes that even when dynamic need a specific .class generated for them */
   boolean needsCompiledClass() default false; 
   /** Set to true for classes that must be compiled classes because their implementation won't allow them to be implemented using the SC dynamic stub paradigm. (e.g. a class used by Hibernate or called from code that's no managed as source within StrataCode).  */
   boolean compiledOnly() default false;     
   /** If this type does not support liveDynamicTypes, you can turn this off via this flag */
   boolean liveDynamicTypes() default true; 
   /** If false, compile in extra type info to avoid need for reflection */
   boolean useRuntimeReflection() default true;
   /** Set to true for types access with DynUtil.getPropertyNames(type) in Javascript.  The current generated JS does not otherwise let us find the properties of a type */
   boolean needsPropertyNames() default false;
   /** If true, use a separate class to hold reflective code when useRuntimeReflection = false (i.e. if you are replacing a JDK class whose signature can't be modified or do not have source to the class you are using in data binding or reflection, and so can't generate a new version of it) */
   boolean useExternalDynType() default false; 
   /**
    * We use generic names with the @Component/IComponent interface which sometimes conflicts with frameworks.
    * In that case, set this to true and we'll use the methods: altInit, altStart, etc. methods are used and the interface AltComponent is implemented
    */
   boolean useAltComponent() default false;      
   /** If true, this type's static variables are initialized at process startup. */
   boolean initOnStartup() default false;      
   /** If true, the object or class is instantiated when the process starts */
   boolean createOnStartup() default false;     
   /** Controls the order in which and initOnStartup and createOnStartup types are initialized or started.  Higher priority types are initialized or started first. */
   int startPriority() default 0;

   /** Set this to a comma separated list of properties that should be defined as part of the constructor of this type. Use for properties that are initializable before the instance is created (i.e. the initializer does not refer to this) */
   String constructorProperties() default "";

   /** Set to false on a type, or a base-type so that properties in the base-type are inherited by sub-classes in the editor's view of the type */
   boolean inheritProperties() default true;

   /** Set to false on a type to indicate it does not export it's properties to sub-types in the editor's view of the type */
   boolean exportProperties() default true;
}
