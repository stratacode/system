/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.layer.Layer;
import sc.parser.OrderedChoice;
import sc.parser.ParseUtil;
import sc.parser.Sequence;
import sc.parser.Symbol;

/**
 * This class defines the grammar modifications to StrataCode for the command line interpreter and completion grammars
 * For top-level declarations, we want to parse:
 *    package definition - changes the current package - clears any current imports
 *    import definition - queues up current imports
 *    startTypeDeclaration - define a new model as this point and populate the package and imports
 *    startModifyDeclaration
 *    expression
 *
 * When current-type is not null:
 *    endTypeDeclaration - reverts us back to the first state when we close out the last inner type
 *    startTypeDeclaration for inner type
 *    startModifyDeclaration for inner type
 *    memberDefinition - i.e. anything appropriate for the current type, property assignment, field or method definition
 *    expression
 */
public class CommandSCLanguage extends SCLanguage {
   public final static String SCR_SUFFIX = "scr";

   public Sequence startClassDeclaration = new Sequence("ClassDeclaration(modifiers, operator,,typeName,typeParameters,extendsType,implementsTypes,)",
                        modifiers, classOperators, spacing, identifierSp, optTypeParameters, extendsType, implementsTypes, openBraceEOL);

   public Sequence startModifyDeclaration = new Sequence("ModifyDeclaration(modifiers,typeName,extendsTypes,implementsTypes,)",
                                                  modifiers, qualifiedIdentifier, extendsTypes, implementsTypes, openBraceEOL);

   Sequence endTypeDeclaration = new Sequence("EndTypeDeclaration", closeBrace); // Don't want skipOnError set here like is done with closeBraceEOL

   /*
    * Need a way to parse comments from the command line?   Right now they go into spacing which gets ignored and
    * it is hard to preserve them by adding them into the model.  This attempt failed because I think the NOT
    * operator does not support semantic values in the parser.
    *
   public Sequence semanticEOLComment = new Sequence("EOLComment(,commentBody,)", NOERROR,
           new Symbol("//"),
           new Sequence("('')", new SymbolChoice(NOT | REPEAT | OPTIONAL | NOERROR, "\r\n", "\r", "\n", Symbol.EOF)),
           new OrderedChoice(lineTerminator, new Symbol(Symbol.EOF)));
    */

   public Sequence topLevelCommands = new Sequence("(,.,)", spacing,
           new OrderedChoice("([],[],[],[],[],[],[])", REPEAT | OPTIONAL, reqPackageDeclaration, reqImport, startClassDeclaration,
                             startModifyDeclaration, reqClassBodyDeclaration, statement, endTypeDeclaration),
           new Symbol(EOF));
   {
      // Just like blockStatements, when doing partialValues for the command line, need to skip over error text so we can keep going and not produce an incomplete result at the first failure.
      topLevelCommands.skipOnErrorParselet = skipBodyError;
   }

   /** The command line interpreter does not use this parselet but we need it for the IDE */
   public Sequence cmdScript = new Sequence("CmdScriptModel(commands,)", topLevelCommands, new Symbol(Symbol.EOF));

   // NOTE: we allow statements in the class body for the command line (and at the top-level)
   public Sequence typeCommands = new Sequence("(,.,)", spacing,
           new OrderedChoice("([],[],[],[],[],[])", REPEAT | OPTIONAL, startClassDeclaration, startModifyDeclaration,
                             reqClassBodyDeclaration, expressionStatement, statement, endTypeDeclaration),
           new Symbol(EOF));

   Sequence completeExtends =
           new Sequence("ClassDeclaration(operator,,typeName,typeParameters,extendsType,implementsTypes)",
                   classOperators, spacing, identifierSp, optTypeParameters, extendsType, implementsTypes);

   // TODO: more completions here... can we complete class body definitions, class declarations, etc.?
   public Sequence completionCommands = new Sequence("(,.,)", spacing,
                                                      // Order from most specific to least otherwise parts of the text will match one and we don't try the other one
                                                      new OrderedChoice("(.,.)", completeExtends, expression),
                                                      new Symbol(Symbol.EOF));

   public final static CommandSCLanguage INSTANCE = new CommandSCLanguage();

   public static CommandSCLanguage getCommandSCLanguage() {
      return INSTANCE;
   }
   public CommandSCLanguage() {
      this(null);
   }

   public CommandSCLanguage(Layer layer) {
      super(layer);
      setStartParselet(cmdScript);
      // TODO: we can probably remove these now that we set the StartParselet because it will start everything
      typeCommands.setLanguage(this);
      topLevelCommands.setLanguage(this);
      completionCommands.setLanguage(this);
      modifyDeclaration.setLanguage(this);
      languageName = "SCCmd";
      defaultExtension = SCR_SUFFIX;
      prependLayerPackage = false;
      useSrcDir = false;
   }

   private boolean _inited = false;

   public void initialize() {
      if (_inited)
         return;
      _inited = true;
      // There is no one parselet for the command language and it seems to tunnel into sc language so we need to start
      // a bunch of parselets manually.
      // Maybe we should initialize all parselets that are attached to the language... right now we just use the properties
      // to name parselets.  Some parselets are in a language but not used and currently not initialized.
      ParseUtil.initAndStartComponent(typeCommands);
      ParseUtil.initAndStartComponent(topLevelCommands);
      ParseUtil.initAndStartComponent(completionCommands);
      ParseUtil.initAndStartComponent(modifyDeclaration);
      super.initialize();
   }

   /** TODO: This really should be isProcessed or isTransformed since we can parse these files */
   public boolean isParsed() {
      return false;
   }
}
