/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import sc.layer.*;
import sc.type.DynType;
import sc.type.IBeanMapper;
import sc.type.RTypeUtil;
import sc.util.FileUtil;
import sc.type.TypeUtil;
import sc.util.PerfMon;

import java.io.*;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** 
 * This is the abstract base class for all language grammars defined in the system (e.g. JavaLanguage, SCLanguage, HTMLLanguage, etc.  It implements some features shared by all languages.  For example,
 * each language an parse a text string and produce a language element - either an entire file 
 * (when you use the default "start parselet") or for a given chunk of code represented by a public
 * parselet (e.g. an initializer).  
 * <p>
 * In addition to parsing a language, you can also take an language model - called a SemanticNode in
 * the parselets framework - and re-generate the code corresponding to that model.  In the parselets
 * framework, this is implemented with the "generate" methods.
 * <p> 
 * Model generation lets you reconstruct the code for objects that are programmatically
 * constructed (e.g. by manually constructing instances for each language type, setting the properties, etc.) or parsed, or parsed and modified.  Each parsed set of SemanticNodes have a parallel parse
 * node tree hanging off of them.  As you change properties in a SemanticNode, by default the 
 * parselets framework will update or regenerate any affected code made by the change.  It can do 
 * this in two modes - either by invalidating the parse-nodes that are affected and later doing the
 * generation, or it can do the generation in real time as you make property changes to the 
 * SemanticNode objects.
 * <p>
 * For the most part, this generation comes for free when you write a grammar in Parselets that
 * builds a SemanticNode tree.  You do need to ensure you retain enough information in your 
 * SemanticNode trees so that parselets can undo what it parsed.  Spacing, comments, etc. are
 * generally not preserved in the semantic tree.  Instead, parselets use special parse nodes
 * which represent spacing.  Parse nodes support a "format" phase, that runs after generation.  This
 * phase can use the data in the semantic model to add back in the necessary spacing, newlines, etc.
 */
public abstract class Language implements IFileProcessor {
   // Suffix used to represent a file which is inherited from an extended layer
   public final static String INHERIT_SUFFIX = "inh";

   Parselet startParselet;

   public ClassLoader classLoader;

   String[] semanticValueClassPath;

   public boolean debug = false;

   /** The generation scheme works in two modes: when trackTranges=true, each property change automatically updates the generated result.  When it is false, we invalidate changed nodes and only revalidate them as needed.  tracking changes is better for debugging but a little slower */
   public boolean trackChanges = false;

   public boolean debugSuccessOnly = false;

   public Layer definedInLayer;    // If you add a language in a layer, set this to that layer so that previous layers
                                  // will not see the language definitions

   public boolean disableProcessing = false;
   public boolean exportProcessing = true;  // You can add a language private to a specific layer by setting this to false

   public BuildPhase buildPhase = BuildPhase.Process;

   public boolean prependLayerPackage = true;  // If true, prepend the layer's package name to the file.

   public String pathPrefix;    // A prefix prepended onto the generated source name

   public boolean useCommonBuildDir = false;

   public String defaultExtension = null;

   public Object parse(File file) {
      try {
         return parse(new FileReader(file));
      }
      catch (FileNotFoundException exc) {
         throw new IllegalArgumentException(exc.toString());
      }
   }

   public Object parse(String fileName, boolean enablePartialValues) {
      try {
         return parse(new FileReader(fileName), startParselet, enablePartialValues);
      }
      catch (FileNotFoundException exc) {
         throw new IllegalArgumentException(exc.toString());
      }
   }

   public Object parse(String fileName, Parselet parselet, boolean enablePartialValues) {
      try {
         return parse(new FileReader(fileName), parselet, enablePartialValues);
      }
      catch (FileNotFoundException exc) {
         throw new IllegalArgumentException(exc.toString());
      }
   }

   /** Parses the string using the given parselet but instead of creating a new instance, populates the instance specified - which must match the type used to produce the grammar */
   public Object parseIntoInstance(String string, Parselet start, Object populateInst) {
      return parse(new StringReader(string), start, false, false, populateInst, string.length());
   }

   public boolean matchString(String string, Parselet start) {
      Object res = parse(new StringReader(string), start, false, true, null, string.length());
      if (res instanceof ParseError || res == null)
         return false;
      return true;
   }

   public Object parseString(String string) {
      return parse(new StringReader(string), startParselet, false, false, null, string.length());
   }

   public Object parseString(String string, boolean enablePartialValues) {
      return parse(new StringReader(string), startParselet, enablePartialValues, false, null, string.length());
   }

   public Object parseString(String string, Parselet start, boolean enablePartialValues) {
      return parse(new StringReader(string), start, enablePartialValues, false, null, string.length());
   }

   public Object parseString(String string, Parselet start) {
      return parse(new StringReader(string), start, false, false, null, string.length());
   }

   public Object parse(Reader reader) {
      return parse(reader, startParselet);
   }

   /**
    * This is the most flexible form of the parse method.  It takes an input reader which supplies the input
    * stream.  It also takes a starting parselet... i.e. a node in the grammar which we should expect at the
    * first character of the input.  You can use the variant of parse which does not take a parselet to parse
    * the default grammar.
    * <p>
    * @param reader the input reader.
    * @param start the parselet which defines the grammar we expect in the reader
    * @param enablePartialValues - if true, return the longest matched grammar
    * @return  This method returns the parse tree generated  If your grammar defines a semantic value, you can retrieve
    * that using the @see ParseUtil.nodeToSemanticValue method passing in the return value if the parse method.
    */
   public Object parse(Reader reader, Parselet start, boolean enablePartialValues) {
      return parse(reader, start, enablePartialValues, false, null, Parser.DEFAULT_BUFFER_SIZE);
   }

   public Object parse(Reader reader, Parselet start, boolean enablePartialValues, boolean matchOnly, Object toPopulateInst, int bufSize) {
      if (!start.initialized) {
         ParseUtil.initAndStartComponent(start);
      }
      Parser p = new Parser(this, reader, bufSize);
      p.enablePartialValues = enablePartialValues;
      p.matchOnly = matchOnly;
      p.populateInst = toPopulateInst;
      Object parseTree = p.parseStart(start);
      if (parseTree instanceof IParseNode) {
         if (!p.atEOF())
            parseTree = p.wrapErrors();
      }
      return parseTree;
   }

   public Object parse(Reader reader, Parselet start) {
      return parse(reader, start, false);
   }

   @sc.obj.HTMLSettings(returnsHTML=true)
   public Object style(String input) {
      return styleInternal(null, null, true, false, input);
   }

   @sc.obj.HTMLSettings(returnsHTML=true)
   public Object styleLayer(String input) {
      return styleInternal(null, null, true, true, input);
   }

   @sc.obj.HTMLSettings(returnsHTML=true)
   public Object styleNoTypeErrors(String layerName, String fileName, String input) {
      return styleInternal(layerName, fileName, false, false, input);
   }

   @sc.obj.HTMLSettings(returnsHTML=true)
   public Object styleNoTypeErrors(String input) {
      return styleInternal(null, null, false, false, input);
   }

   /** Uses a normalized file name. */
   @sc.obj.HTMLSettings(returnsHTML=true)
   public Object style(String layerName, String fileName, String input) {
      return styleInternal(layerName, fileName, true, false, input);
   }

   /** Uses one file to parse, a separate to display so we can use the samples to build a new sample interactive sample */
   @sc.obj.HTMLSettings(returnsHTML=true)
   public Object styleDemoFile(String layerName, String dispLayerName, String fileName, String input) {
      return styleInternal(layerName, dispLayerName, fileName, false, false, input);
   }

   @sc.obj.HTMLSettings(returnsHTML=true)
   public Object styleFileNoTypeErrors(String layerName, String fileName, boolean isLayer) {
      return styleFile(layerName, fileName, false, isLayer);
   }

   /** Uses a normalized file name. */
   @sc.obj.HTMLSettings(returnsHTML=true)
   public Object styleFileNoTypeErrors(String layerName, String fileName) {
      return styleFile(layerName, fileName, false, false);
   }

   /** Uses a normalized file name. */
   @sc.obj.HTMLSettings(returnsHTML=true)
   public Object styleFileNoTypeErrors(String fileName) {
      return styleFile(null, fileName, true, false);
   }

   /** Uses a normalized file name. */
   @sc.obj.HTMLSettings(returnsHTML=true)
   public Object styleFile(String layerName, String fileName) {
      return styleFile(layerName, fileName, true, false);
   }

   /** Takes a normalized file name - / instead of the OS dependent path */
   @sc.obj.HTMLSettings(returnsHTML=true)
   public Object styleFile(String fileName) {
      return styleFile(null, fileName, true, false);
   }

   @sc.obj.HTMLSettings(returnsHTML=true)
   public Object styleFile(String layerName, String fileName, boolean displayError, boolean isLayer) {
      LayeredSystem sys = LayeredSystem.getCurrent().getMainLayeredSystem();
      fileName = FileUtil.unnormalize(fileName);
      String absFileName = fileName;
      if (layerName != null) {
         Layer layer = sys.getLayerByPath(layerName);
         if (layer == null) {
            layer = sys.getInactiveLayer(layerName);
            if (layer == null) {
               System.err.println("No layer named: " + layerName + " for styleFile method");
               return null;
            }
         }
         absFileName = FileUtil.concat(layer.getLayerPathName(), fileName);
      }
      File file = new File(absFileName);
      try {
         Object result = parse(file);
         if (result instanceof ParseError) {
            System.err.println("Error parsing string to be styled - no styling for this section: " + file + ": " + ((ParseError) result).errorStringWithLineNumbers(file));
            return "";
         }
         return ParseUtil.styleParseResult(layerName, layerName, fileName, displayError, isLayer, result);
      }
      catch (IllegalArgumentException exc) {
         System.err.println("Error reading file to be styled: " + absFileName + ": " + exc);
         return "Missing file: " + absFileName;
      }
   }

   private Object styleInternal(String layerName, String fileName, boolean displayTypeError, boolean isLayerModel, String input) {
      return styleInternal(layerName, layerName, fileName, displayTypeError, isLayerModel, input);
   }

   private Object styleInternal(String layerName, String dispLayerName, String fileName, boolean displayTypeError, boolean isLayerModel, String input) {
      if (fileName != null)
         fileName = FileUtil.unnormalize(fileName);
      Object result = parseString(input, true);
      if (result instanceof ParseError) {
         Object pv;
         // Allow partial values for styling purposes
         if ((pv = ((ParseError)result).partialValue) != null)
            return ParseUtil.styleParseResult(layerName, dispLayerName, fileName, displayTypeError, isLayerModel, pv);
         System.err.println("Error parsing string to be styled - no styling for this section: " + ((ParseError) result).errorStringWithLineNumbers(input));
         return input;
      }
      return ParseUtil.styleParseResult(layerName, dispLayerName, fileName, displayTypeError, isLayerModel, result);
   }

   /**
    * Generates a textual representation from the model that could be later parsed.  This takes the semantic value
    * from the result and generates a textual description from it.
    *
    *
    * @param model
    * @param finalGenerate
    * @return the ParseNode or an error if the textual representation cannot be generated.
    */
   public Object generate(Object model, boolean finalGenerate) {
      Object result = startParselet.generate(startParselet.getLanguage().newGenerateContext(startParselet, model), model);

      if (debug)
         //System.out.println("Generated: " + result + " from: " + model.toString());
         System.out.println("Generated: " + result);

      return result;
   }

   public void saveSemanticValue(Object model, File file) {
      PrintWriter pw = null;
      try {
         pw = new PrintWriter(file);
         Object o = generate(model, false);
         if (o instanceof GenerateError)
            throw new IllegalArgumentException("Can't generate file: " + file + " for model: " + o);
         pw.print(o.toString());
      }
      catch (IOException exc) {
         throw new IllegalArgumentException(exc);
      }
      finally {
         if (pw != null)
            pw.close();
      }
   }

   public void setStartParselet(Parselet sp) {
      startParselet = sp;
      startParselet.setLanguage(this);
   }

   public Parselet getStartParselet() {
      return startParselet;
   }

   /**
    * This is the package for this language's model classes.
    * 
    * @param pkg
    */
   public void setSemanticValueClassPath(String pkg) {
      semanticValueClassPath = pkg.split(":");
   }

   public void addToSemanticValueClassPath(String newPkg) {
      if (semanticValueClassPath == null) {
         semanticValueClassPath = new String[1];
         semanticValueClassPath[0] = newPkg;
      }
      else {
         String [] newList = new String[semanticValueClassPath.length + 1];
         System.arraycopy(semanticValueClassPath, 0, newList, 0, semanticValueClassPath.length);
         semanticValueClassPath = newList;
         semanticValueClassPath[semanticValueClassPath.length-1] = newPkg;
      }
   }

   public Class lookupModelClass(String className) {
      // Might be given an absolute path name so try that first
      if (className.contains(".")) {
         Class c = RTypeUtil.loadClass(classLoader, className, true);
         if (c != null)
            return c;
      }
      if (semanticValueClassPath == null)
         throw new IllegalArgumentException("Unable to lookup model class: " + className + " because the Language.semanticValueClassPath has not been set");
      for (int i = 0; i < semanticValueClassPath.length; i++) {
         Class c = RTypeUtil.loadClass(classLoader, semanticValueClassPath[i] + "." + className, true);
         if (c != null)
             return c;
      }
      // Also need to check the system class loader for top level classes (since this is now used for user defined classes via the Pattern language)
      Class c = RTypeUtil.loadClass(classLoader, className, true);
      if (c != null)
         return c;
      return null;
   }

   public String getJavaFileName(String fileName) {
      return null;
   }

   /* Maintains the set of languages currently registered in this class loader */

   public static Map<String,Language> languages = new HashMap<String,Language>();

   public String languageName;

   public static void registerLanguage(Language l, String extension) {
      initLanguage(l);
      if (l.defaultExtension == null)
         l.defaultExtension = extension;
      languages.put(extension, l);
   }

   public static void removeLanguage(String extension) {
      languages.remove(extension);
   }

   public static void initLanguage(Language l) {
      l.startParselet.initialize();
      l.startParselet.start();
      l.initialize();
   }

   public static Language getLanguageByExtension(String type) {
      return languages.get(type);
   }

   public static boolean isParseable(String fileName) {
      return getLanguageByExtension(FileUtil.getExtension(fileName)) != null;
   }

   public static Collection<String> getLanguageExtensions() {
      return languages.keySet();
   }


   public static File findSrcFile(String dir, String srcName) {
      for (String lang:languages.keySet()) {
         File f = new File(dir, srcName + "." + lang);
         if (f.exists() && FileUtil.caseMatches(f))
            return f;
      }
      return null;
   }

   /** Hook into the LayeredSystem's build process */
   public Object process(SrcEntry file, boolean enablePartialValues) {
      if (file.isZip()) {
         InputStream input = file.getInputStream();
         if (input == null) {
            throw new IllegalArgumentException("Unable to open zip file: " + file);
         }
         else
            return parse(new BufferedReader(new InputStreamReader(input)), startParselet, enablePartialValues);
      }
      else {
         try {
            PerfMon.start("parse", false);
            return parse(file.absFileName, startParselet, enablePartialValues);
         }
         finally {
            PerfMon.end("parse");
         }
      }
   }

   public boolean getNeedsCompile() {
      return true;
   }

   /** By default, this language produces objects that go into the type system */
   public boolean getProducesTypes() {
      return true;
   }

   public FileEnabledState enabledFor(Layer layer) {
      if (disableProcessing)
         return FileEnabledState.Disabled;
      
      if (definedInLayer == null)
         return FileEnabledState.Enabled;

      return (exportProcessing ? layer.getLayerPosition() >= definedInLayer.getLayerPosition() :
              layer.getLayerPosition() == definedInLayer.getLayerPosition()) ? FileEnabledState.Enabled : FileEnabledState.NotEnabled;
   }

   public int getLayerPosition() {
      return definedInLayer == null ? -1 : definedInLayer.getLayerPosition();
   }

   public void setDefinedInLayer(Layer l) {
      definedInLayer = l;
   }

   public Layer getDefinedInLayer() {
      return definedInLayer;
   }

   public BuildPhase getBuildPhase() {
      return buildPhase;
   }

   /** If true, returns the layer package */
   public boolean getPrependLayerPackage() {
      return prependLayerPackage;
   }

   public boolean getUseCommonBuildDir() {
      return useCommonBuildDir;
   }
   
   public String getOutputDir() {
      return null;
   }

   public String getOutputDirToUse(LayeredSystem sys, String buildSrcDir, String layerBuildDir) {
      return buildSrcDir;
   }

   // Name the parselets
   public void initialize() {

      DynType type = TypeUtil.getPropertyCache(getClass());
      IBeanMapper[] props = type.getSemanticPropertyList();
      // First have to set all of the names of those connected to field directly.
      for (int i = 0; i < props.length; i++) {
         IBeanMapper mapper = props[i];

         Object val = TypeUtil.getPropertyValue(this, mapper.getField());
         if (val instanceof Parselet) {
            Parselet pl = (Parselet) val;
            initName(pl, mapper.getPropertyName(), null, true);
         }
      }
      // Now set unnamed children based on their wrapping parselets.
      for (int i = 0; i < props.length; i++) {
         IBeanMapper mapper = props[i];
         Object val = TypeUtil.getPropertyValue(this, mapper.getField());
         if (val instanceof Parselet) {
            initName((Parselet) val, mapper.getPropertyName(), null, false);
         }
      }
   }

   private void initName(Parselet p, String propertyName, String prefix, boolean rootOnly) {
      String origName = p.getName();

      if (origName == null || !origName.startsWith("<")) {
         String newName = "<" + propertyName + (prefix == null ? "" : prefix) + ">" + (origName == null ? "" : origName);
         p.setName(newName);
      }
      if (rootOnly)
         p.fieldNamed = true;
      else {
         Parselet replaced = parseletsByName.put(convertNameToKey(p.getName()), p);
         if (replaced != null && replaced != p)
            System.err.println("*** Warning: two parselets with the same name: " + p.getName());
      }

      if (!rootOnly) {
         if (p instanceof NestedParselet) {
            NestedParselet np = (NestedParselet) p;
            if (np.parselets != null) {
               for (int i = 0; i < np.parselets.size(); i++) {
                  Parselet child = np.parselets.get(i);
                  if (!child.fieldNamed)
                     initName(child, propertyName, (prefix == null ? "" : prefix) + "." + i, false);
               }
            }
         }
      }
   }

   public Map<String,Parselet> parseletsByName = new HashMap<String,Parselet>();

   public static String convertNameToKey(String name) {
      // Remove the parameters so it makes it easier to match parselets from different languages
      int six = name.indexOf("(");
      if (six != -1)
         name = name.substring(0,six);
      return name;
   }

   public Parselet getParselet(String name) {
      return parseletsByName.get(name);
   }

   public Parselet findMatchingParselet(Parselet old) {
      String name = convertNameToKey(old.getName());
      return parseletsByName.get(name);
   }

   public GenerateContext newGenerateContext(Parselet parselet, Object value) {
      GenerateContext ctx = new GenerateContext(debug);
      ctx.semanticContext = newSemanticContext(parselet, value);
      return ctx;
   }

   /**
    * Hook for languages to store and manage state used to help guide the parsing process.  For example, in HTML
    * keeping track of the current tag name stack so the grammar can properly assemble the tag-tree.
    */
   public SemanticContext newSemanticContext(Parselet parselet, Object semanticValue) {
      return null;
   }

   public String toString() {
      return getClass().getName();
   }

   public boolean getInheritFiles() {
      return true;
   }
}
