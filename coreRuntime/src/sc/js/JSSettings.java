/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.js;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@JSRetention(RetentionPolicy.SOURCE)
public @interface JSSettings {
   /** Specifies alternate javascript files to include in place of generating code from this class.  For correctness, this script would have to follow the conventions used by the framework for JS code generation */
   String jsLibFiles() default "";
   /** Specify a js file to store the generated JS files in.  If you use this setting, you must ensure there's a linear dependency order for all JS modules (including the default).  In other words, if JS types in this file extend those in another module file, types in that file can't extend types in this one.*/
   String jsModuleFile() default "";
   /** Specify a pattern to apply to this type and all sub-types for the jsModuleFile. */
   String jsModulePattern() default "";
   /** Override the frameworks type template used to generate code for this type and subtypes */
   String typeTemplate() default "";
   /** Override the frameworks merge template used to generate an update for this type and subtypes */
   String mergeTemplate() default "";
   /** Registers an alias for this types package to use as the prefix instead of the default convention of pkgA_pkgB_ */
   String prefixAlias() default "";
   /**
    * Replace references to this type in the generated JS code with the JS type name specified (e.g. use jv_Object to
    * just eliminate this type from Javascript land).
    * Don't specify the java type name here - e.g. use String rather than java.lang.String
    */
   String replaceWith() default "";

   /** Like replaceWith but for types without the _c on the end */
   String replaceWithNative() default "";

   /** Comma separated list of jsLibFiles which this lib file uses in it's classes. This does not affect the sorting order of the lib files */
   String usesJSFiles() default "";
   /** Comma separated list of jsLibFiles which this lib file may extend one or more classes - using extends changes the sorting order of the JS files so the extended JS files come first */
   String extendsJSFiles() default "";

   /**
    * Specifies a comma separated list of type dependencies to add in addition to those discovered automatically by references in the code.
    * It useful for jsLibFiles where important dependencies are implemented in the native JS code, or when you might reference a base class
    * that can generate instances of a subclass.
    * An important note is that if a class uses jsLibFiles it stops looking for dependencies in that class (since many may not be resolveable)
    * but sometimes the Java and JS classes depend on the same class. Currently we require that you provide those overlapping classes manually but
    * it's possible we could improve this using that info.
    */
   String dependentTypes() default "";
   /** When jsModuleFile/Pattern is used and js.options.disableModules is enabled if this is true, this module is still used.  Set this to true for core modules which have dependencies to/from native js code.  Those that can be split out at compile time for smaller downloads. */
   boolean requiredModule() default false;
}
