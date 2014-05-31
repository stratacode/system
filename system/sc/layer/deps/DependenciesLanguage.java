/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer.deps;

import sc.lang.BaseLanguage;
import sc.parser.*;

public class DependenciesLanguage extends BaseLanguage implements IParserConstants
{
   public final static DependenciesLanguage INSTANCE = new DependenciesLanguage();

   Sequence fileName =  new Sequence("(.,)", new OrderedChoice("('','')", REPEAT, identifierChar, new SymbolChoice("/", ".", "\\", "-", "_")), spacing);
   Sequence fileNameList = new Sequence("([],[])", OPTIONAL, fileName, new Sequence("(,[])", OPTIONAL | REPEAT, comma, fileName));
   Sequence depsList = new Sequence("(,.,)", openBraceEOL,
        new Sequence("LayerDependencies(layerName,,fileList,)", REPEAT | OPTIONAL, qualifiedIdentifier, openBrace, fileNameList, closeBrace),
        closeBraceEOL);
   // This node has no grammar - it is just here to do the slot mappings...
   Sequence setFileDeps = new Sequence("(fileDeps)", depsList);
   OrderedChoice depsListOrDir = new OrderedChoice(new Sequence("(isDirectory,)", new Symbol("{dir"), closeBraceEOL), new Sequence("(isError,)", new Symbol("{error"), closeBraceEOL), setFileDeps);
   Sequence genFiles = new Sequence("(,.)", OPTIONAL, new SymbolSpace(">>>"), fileNameList);
   Sequence depsEnts = new Sequence("DependencyEntry(fileName, genFileNames, *)", REPEAT | OPTIONAL, fileName, genFiles, depsListOrDir);
   Sequence depsFile = new Sequence("DependencyFile(depList,,)", depsEnts, spacing, new Symbol(EOF));

   public DependenciesLanguage() {
      setSemanticValueClassPath("sc.layer.deps");
      setStartParselet(depsFile);
   }

}
