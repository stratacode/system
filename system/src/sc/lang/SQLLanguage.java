package sc.lang;

import sc.layer.Layer;
import sc.parser.*;
import sc.type.TypeUtil;
import sc.util.StringUtil;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Subset of sql for StrataCode (scsql). The goal is to support all that we need to maintain the boundary between code and data
 * definition, or eventually evolve to supporting whatever SQL variants are necessary.
 *
 * TODO: this is a starting point for a general purpose SQL language parse. Initially based on a useful subset of postgres sql to keep ddl in-sync with code.
 */
public class SQLLanguage extends SCLanguage {
   protected static final String[] ALL_SQL_KEYWORDS_ARR = {
           "all", "and", "any", "array", "as", "asc", "asymmetric", "both",
           "case", "cast", "check", "collate", "column", "constraint", "create", "current_date", "current_role", "current_time", "current_timestamp", "current_user",
           "default", "deferrable", "desc", "distinct", "do", "else", "end", "except", "false", "for", "foreign", "from", "grant", "group", "having", "in",
           "initially", "intersect", "into", "limit", "localtime", "localtimestamp", "new", "not", "null", "off", "offset", "old", "on", "only", "or", "order",
           "placing", "primary", "references", "select", "session_user", "some", "symmetric", "table", "then", "to", "trailing", "true", "union", "unique", "user",
           "using", "when", "where", "with"};

   protected static Set<IString> ALL_SQL_KEYWORDS = new HashSet<IString>(Arrays.asList(PString.toPString(ALL_SQL_KEYWORDS_ARR)));

   public final static SQLLanguage INSTANCE = new SQLLanguage();

   public SQLLanguage() {
      this(null);
      ignoreCaseInKeywords = true;
   }

   public SQLLanguage(Layer layer) {
      super(layer);
      setStartParselet(sqlFileModel);
      addToSemanticValueClassPath("sc.lang.sql");
      languageName = "SC SQL";
      defaultExtension = "scsql";
   }

   public static SQLLanguage getSQLLanguage() {
      return INSTANCE;
   }

   class ICSymbol extends Symbol {
      ICSymbol(String s) {
         super(s);
         ignoreCase = true;
      }
   }

   class ICSymbolSpace extends SymbolSpace {
      ICSymbolSpace(String s) {
         super(s, IGNORE_CASE);
      }
   }

   class ICSymbolChoiceSpace extends SymbolChoiceSpace {
      ICSymbolChoiceSpace(int options, String...values) {
         super(options | IGNORE_CASE, values);
      }
      ICSymbolChoiceSpace(String...values) {
         super(IGNORE_CASE, values);
      }
   }

   enum DollarTagType {
      Start, Body, End
   }

   ICSymbolSpace noKeyword = new ICSymbolSpace("no");
   ICSymbolSpace notKeyword = new ICSymbolSpace("not");
   ICSymbolSpace nullKeyword = new ICSymbolSpace("null");
   ICSymbolSpace trueKeyword = new ICSymbolSpace("true");
   ICSymbolSpace falseKeyword = new ICSymbolSpace("false");
   ICSymbolSpace withKeyword = new ICSymbolSpace("with");
   ICSymbolSpace optionsKeyword = new ICSymbolSpace("options");
   ICSymbolSpace whereKeyword = new ICSymbolSpace("where");
   ICSymbolSpace matchKeyword = new ICSymbolSpace("match");
   ICSymbolSpace onKeyword = new ICSymbolSpace("on");
   ICSymbolSpace actionKeyword = new ICSymbolSpace("action");
   ICSymbolSpace setKeyword = new ICSymbolSpace("set");
   ICSymbolSpace defaultKeyword = new ICSymbolSpace("default");
   ICSymbolSpace primaryKeyword = new ICSymbolSpace("primary");
   ICSymbolSpace keyKeyword = new ICSymbolSpace("key");
   ICSymbolSpace referencesKeyword = new ICSymbolSpace("references");
   ICSymbolSpace likeKeyword = new ICSymbolSpace("like");
   ICSymbolSpace ifKeyword = new ICSymbolSpace("if");
   ICSymbolSpace ofKeyword = new ICSymbolSpace("of");
   ICSymbolSpace asKeyword = new ICSymbolSpace("as");
   ICSymbolSpace tableKeyword = new ICSymbolSpace("table");
   ICSymbolSpace typeKeyword = new ICSymbolSpace("type");
   ICSymbolSpace existsKeyword = new ICSymbolSpace("exists");
   ICSymbolSpace dollarKeyword = new ICSymbolSpace("$");

   ICSymbol dollarSymbol = new ICSymbol("$");

   public SymbolChoice sqlEscapedStringBody = new SymbolChoice(OPTIONAL | NOT | REPEAT, "\"", "\n", EOF);
   public SymbolChoice sqlSingleQuoteEscapedStringBody = new SymbolChoice(OPTIONAL | NOT | REPEAT, "'", "\n", EOF);

   Symbol doubleQuote = new Symbol("\"");
   SymbolSpace endDoubleQuote = new SymbolSpace("\"");
   Symbol singleQuote = new Symbol("'");
   SymbolSpace endSingleQuote = new SymbolSpace("'");
   {
      endDoubleQuote.addExcludedValues("\"\"");
      endSingleQuote.addExcludedValues("''");

      // Use the Java-like EOLComment inherited from BaseLanguage - replace the first parselet. This changes the default 'spacing' parselet
      EOLComment.set(0, new Symbol("--"));
   }

   public ICSymbolChoiceSpace binaryOperators = new ICSymbolChoiceSpace("and", "or", "<>", "=", "!=", "<", ">", "<=", ">=",
           "between", "is", "+", "-", "*", "/", "%", "^", "&", "|", "<<", ">>", "like", "&&", "||", "@>", "<@", "from");

   public Sequence quotedIdentifier = new Sequence("QuotedIdentifier(,value,)", doubleQuote, sqlEscapedStringBody, endDoubleQuote);

   public Sequence sqlQualifiedIdentifier = new Sequence("QualifiedIdentifier(identifiers)",
                  new Sequence("([],[])", identifier,
                               new Sequence("(,[])", OPTIONAL | REPEAT, new SymbolSpace("."), identifier)));

   OrderedChoice sqlIdentifier = new OrderedChoice("(.,.)", sqlQualifiedIdentifier, quotedIdentifier);

   Sequence sqlIdentifierList = new Sequence("(,[],[],)", openParen, sqlIdentifier, new Sequence("(,[])", REPEAT | OPTIONAL, comma, sqlIdentifier), closeParen);
   Sequence optSqlIdentifierList = (Sequence) sqlIdentifierList.copyWithOptions(OPTIONAL);

   Sequence withOperand = new Sequence("WithOperand(identifier,,operand)", sqlIdentifier, withKeyword, binaryOperators);
   Sequence withOpList = new Sequence("(,[],[],)", openParen, withOperand, new Sequence("(,[])", REPEAT | OPTIONAL, comma, withOperand), closeParen);

   Sequence sequenceOptions = new Sequence("(,'',)", OPTIONAL, comma, new OrderedChoice("('','')", digits, new ICSymbolChoiceSpace("start", "with")), comma);

   public Sequence sqlQuotedStringLiteral = new Sequence("QuotedStringLiteral(,value,)", singleQuote, sqlSingleQuoteEscapedStringBody, endSingleQuote);
   public Sequence escapedStringLiteral = new Sequence("QuotedStringLiteral(,value,)", singleQuote, escapedSingleQuoteString, endSingleQuote);
   public Sequence bitStringLiteral = new Sequence("BitStringLiteral(,value,)", singleQuote, binaryDigits, endSingleQuote);
   public Sequence hexStringLiteral = new Sequence("HexStringLiteral(,value,)", singleQuote, hexDigits, endSingleQuote);

   public Sequence startDollar = new DollarTag(DollarTagType.Start, 0);
   public Sequence endDollar = new DollarTag(DollarTagType.End, 0);

   public Sequence dollarStringBody = new DollarTag(DollarTagType.Body, NOT);

   public Sequence dollarStringLiteral = new Sequence("DollarStringLiteral(,value,,)", startDollar, dollarStringBody, endDollar, spacing);

   public Sequence optExponent = new Sequence("('','')", OPTIONAL, new ICSymbol("e"), digits);

   public OrderedChoice numericConstant = new OrderedChoice(new Sequence("IntConstant(numberPart,exponent,)", digits, optExponent, spacing),
                                                            new Sequence("FloatConstant(numberPart,,fractionPart,exponent,)", optDigits, period, optDigits, optExponent, spacing));

   ICSymbolChoiceSpace unaryPrefix = new ICSymbolChoiceSpace("+", "-", "~", "!!", "@", "not", "distinct", "|/", "||/");

   IndexedChoice sqlPrimary = new IndexedChoice();

   Sequence sqlPrefixUnaryExpression = new Sequence("SQLPrefixUnaryExpression(operator,expression)", unaryPrefix, sqlPrimary);
   Sequence sqlBinaryOperands = new Sequence("SQLBinaryOperand(operator,rhs)", OPTIONAL | REPEAT, binaryOperators, sqlPrimary);
   public Sequence sqlExpression = new ChainedResultSequence("SQLBinaryExpression(firstExpr,operands)", sqlPrimary, sqlBinaryOperands);
   Sequence sqlParenExpression = new Sequence("SQLParenExpression(,expression,)" , openParen, sqlExpression, closeParenSkipOnError);
   Sequence sqlExpressionList = new Sequence("([],[])", OPTIONAL, sqlExpression, new Sequence("(,[])", OPTIONAL | REPEAT, comma, sqlExpression));
   Sequence functionCall = new Sequence("FunctionCall(functionName,,expressionList,)", sqlQualifiedIdentifier, openParen, sqlExpressionList, closeParenSkipOnError);
   Sequence trueLiteral = new Sequence("SQLTrueLiteral(value)", trueKeyword);
   Sequence falseLiteral = new Sequence("SQLFalseLiteral(value)", falseKeyword);
   Sequence sqlNullLiteral = new Sequence("SQLNullLiteral(value)", nullKeyword);
   // Because current_date etc are keywords, they are not matched as sqlIdentifiers so need a new rule here
   Sequence keywordLiteral = new Sequence("KeywordLiteral(value)", new ICSymbolChoiceSpace("current_timestamp", "current_time", "current_date", "current_user", "session_user", "current_role"));

   Sequence sqlIdentifierExpression = new Sequence("SQLIdentifierExpression(identifier)", sqlIdentifier);

   // TODO :: casting

   {
      sqlPrimary.put("(", sqlParenExpression);
      sqlPrimary.put("'", sqlQuotedStringLiteral);
      sqlPrimary.put("$", dollarStringLiteral);
      sqlPrimary.put("B", bitStringLiteral);
      sqlPrimary.put("b", bitStringLiteral);
      sqlPrimary.put("x", hexStringLiteral);
      sqlPrimary.put("X", hexStringLiteral);
      sqlPrimary.put("n", sqlNullLiteral);
      sqlPrimary.put("N", sqlNullLiteral);
      sqlPrimary.put("t", trueLiteral);
      sqlPrimary.put("T", trueLiteral);
      sqlPrimary.put("f", falseLiteral);
      sqlPrimary.put("F", falseLiteral);
      sqlPrimary.put("X", hexStringLiteral);
      // TODO
      //sqlExpression.put("U&", ucStringLiteral);
      sqlPrimary.put("E", escapedStringLiteral);
      sqlPrimary.put("e", escapedStringLiteral);
      for (int i = 0; i < 10; i++)
         sqlPrimary.put(String.valueOf(i), numericConstant);
      sqlPrimary.put(".", numericConstant);
      sqlPrimary.addDefault(functionCall, sqlIdentifierExpression, sqlPrefixUnaryExpression, keywordLiteral);
   }

   Sequence namedConstraint = new Sequence("NamedConstraint(,constraintName)", new ICSymbolSpace("constraint"), sqlIdentifier);
   Sequence optNamedConstraint = (Sequence) namedConstraint.copyWithOptions(OPTIONAL);

   Sequence notNullConstraint = new Sequence("NotNullConstraint(,)", notKeyword, nullKeyword);
   Sequence nullConstraint = new Sequence("NullConstraint(value)", nullKeyword);

   Sequence checkConstraint = new Sequence("CheckConstraint(,,expression,)", new ICSymbolSpace("check"), openParen, sqlExpression, closeParen);

   Sequence defaultConstraint = new Sequence("DefaultConstraint(,expression)", defaultKeyword, sqlExpression);

   Sequence storageParamValue = new Sequence("('','')", identifier, new Sequence(OPTIONAL, equalSign, sqlPrimary));

   Sequence paramList = new Sequence("([],[])", storageParamValue, new Sequence("(,[])", OPTIONAL, comma, storageParamValue));

   // TODO: indexParameters are more than just the WITH(x=30) that are in storageParameters so when we generalize this, only do it for
   // indexParameters.
   Sequence indexParameters = new Sequence("IndexParameters(,,paramList,)", OPTIONAL, withKeyword, openParen, paramList, closeParen);
   Sequence storageParameters = indexParameters;

   Sequence colUniqueConstraint = new Sequence("UniqueConstraint(,indexParams)", new ICSymbolSpace("unique"), indexParameters);

   Sequence tableUniqueConstraint = new Sequence("UniqueConstraint(,columnList,indexParams)", new ICSymbolSpace("unique"), sqlIdentifierList, indexParameters);

   Sequence colPrimaryKeyConstraint = new Sequence("PrimaryKeyConstraint(,,indexParams)", primaryKeyword, keyKeyword, indexParameters);
   Sequence tablePrimaryKeyConstraint = new Sequence("PrimaryKeyConstraint(,,columnList,indexParams)", primaryKeyword, keyKeyword, sqlIdentifierList, indexParameters);

   Sequence usingMethod = new Sequence("(,'')",OPTIONAL, new ICSymbolSpace("using"), identifier);

   Sequence optWhereClause = new Sequence("WhereClause(,expression)", OPTIONAL, whereKeyword, sqlExpression);

   Sequence excludeConstraint = new Sequence("ExcludeConstraint(,usingMethod,withOpList,indexParams,whereClause)", new ICSymbolSpace("exclude"), usingMethod, withOpList, indexParameters, optWhereClause);

   Sequence optColumnRef = new Sequence("(,.,)", OPTIONAL, openParen, sqlIdentifier, closeParen);

   Sequence matchOption = new Sequence("(,'')", OPTIONAL, matchKeyword, new ICSymbolChoiceSpace("full", "partial", "simple"));

   Sequence onOptions = new Sequence("OnOption(,onType,action)", OPTIONAL | REPEAT, onKeyword, new ICSymbolChoiceSpace("delete", "update"),
                                    new OrderedChoice(new ICSymbolChoiceSpace("restrict", "cascade"), new Sequence(noKeyword, actionKeyword), new Sequence(setKeyword, new OrderedChoice(nullKeyword, defaultKeyword))));

   Sequence referencesConstraint = new Sequence("ReferencesConstraint(,refTable,columnRef,matchOption,onOptions)",
                                                 referencesKeyword, sqlIdentifier, optColumnRef, matchOption, onOptions);

   OrderedChoice columnConstraints = new OrderedChoice("([],[],[],[],[],[],[],[])", OPTIONAL | REPEAT, notNullConstraint, nullConstraint, checkConstraint, defaultConstraint,
                                                      new Sequence("GeneratedConstraint(,whenOptions,sequenceOptions)",
                                                             new ICSymbolSpace("generated"),
                                                             new OrderedChoice(OPTIONAL, new ICSymbolSpace("always"), new Sequence(new ICSymbolSpace("by"),  new ICSymbolSpace("default"))),
                                                             new Sequence("(,,.)", new ICSymbolSpace("as"),  new ICSymbolSpace("identity"), sequenceOptions)),
                                                       colUniqueConstraint, colPrimaryKeyConstraint, referencesConstraint);

   Sequence foreignKeyConstraint = new Sequence("ForeignKeyConstraint(,,columnList,,refTable,refColList,matchOption,onOptions)",
                                                 new ICSymbolSpace("foreign"), keyKeyword, sqlIdentifierList,
                                                 referencesKeyword, sqlIdentifier, optSqlIdentifierList, matchOption, onOptions);

   Sequence collation = new Sequence("(,'')", OPTIONAL, new ICSymbolSpace("collate"), quotedIdentifier);

   ICSymbolChoiceSpace intervalOptions = new ICSymbolChoiceSpace(OPTIONAL, "YEAR", "MONTH", "DAY", "HOUR", "MINUTE", "SECOND", "YEAR TO MONTH", "DAY TO HOUR", "DAY TO MINUTE",
                                                                 "DAY TO SECOND", "HOUR TO MINUTE", "HOUR TO SECOND", "MINUTE TO SECOND");

   Sequence sizeList = new Sequence("(,[],)", OPTIONAL | REPEAT, openParen, digits, closeParen);
   Sequence dimsList = new Sequence("(,[],)", OPTIONAL | REPEAT, openSqBracket, optDigits, closeSqBracket);
   {
      dimsList.allowNullElements = true; // We need to store an empty element when there are no digits to keep track of the brackets themselves
   }
   public Sequence sqlDataType = new Sequence("SQLDataType(typeName,sizeList,dimsList,intervalOptions)", identifier, sizeList, dimsList, intervalOptions);

   Sequence columnDef = new Sequence("ColumnDef(columnName,columnType,collation,constraintName,columnConstraints)",
                                                    sqlIdentifier, sqlDataType, collation, optNamedConstraint, columnConstraints);

   Sequence withOptions = new Sequence("('','')", withKeyword, optionsKeyword);

   Sequence columnWithOptions = new Sequence("ColumnWithOptions(columnName,,constraintName,columnConstraints)",
                                                                    sqlIdentifier, withOptions, optNamedConstraint, columnConstraints);

   Sequence likeSource = new Sequence("LikeDef(,sourceTable,likeOptions)", likeKeyword, sqlIdentifier, new ICSymbolChoiceSpace(REPEAT, "including", "defaults", "comments", "constraints", "identity", "indexes", "statistics", "storage"));

   // TODO: add 'exclude' rule to list of table constraints at the end here
   // TODO: make this an indexedChoice
   Sequence tableConstraint = new Sequence("TableConstraint(namedConstraint,constraint)", optNamedConstraint,
           new OrderedChoice("(.,.,.,.,.,.)", checkConstraint, tableUniqueConstraint, tablePrimaryKeyConstraint,
                             referencesConstraint, foreignKeyConstraint, excludeConstraint));
   OrderedChoice tableDef = new OrderedChoice(columnDef, likeSource, tableConstraint, columnWithOptions);

   Sequence tableDefList = new Sequence("([],[])", tableDef, new Sequence("(,[])", OPTIONAL | REPEAT, commaEOL, tableDef));

   Sequence ifNotExists = new Sequence("('','','')", OPTIONAL, ifKeyword, notKeyword, existsKeyword);

   Sequence tableSpace = new Sequence("(,'')", OPTIONAL, new ICSymbolSpace("tablespace"), identifier);

   Sequence ofType = new Sequence("(,.)", OPTIONAL, ofKeyword, sqlIdentifier);

   Sequence tableInherits = new Sequence("(,.)", OPTIONAL, new ICSymbolSpace("inherits"), sqlIdentifierList);

   Sequence tablePartition = new Sequence("TablePartition(,,partitionBy,,expressionList,)", OPTIONAL,
           new ICSymbolSpace("partition"), new ICSymbolSpace("by"), new ICSymbolChoiceSpace("range", "list"),
           openParen, sqlExpressionList, closeParen);

   Sequence createTable = new Sequence("CreateTable(tableOptions,,ifNotExists,tableName,ofType,,tableDefs,,tableInherits,tablePartition,storageParams,tableSpace)",
                   new ICSymbolChoiceSpace(REPEAT | OPTIONAL, "global", "local", "temporary", "temp", "unlogged"),
                   new ICSymbolSpace("table"), ifNotExists, sqlIdentifier, ofType, openParen, tableDefList, closeParen,
                   tableInherits, tablePartition, storageParameters, tableSpace);

   // TODO: create type 'as enum' and 'as range' and with input/output params
   Sequence createType = new Sequence("CreateType(,typeName,,,tableDefs,)", typeKeyword, sqlIdentifier, asKeyword, openParen, tableDefList, closeParen);

   Sequence dropTable = new Sequence("DropTable(,tableNameList,dropOptions)", tableKeyword, sqlIdentifierList, new ICSymbolChoiceSpace("cascade", "restrict"));

   OrderedChoice createChoice = new OrderedChoice("(.,.)", createTable, createType);
   Sequence createCommand = new Sequence("(,.,)", new ICSymbolSpace("create"), createChoice, semicolonEOL);
   OrderedChoice dropChoice = new OrderedChoice("(.,.)", dropTable);
   Sequence dropCommand = new Sequence("(,.,)", new ICSymbolSpace("drop"), dropChoice, semicolonEOL);

   public OrderedChoice sqlCommands = new OrderedChoice( "([])", REPEAT | OPTIONAL, createCommand);

   public Sequence sqlFileModel = new Sequence("SQLFileModel(,sqlCommands,)", spacing, sqlCommands, new Symbol(EOF));

   {
      // For when we convert scsql to java, need to update the result class name so it matches. This corresponds to the
      // code in SQLFileModel.getTransformedResult
      compilationUnit.setResultClassName("SQLFileModel");
   }

   class DollarTag extends Sequence {
      DollarTagType tagType;
      DollarTag(DollarTagType tagType, int options) {
         // TODO: The identifier here should not contain '$' unlike a normal SQL unquoted identifier
         super("(,'',)", options, dollarSymbol, identifier, dollarSymbol);
         this.tagType = tagType;
      }

      protected String accept(SemanticContext ctx, Object value, int startIx, int endIx) {
         String res = super.accept(ctx, value, startIx, endIx);
         if (res != null)
            return res;

         ParentParseNode pn = (ParentParseNode) value;
         Object tagValue = pn.children.get(1);

         String strValue = tagValue == null ? "" : tagValue.toString();

         TagStackSemanticContext sctx = (TagStackSemanticContext) ctx;

         switch (tagType) {
            case Start:
               if (startIx != -1)
                  sctx.addEntry(strValue, startIx, endIx);
               break;
            case Body: // Body has the parselet 'NOT' option - it matches just like 'End' but the result will be the opposite
            case End:
               // Currently not doing the tag name stack during generate
               if (startIx == -1)
                  return null;

               TagStackSemanticContext hctx = ((TagStackSemanticContext) ctx);
               if (hctx.allowAnyCloseTag) // In diagnostic mode - need to just parse the close tag for an error
                  return null;
               String openTagName = hctx.getCurrentTagName();
               if (openTagName == null)
                  return "No open tag for close tag: " + strValue;
               if (!openTagName.equals(strValue))
                  return "Mismatching close tag name: " + value + " does not match open: " + openTagName;
               hctx.popTagName(startIx);
               break;
         }
         return null;
      }
   }

   /**
    * Hook for languages to store and manage state used to help guide the parsing process.  For example, in HTML
    * keeping track of the current tag name stack so the grammar can properly assemble the tag-tree.
    */
   public SemanticContext newSemanticContext(Parselet parselet, Object semanticValue) {
      return new TagStackSemanticContext(false);
   }

   public Set getKeywords() {
      return ALL_SQL_KEYWORDS;
   }
}
