/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.template;

import sc.layer.Layer;
import sc.layer.SrcEntry;

import java.util.List;

/**
 * Files processed by the layered system can return an implementation of this interface to get automatic
 * dependency management.
 */
public interface ITemplateProcessor {

   /** The files which this result is dependent upon - i.e. any imports or external references */
   List<SrcEntry> getDependentFiles();

   /** True if this result has errors - in that case, it will always be processed next compile */
   boolean hasErrors();

   /** True if this result needs to be compiled using javac */
   boolean needsCompile();

   /** Returns the set of result processed files for dependency purposes. */
   List<SrcEntry> getProcessedFiles(Layer buildLayer, String buildDir, boolean generate);

   /** Called to implement any building after the compile has completed and the classes required are available (like generating the initial .html file an .schtml template) */
   void postBuild(Layer buildLayer, String buildDir);

   /** Returns the name of the generated source file for overriding purposes.  This is the name of the .java file if a generated java file is used. */
   String getProcessedFileName();

   /** Returns the name of the generated file for the post build process.  This is the .html file which may omit the layer's package prefix for the schtml templates. */
   String getPostBuildFileName();

   String getDefaultExtendsType();

   boolean getIsDefaultObjectType();

   String getTypeGroupName();

   /** Return true if the template should evaluate itself during the toString operator.  Useful for including templates inside of each other using a simple syntax: <%= templateName %> */
   boolean evalToString();

   boolean getDefaultModify();

   /** If this is an HTML file with a single tag, should that tag's type be used as the type for the template or considered a single child tag of a page type */
   boolean getCompressSingleElementTemplates();

   boolean needsProcessing();

   boolean needsPostBuild();

   ITemplateProcessor deepCopy(Template templ);

   /** If you need to escape the body content in your template language, for example to escape HTML this provides the name of the escape method */
   String escapeBodyMethod();

   /** Returns the suffix that's a result of this process (or null) if there isn't one. */
   String getResultSuffix();
}
