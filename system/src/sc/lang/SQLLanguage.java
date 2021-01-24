/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.layer.Layer;
import sc.parser.*;
import sc.type.TypeUtil;
import sc.util.StringUtil;

import java.util.ArrayList;
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
           "using", "when", "where", "with", "nulls", "include"};

   protected static Set<IString> ALL_SQL_KEYWORDS = new HashSet<IString>(Arrays.asList(PString.toPString(ALL_SQL_KEYWORDS_ARR)));

   public static SQLLanguage INSTANCE;

   public SQLLanguage() {
      this(null);
      ignoreCaseInKeywords = true;
   }

   public SQLLanguage(Layer layer) {
      super(layer);
      setStartParselet(sqlFileModel);
      addToSemanticValueClassPath("sc.lang.sql");
      addToSemanticValueClassPath("sc.lang.sql.seqOpt");
      addToSemanticValueClassPath("sc.lang.sql.funcOpt");
      languageName = "SCSQL";
      defaultExtension = "scsql";
      if (INSTANCE == null)
         INSTANCE = this;
   }

   public static SQLLanguage getSQLLanguage() {
      if (INSTANCE == null)
         INSTANCE = new SQLLanguage();
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
      ICSymbolSpace(String s, int opts) {
         super(s, opts | IGNORE_CASE);
      }
   }

   class ICKeywordSpace extends KeywordSpace {
      ICKeywordSpace(String s) {
         super(s, IGNORE_CASE);
      }
      ICKeywordSpace(String s, int opts) {
         super(s, opts | IGNORE_CASE);
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

   class ICKeywordChoice extends KeywordChoice {
      ICKeywordChoice(int options, String...values) {
         super("('',,)", options | IGNORE_CASE, true, values);
      }
      ICKeywordChoice(String...values) {
         super("('',,)", IGNORE_CASE, true, values);
      }
   }

   /**
    * Works like ICSymbolChoiceSpace but supports multi-word symbols separated by whitespace. Configures this OrderedChoice
    * to select one of the specific values. It's an OrderedChoice where we use one ICSymbolSpace for each one word symbol
    * and a Sequence of ICSymbolSpace's for multi-word symbols.
    */
   class ICMultiSymbolChoice extends OrderedChoice {
      public ICMultiSymbolChoice(String baseName, int options, String...values) {
         super(options);
         ArrayList<Parselet> children = new ArrayList<Parselet>();
         int ct = 0;
         for (String value:values) {
            Parselet child;
            if (value.indexOf(' ') != -1) {
               String[] words = StringUtil.split(value, ' ');
               ArrayList<Parselet> wordPList = new ArrayList<Parselet>();
               StringBuilder nameSB = new StringBuilder("<" + baseName + "-" + ct + ">(");
               int wordCt = 0;
               for (String word:words) {
                  if (wordCt != 0)
                     nameSB.append(", ");
                  wordPList.add(new ICSymbolSpace(word));
                  nameSB.append("''");
                  wordCt++;
               }
               nameSB.append(")");
               child = new Sequence(nameSB.toString(), wordPList.toArray(new Parselet[wordPList.size()]));
            }
            else {
               child = new ICSymbolSpace(value);
            }
            children.add(child);
            ct++;
         }
         for (Parselet child:children)
            add(child);
      }
   }

   enum DollarTagType {
      Start, End
   }

   ICKeywordSpace noKeyword = new ICKeywordSpace("no");
   ICKeywordSpace notKeyword = new ICKeywordSpace("not");
   ICKeywordSpace nullKeyword = new ICKeywordSpace("null");
   ICKeywordSpace trueKeyword = new ICKeywordSpace("true");
   ICKeywordSpace falseKeyword = new ICKeywordSpace("false");
   ICKeywordSpace withKeyword = new ICKeywordSpace("with");
   ICKeywordSpace optionsKeyword = new ICKeywordSpace("options");
   ICKeywordSpace whereKeyword = new ICKeywordSpace("where");
   ICKeywordSpace matchKeyword = new ICKeywordSpace("match");
   ICKeywordSpace onKeyword = new ICKeywordSpace("on");
   ICKeywordSpace actionKeyword = new ICKeywordSpace("action");
   ICKeywordSpace setKeyword = new ICKeywordSpace("set");
   ICKeywordSpace defaultKeyword = new ICKeywordSpace("default");
   ICKeywordSpace primaryKeyword = new ICKeywordSpace("primary");
   ICKeywordSpace keyKeyword = new ICKeywordSpace("key");
   ICKeywordSpace referencesKeyword = new ICKeywordSpace("references");
   ICKeywordSpace likeKeyword = new ICKeywordSpace("like");
   ICKeywordSpace ifKeyword = new ICKeywordSpace("if");
   ICKeywordSpace ofKeyword = new ICKeywordSpace("of");
   ICKeywordSpace asKeyword = new ICKeywordSpace("as");
   ICKeywordSpace tableKeyword = new ICKeywordSpace("table");
   ICKeywordSpace enumKeyword = new ICKeywordSpace("enum");
   ICKeywordSpace typeKeyword = new ICKeywordSpace("type");
   ICKeywordSpace existsKeyword = new ICKeywordSpace("exists");
   ICKeywordSpace byKeyword = new ICKeywordSpace("by");
   ICKeywordSpace optByKeyword = new ICKeywordSpace("by", OPTIONAL);
   ICKeywordSpace addKeyword = new ICKeywordSpace("add");
   ICKeywordSpace dropKeyword = new ICKeywordSpace("drop");

   ICKeywordSpace constraintKeyword = new ICKeywordSpace("constraint");
   ICKeywordSpace optConstraintKeyword = (ICKeywordSpace) constraintKeyword.copyWithOptions(OPTIONAL);

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
      spacing.remove("//");
      spacing.put("--", EOLComment);
   }

   public ICSymbolChoiceSpace binaryOperators = new ICSymbolChoiceSpace("and", "or", "<>", "=", "!=", "<", ">", "<=", ">=", "->>", "->",
           "between", "is", "+", "-", "*", "/", "%", "^", "&", "|", "<<", ">>", "like", "&&", "||", "@>", "<@", "from",
           // TODO: Treating 'as' as a binary operator even though it shows up only in the 'cast' function. Also :: is a binary operator even though the RHS is
           // always a sqlDataType, not an expression.
           "as", "::");

   public Sequence quotedIdentifier = new Sequence("QuotedIdentifier(,value,)", doubleQuote, sqlEscapedStringBody, endDoubleQuote);

   public Sequence sqlQualifiedIdentifier = new Sequence("QualifiedIdentifier(identifiers)",
                  new Sequence("([],[])", identifier,
                               new Sequence("(,[])", OPTIONAL | REPEAT, new SymbolSpace("."), identifier)));

   OrderedChoice sqlIdentifier = new OrderedChoice("(.,.)", sqlQualifiedIdentifier, quotedIdentifier);
   OrderedChoice optSqlIdentifier = (OrderedChoice) sqlIdentifier.copyWithOptions(OPTIONAL);

   Sequence sqlIdentifierList = new Sequence("(,[],[],)", openParen, sqlIdentifier, new Sequence("(,[])", REPEAT | OPTIONAL, comma, sqlIdentifier), closeParen);
   {
      sqlIdentifierList.minContentSlot = 1;
   }
   Sequence optSqlIdentifierList = (Sequence) sqlIdentifierList.copyWithOptions(OPTIONAL);

   Sequence sqlIdentifierCommaList = new Sequence("([],[])", sqlIdentifier, new Sequence("(,[])", REPEAT | OPTIONAL, comma, sqlIdentifier));

   Sequence withOperand = new Sequence("WithOperand(identifier,,operand)", sqlIdentifier, withKeyword, binaryOperators);
   Sequence withOpList = new Sequence("(,[],[],)", openParen, withOperand, new Sequence("(,[])", REPEAT | OPTIONAL, comma, withOperand), closeParen);
   {
      withOpList.minContentSlot = 1;
   }

   public Sequence sqlQuotedStringLiteral = new Sequence("QuotedStringLiteral(,value,)", singleQuote, sqlSingleQuoteEscapedStringBody, endSingleQuote);
   public Sequence escapedStringLiteral = new Sequence("QuotedStringLiteral(,value,)", singleQuote, escapedSingleQuoteString, endSingleQuote);
   public Sequence bitStringLiteral = new Sequence("BitStringLiteral(,value,)", singleQuote, binaryDigits, endSingleQuote);
   public Sequence hexStringLiteral = new Sequence("HexStringLiteral(,value,)", singleQuote, hexDigits, endSingleQuote);

   public Symbol dollarTagId = new Symbol(OPTIONAL | NOT | REPEAT, "$");

   public Sequence startDollar = new DollarTag(DollarTagType.Start, 0);
   public Sequence endDollar = new DollarTag(DollarTagType.End, 0);

   public OrderedChoice dollarStringBody = new OrderedChoice("('',)", REPEAT | OPTIONAL, new Symbol(NOT, "$"), new Sequence(dollarSymbol, new DollarTagBody()));

   public Sequence dollarStringLiteral = new Sequence("DollarStringLiteral(,value,,)", startDollar, dollarStringBody, endDollar, spacing);

   public Sequence optExponent = new Sequence("('','')", OPTIONAL, new ICSymbol("e"), digits);

   // Must parse float first since int will match part of a float but not vice versa
   public OrderedChoice numericConstant =
        new OrderedChoice(new Sequence("FloatConstant(numberPart,,fractionPart,exponent,)", optDigits, period, optDigits, optExponent, spacing),
                          new Sequence("IntConstant(numberPart,exponent,)", digits, optExponent, spacing));

   ICSymbolChoiceSpace unaryPrefix = new ICSymbolChoiceSpace("+", "-", "~", "!!", "@", "not", "distinct", "|/", "||/");

   IndexedChoice sqlPrimary = new IndexedChoice();

   Sequence sqlPrefixUnaryExpression = new Sequence("SQLPrefixUnaryExpression(operator,expression)", unaryPrefix, sqlPrimary);
   Sequence sqlBinaryOperands = new Sequence("SQLBinaryOperand(operator,rhs)", OPTIONAL | REPEAT, binaryOperators, sqlPrimary);
   public Sequence sqlExpression = new ChainedResultSequence("SQLBinaryExpression(firstExpr,operands)", sqlPrimary, sqlBinaryOperands);
   Sequence sqlParenExpression = new Sequence("SQLParenExpression(,expression,)" , openParen, sqlExpression, closeParenSkipOnError);
   {
      sqlParenExpression.minContentSlot = 1;
   }
   Sequence sqlExpressionList = new Sequence("([],[])", OPTIONAL, sqlExpression, new Sequence("(,[])", OPTIONAL | REPEAT, comma, sqlExpression));
   Sequence functionCall = new Sequence("FunctionCall(functionName,,expressionList,)", sqlQualifiedIdentifier, openParen, sqlExpressionList, closeParenSkipOnError);
   Sequence trueLiteral = new Sequence("SQLTrueLiteral(value)", trueKeyword);
   Sequence falseLiteral = new Sequence("SQLFalseLiteral(value)", falseKeyword);
   Sequence sqlNullLiteral = new Sequence("SQLNullLiteral(value)", nullKeyword);
   // Because current_date etc are keywords, they are not matched as sqlIdentifiers so need a new rule here
   Sequence keywordLiteral = new Sequence("KeywordLiteral(value)", new ICSymbolChoiceSpace("current_timestamp", "current_time", "current_date", "current_user", "session_user", "current_role"));

   Sequence sqlIdentifierExpression = new Sequence("SQLIdentifierExpression(identifier)", sqlIdentifier);

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
      // TODO - unicode string literals
      //sqlExpression.put("U&", ucStringLiteral);
      sqlPrimary.put("E", escapedStringLiteral);
      sqlPrimary.put("e", escapedStringLiteral);
      for (int i = 0; i < 10; i++)
         sqlPrimary.put(String.valueOf(i), numericConstant);
      sqlPrimary.put(".", numericConstant);
      sqlPrimary.addDefault(functionCall, sqlIdentifierExpression, sqlPrefixUnaryExpression, keywordLiteral);
   }

   public IndexedChoice sqlStringLiteral = new IndexedChoice();
   {
      sqlStringLiteral.put("'", sqlQuotedStringLiteral);
      sqlStringLiteral.put("$", dollarStringLiteral);
      // TODO: does escapedStringLiteral belong here?
   }

   ICSymbolSpace cycleKeyword = new ICSymbolSpace("cycle");
   ICSymbolSpace minValueKeyword = new ICSymbolSpace("minvalue");
   ICSymbolSpace maxValueKeyword = new ICSymbolSpace("maxvalue");

   Sequence seqValue = new Sequence("(negativeValue,.)", new ICSymbolSpace("-", OPTIONAL), numericConstant);

   Sequence incrementBy = new Sequence("IncrementSeqOpt(,,value)", new ICSymbolSpace("increment"), optByKeyword, seqValue);
   Sequence noMinValue = new Sequence("NoMinSeqOpt(,)", noKeyword, minValueKeyword);
   Sequence noMaxValue = new Sequence("NoMaxSeqOpt(,)", noKeyword, maxValueKeyword);
   Sequence minValue = new Sequence("MinSeqOpt(,value)", minValueKeyword, seqValue);
   Sequence maxValue = new Sequence("MaxSeqOpt(,value)", maxValueKeyword, seqValue);
   Sequence startWith = new Sequence("StartWithSeqOpt(,,value)", new ICSymbolSpace("start"), new ICSymbolSpace("with", OPTIONAL), seqValue);
   Sequence cacheSize = new Sequence("CacheSizeSeqOpt(,value)", new ICSymbolSpace("cache"), seqValue);
   Sequence cycleOption = new Sequence("CycleSeqOpt()", cycleKeyword);
   Sequence noCycleOption = new Sequence("NoCycleSeqOpt(,)", noKeyword, cycleKeyword);
   Sequence ownedBy = new Sequence("OwnedBySeqOpt(,,name)", new ICSymbolSpace("owned"), byKeyword, sqlIdentifier);

   OrderedChoice sequenceOptions = new OrderedChoice("([],[],[],[],[],[],[],[],[],[])", REPEAT, incrementBy, noMaxValue, noMinValue, minValue, maxValue, startWith, cacheSize, cycleOption, noCycleOption, ownedBy);

   Sequence sequenceOptionsWithParens = new Sequence("(,[],)", OPTIONAL, openParen, sequenceOptions, closeParen);
   {
      sequenceOptionsWithParens.minContentSlot = 1;
   }

   Sequence namedConstraint = new Sequence("NamedConstraint(,constraintName)", constraintKeyword, sqlIdentifier);
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

   ICSymbolSpace usingKeyword = new ICSymbolSpace("using");

   Sequence usingMethod = new Sequence("(,'')", OPTIONAL, usingKeyword, identifier);

   Sequence usingExpression = new Sequence("(,.)", OPTIONAL, new ICSymbolSpace("using"), sqlExpression);

   Sequence optWhereClause = new Sequence("WhereClause(,expression)", OPTIONAL, whereKeyword, sqlExpression);

   Sequence excludeConstraint = new Sequence("ExcludeConstraint(,usingMethod,withOpList,indexParams,whereClause)", new ICSymbolSpace("exclude"), usingMethod, withOpList, indexParameters, optWhereClause);

   Sequence optColumnRef = new Sequence("(,.,)", OPTIONAL, openParen, sqlIdentifier, closeParen);
   {
      optColumnRef.minContentSlot = 1;
   }

   Sequence matchOption = new Sequence("(,'')", OPTIONAL, matchKeyword, new ICSymbolChoiceSpace("full", "partial", "simple"));

   Sequence onOptions = new Sequence("OnOption(,onType,action)", OPTIONAL | REPEAT, onKeyword, new ICSymbolChoiceSpace("delete", "update"),
                                    new OrderedChoice(new ICSymbolChoiceSpace("restrict", "cascade"), new Sequence(noKeyword, actionKeyword), new Sequence(setKeyword, new OrderedChoice(nullKeyword, defaultKeyword))));

   Sequence referencesConstraint = new Sequence("ReferencesConstraint(,refTable,columnRef,matchOption,onOptions)",
                                                 referencesKeyword, sqlIdentifier, optColumnRef, matchOption, onOptions);

   ICKeywordSpace alwaysKeyword = new ICKeywordSpace("always");
   ICKeywordSpace storedKeyword = new ICKeywordSpace("stored");

   Sequence generatedConstraint = new Sequence("GeneratedConstraint(,genConstraint)",
                   new ICSymbolSpace("generated"),
                   new OrderedChoice("(.,.)", new Sequence("GeneratedIdentity(whenOptions,,,sequenceOptions)",
                                                  new OrderedChoice("(.,.)", alwaysKeyword, new Sequence(byKeyword, defaultKeyword)),
                                                  asKeyword, new ICKeywordSpace("identity"), sequenceOptionsWithParens),
                                     new Sequence("GeneratedExpr(,,,expression,,)", alwaysKeyword, asKeyword, openParen, sqlExpression, closeParen, storedKeyword)));

   OrderedChoice columnConstraints = new OrderedChoice("([],[],[],[],[],[],[],[])", OPTIONAL | REPEAT,
         notNullConstraint, nullConstraint, checkConstraint, defaultConstraint, generatedConstraint,
         colUniqueConstraint, colPrimaryKeyConstraint, referencesConstraint);

   Sequence foreignKeyConstraint = new Sequence("ForeignKeyConstraint(,,columnList,,refTable,refColList,matchOption,onOptions)",
                                                 new ICSymbolSpace("foreign"), keyKeyword, sqlIdentifierList,
                                                 referencesKeyword, sqlIdentifier, optSqlIdentifierList, matchOption, onOptions);

   // Here we keep the collate keyword as part of the value because it won't generate properly without it.
   Sequence collation = new Sequence("Collation(, identifier)", OPTIONAL, new ICSymbolSpace("collate"), quotedIdentifier);

   ICSymbolChoiceSpace intervalOptions = new ICSymbolChoiceSpace(OPTIONAL, "YEAR", "MONTH", "DAY", "HOUR", "MINUTE", "SECOND", "YEAR TO MONTH", "DAY TO HOUR", "DAY TO MINUTE",
                                                                 "DAY TO SECOND", "HOUR TO MINUTE", "HOUR TO SECOND", "MINUTE TO SECOND");

   Sequence sizeList = new Sequence("(,[],)", OPTIONAL | REPEAT, openParen, digits, closeParen);
   {
      sizeList.minContentSlot = 1;
   }
   Sequence dimsList = new Sequence("(,[],)", OPTIONAL | REPEAT, openSqBracket, optDigits, closeSqBracket);
   {
      dimsList.allowNullElements = true; // We need to store an empty element when there are no digits to keep track of the brackets themselves
      dimsList.minContentSlot = 1;
   }
   ICKeywordSpace doubleKeyword = new ICKeywordSpace("double");
   ICKeywordSpace precisionKeyword = new ICKeywordSpace("precision");
   OrderedChoice typeIdentifier = new OrderedChoice("(.,.)", new Sequence("('','')", doubleKeyword, precisionKeyword), identifier);
   public Sequence sqlDataType = new Sequence("SQLDataType(typeName,sizeList,dimsList,intervalOptions)", typeIdentifier, sizeList, dimsList, intervalOptions);

   Sequence stringLiteralList = new Sequence("([],[])", escapedStringLiteral, new Sequence("(,[])", REPEAT | OPTIONAL, comma, escapedStringLiteral));

   public OrderedChoice sqlParamType = new OrderedChoice("(.,.)", sqlIdentifier, sqlDataType);

   Sequence columnDef = new Sequence("ColumnDef(columnName,columnType,collation,namedConstraint,columnConstraints)",
                                                    sqlIdentifier, sqlDataType, collation, optNamedConstraint, columnConstraints);

   Sequence withOptions = new Sequence("('','')", withKeyword, optionsKeyword);

   Sequence columnWithOptions = new Sequence("ColumnWithOptions(columnName,,namedConstraint,columnConstraints)",
                                                                    sqlIdentifier, withOptions, optNamedConstraint, columnConstraints);

   Sequence likeSource = new Sequence("LikeDef(,sourceTable,likeOptions)", likeKeyword, sqlIdentifier, new ICSymbolChoiceSpace(REPEAT, "including", "defaults", "comments", "constraints", "identity", "indexes", "statistics", "storage"));

   // TODO: add 'exclude' rule to list of table constraints at the end here
   // TODO: make this an indexedChoice
   OrderedChoice constraint = new OrderedChoice("(.,.,.,.,.,.)", checkConstraint, tableUniqueConstraint, tablePrimaryKeyConstraint,
                   referencesConstraint, foreignKeyConstraint, excludeConstraint);

   Sequence tableConstraint = new Sequence("TableConstraint(namedConstraint,constraint)", optNamedConstraint, constraint);

   OrderedChoice tableDef = new OrderedChoice(columnDef, likeSource, tableConstraint, columnWithOptions);

   Sequence tableDefList = new Sequence("([],[])", tableDef, new Sequence("(,[])", OPTIONAL | REPEAT, commaEOL, tableDef));

   Sequence ifNotExists = new Sequence("('','','')", OPTIONAL, ifKeyword, notKeyword, existsKeyword);

   Sequence ifExists = new Sequence("('','')", OPTIONAL, ifKeyword, existsKeyword);

   Sequence tableSpace = new Sequence("(,'')", OPTIONAL, new ICSymbolSpace("tablespace"), identifier);

   Sequence ofType = new Sequence("(,.)", OPTIONAL, ofKeyword, sqlIdentifier);

   Sequence tableInherits = new Sequence("(,.)", OPTIONAL, new ICSymbolSpace("inherits"), sqlIdentifierList);

   Sequence tablePartition = new Sequence("TablePartition(,,partitionBy,,expressionList,)", OPTIONAL,
           new ICSymbolSpace("partition"), byKeyword, new ICSymbolChoiceSpace("range", "list"),
           openParen, sqlExpressionList, closeParen);

   Sequence createTable = new Sequence("CreateTable(tableOptions,,ifNotExists,tableName,ofType,,tableDefs,,tableInherits,tablePartition,storageParams,tableSpace)",
                   new ICSymbolChoiceSpace(REPEAT | OPTIONAL, "global", "local", "temporary", "temp", "unlogged"),
                   tableKeyword, ifNotExists, sqlIdentifier, ofType, openParenEOL, tableDefList, closeParen,
                   tableInherits, tablePartition, storageParameters, tableSpace);


   // TODO: create type 'as enum' and 'as range' and with input/output params
   Sequence createTableType = new Sequence("CreateType(,tableDefs,)", openParenEOL, tableDefList, closeParen);
   Sequence createEnumType = new Sequence("CreateEnum(,,enumDefs,)", enumKeyword, openParen, stringLiteralList, closeParen);
   OrderedChoice createTypeChoice = new OrderedChoice(createTableType, createEnumType);
   Sequence createType = new Sequence("(,typeName,,.)", typeKeyword, sqlIdentifier, asKeyword, createTypeChoice);

   ICSymbolSpace indexKeyword = new ICSymbolSpace("index");

   Sequence indexColIdent = new Sequence("IndexColumn(columnName)", sqlIdentifier);
   Sequence indexColExpr = new Sequence("IndexColumnExpr(expression)", sqlExpression);
   OrderedChoice indexColumnElem = new OrderedChoice("(.,.)", indexColIdent, indexColExpr);
   Sequence indexColumn = new Sequence("(.,collation,opClass,sortDir,nullDir)", indexColumnElem, collation, optIdentifier,
           new ICSymbolChoiceSpace(OPTIONAL, "asc", "desc"),
           new Sequence("(,'')", OPTIONAL, new ICSymbolSpace("nulls"), new ICSymbolChoiceSpace("first", "last")));

   Sequence indexColumnList = new Sequence("([],[])", indexColumn, new Sequence("(,[])", REPEAT | OPTIONAL, commaEOL, indexColumn));

   ICSymbolSpace optConcurrentlyKeyword = new ICSymbolSpace("concurrently", OPTIONAL);
   ICSymbolSpace includeKeyword = new ICSymbolSpace("include");
   ICSymbolSpace sequenceKeyword = new ICSymbolSpace("sequence");

   Sequence optIncludeColumns = new Sequence("(,.)", OPTIONAL, includeKeyword, sqlIdentifierList);

   Sequence createIndex = new Sequence("CreateIndex(unique,,concurrently,indexName,,tableName,usingMethod,,indexColumns,,includeColumns,withOpList,tableSpace,whereClause)",
                                       new ICSymbolSpace("unique", OPTIONAL),
                                       indexKeyword, optConcurrentlyKeyword,
                                       optSqlIdentifier, onKeyword, sqlIdentifier, usingMethod, openParen, indexColumnList, closeParen,
                                       optIncludeColumns, indexParameters, tableSpace, optWhereClause);

   Sequence createSequence = new Sequence("CreateSequence(temporary,,ifNotExists,sequenceName,sequenceOptions)",
                                          new ICSymbolChoiceSpace(OPTIONAL, "temporary", "temp"),
                                          sequenceKeyword, ifNotExists, sqlIdentifier, sequenceOptions);

   Sequence orReplace = new Sequence("('','')", OPTIONAL, new ICSymbolSpace("or"), new ICSymbolSpace("replace"));

   ICKeywordChoice argMode = new ICKeywordChoice(OPTIONAL, "in", "out", "inout", "variadic");

   Sequence argDefault = new Sequence("ArgDefault(op,expr)", OPTIONAL, new ICSymbolChoiceSpace("default", "="), sqlExpression);

   Sequence funcArg = new Sequence("FuncArg(argMode,argName,dataType,argDefault)", argMode, sqlParamType, optSqlIdentifier, argDefault);

   Sequence funcArgList = new Sequence("(,[],[],)", openParen, funcArg, new Sequence("(,[])", OPTIONAL | REPEAT, comma, funcArg), closeParen);

   Sequence returnTable = new Sequence("ReturnTable(,,tableDefs,)", tableKeyword, openParen, tableDefList, closeParen);

   Sequence returnType = new Sequence("ReturnType(setOf,dataType)", new ICSymbolSpace("setof", OPTIONAL), sqlParamType);

   Sequence funcReturn = new Sequence("(,.)", OPTIONAL, new ICSymbolSpace("returns"), new OrderedChoice("(.,.)", returnTable, returnType));

   Sequence funcDef = new Sequence("FuncDef(,funcBody)", asKeyword, sqlStringLiteral);

   Sequence funcLang = new Sequence("FuncLang(,langName)", new ICSymbolSpace("language"), identifier);

   Sequence funcBehaviorType = new Sequence("FuncBehaviorType(typeStr)",
           new ICMultiSymbolChoice("funcBehaviorTypes", 0, "immutable", "stable", "volatile", "leakproof", "not leakproof",
                                   "called on null first input", "returns null on null input", "strict", "security invoker", "security definer",
                                   "external security invoker", "external security definer", "parallel unsafe", "parallel strict", "parallel safe", "window"));

   OrderedChoice funcOptions = new OrderedChoice("([],[],[])", REPEAT, funcDef, funcBehaviorType, funcLang);

   ICKeywordSpace functionKeyword = new ICKeywordSpace("function");

   Sequence createFunction = new Sequence("CreateFunction(orReplace,,funcName,argList,funcReturn,funcOptions)", orReplace, functionKeyword,
                                          sqlIdentifier, funcArgList, funcReturn, funcOptions);

   ICKeywordSpace triggerKeyword = new ICKeywordSpace("trigger");
   ICKeywordSpace orKeyword = new ICKeywordSpace("or");
   ICKeywordSpace forKeyword = new ICKeywordSpace("for");
   ICKeywordSpace whenKeyword = new ICKeywordSpace("when");
   ICKeywordSpace executeKeyword = new ICKeywordSpace("execute");
   ICKeywordSpace procedureKeyword = new ICKeywordSpace("procedure");
   ICKeywordSpace insertKeyword = new ICKeywordSpace("insert");
   ICKeywordSpace updateKeyword = new ICKeywordSpace("update");
   ICKeywordSpace deleteKeyword = new ICKeywordSpace("delete");

   OrderedChoice triggerEvent = new OrderedChoice("(.,.,.,.)", insertKeyword, deleteKeyword, new ICKeywordSpace("truncate"),
                                                  new Sequence("UpdateEvent(,columns)", updateKeyword, new Sequence("(,.)", OPTIONAL, ofKeyword, sqlIdentifierCommaList)));

   Sequence triggerEvents = new Sequence("([],[])", triggerEvent, new Sequence("(,[])", OPTIONAL | REPEAT, orKeyword, triggerEvent));

   Sequence triggerForOpts = new Sequence("TriggerOptions(,each,rowOrStatement)", forKeyword, new ICKeywordSpace("each", OPTIONAL), new ICKeywordChoice("row", "statement"));

   Sequence optWhenExpression = new Sequence("(,.)", OPTIONAL, whenKeyword, sqlExpression);

   Sequence createTrigger = new Sequence(
           "CreateTrigger(constraint,,triggerName,whenOption,triggerEvents,,tableName,triggerForOpts,whenCondition,,,funcCall)",
           optConstraintKeyword, triggerKeyword, sqlIdentifier,
           new ICMultiSymbolChoice("beforeAfterTypes", 0 , "before", "after", "instead of"),
           triggerEvents, onKeyword, sqlIdentifier, triggerForOpts, optWhenExpression, executeKeyword, procedureKeyword, functionCall);

   OrderedChoice createChoice = new OrderedChoice("(.,.,.,.,.,.)", createTable, createType, createIndex, createSequence, createFunction, createTrigger);
   Sequence createCommand = new Sequence("(,.,)", new ICKeywordSpace("create"), createChoice, semicolonEOL2);

   ICSymbolChoiceSpace dropOptions = new ICSymbolChoiceSpace(OPTIONAL, "cascade", "restrict");

   Sequence dropTable = new Sequence("DropTable(,tableNames,dropOptions)", tableKeyword, sqlIdentifierCommaList, dropOptions);
   Sequence dropType = new Sequence("DropType(,typeNames,dropOptions)", typeKeyword, sqlIdentifierCommaList, dropOptions);
   Sequence dropIndex = new Sequence("DropIndex(,concurrently,ifExists,indexNames,dropOptions)", indexKeyword,
                                     optConcurrentlyKeyword, ifExists, sqlIdentifierCommaList, dropOptions);
   Sequence dropFunction = new Sequence("DropFunction(,ifExists,funcNames,dropOptions)", functionKeyword, ifExists,
                                     sqlIdentifierCommaList, dropOptions);
   Sequence dropSequence = new Sequence("DropSequence(,ifExists,seqNames,dropOptions)", sequenceKeyword, ifExists,
                                     sqlIdentifierCommaList, dropOptions);
   Sequence dropTrigger = new Sequence("DropTrigger(,ifExists,triggerName,,tableName,dropOptions)", triggerKeyword, ifExists,
                                      sqlIdentifier, onKeyword, sqlIdentifier, dropOptions);

   OrderedChoice dropChoice = new OrderedChoice("(.,.,.,.,.,.)", dropTable, dropType, dropIndex, dropFunction, dropSequence, dropTrigger);

   Sequence dropCommand = new Sequence("(,.,)", dropKeyword, dropChoice, semicolonEOL2);

   ICSymbolSpace optColumnKeyword = new ICSymbolSpace("column", OPTIONAL);

   Sequence addColumn = new Sequence("AddColumn(,ifNotExists,columnDef)", optColumnKeyword, ifNotExists, columnDef);
   Sequence dropColumn = new Sequence("DropColumn(,ifExists,columnName,dropOptions)", optColumnKeyword, ifExists, sqlIdentifier, dropOptions);

   Sequence addConstraint = new Sequence("AddConstraint(constraint)", tableConstraint);
   Sequence dropConstraint = new Sequence("DropConstraint(constraint)", namedConstraint);

   Sequence alterSetType = new Sequence("AlterSetType(,,columnType,collation,usingExpression)",
                                        new Sequence(OPTIONAL, setKeyword, new ICSymbolSpace("data")),
                                        typeKeyword, sqlDataType, collation, usingExpression);

   Sequence alterSetDefault = new Sequence("AlterSetDefault(,,expression)", setKeyword, defaultKeyword, sqlExpression);
   Sequence alterDropDefault = new Sequence("AlterDropDefault(,)", dropKeyword, defaultKeyword);
   Sequence alterUpdateNotNull = new Sequence("AlterUpdateNotNull(op,)", new ICSymbolChoiceSpace("set", "drop"), new Sequence(notKeyword, nullKeyword));

   OrderedChoice alterCmd = new OrderedChoice("(.,.,.,.)", alterSetType, alterSetDefault, alterDropDefault, alterUpdateNotNull);

   Sequence alterColumn = new Sequence("AlterColumn(,,columnName,alterCmd)", new ICSymbolSpace("alter"), optColumnKeyword, sqlIdentifier, alterCmd);

   ICSymbolSpace renameKeyword = new ICSymbolSpace("rename");
   ICSymbolSpace toKeyword = new ICSymbolSpace("to");

   Sequence renameColumn = new Sequence("RenameColumn(,,oldColumnName,,newColumnName)", renameKeyword, optColumnKeyword, sqlIdentifier, toKeyword, sqlIdentifier);
   Sequence renameTable = new Sequence("RenameTable(,,newTableName)", renameKeyword, toKeyword, sqlIdentifier);

   Sequence alterAdd = new Sequence("(,.)", addKeyword, new OrderedChoice("(.,.)", addColumn, addConstraint));
   Sequence alterDrop = new Sequence("(,.)", dropKeyword, new OrderedChoice("(.,.)", dropColumn, dropConstraint));

   OrderedChoice alterDef = new OrderedChoice("(.,.,.,.,.)", alterAdd, alterDrop, alterColumn, renameColumn, renameTable);

   Sequence alterDefs = new Sequence("([],[])", alterDef, new Sequence("(,[])", REPEAT | OPTIONAL, commaEOL, alterDef));

   Sequence alterTable = new Sequence("AlterTable(,ifExists,only,tableName,alterDefs)",
           tableKeyword, ifExists, new ICSymbolSpace("only", OPTIONAL), sqlIdentifier, alterDefs);

   OrderedChoice alterChoice = new OrderedChoice("(.)", alterTable);
   Sequence alterCommand = new Sequence("(,.,)", new ICSymbolSpace("alter"), alterChoice, semicolonEOL);

   public OrderedChoice sqlCommands = new OrderedChoice( "([],[],[])", REPEAT | OPTIONAL, createCommand, alterCommand, dropCommand);

   public Sequence sqlFileModel = new Sequence("SQLFileModel(,sqlCommands,)", spacing, sqlCommands, new Symbol(EOF));

   {
      // For when we convert scsql to java, need to update the result class name so it matches. This corresponds to the
      // code in SQLFileModel.getTransformedResult
      compilationUnit.setResultClassName("SQLFileModel");
   }

   class DollarTag extends Sequence {
      DollarTagType tagType;
      DollarTag(DollarTagType tagType, int options) {
         super("(,'',)", options, dollarSymbol, dollarTagId, dollarSymbol);
         this.tagType = tagType;
      }

      public Object parse(Parser parser) {
         return super.parse(parser);
      }

      protected String accept(SemanticContext ctx, Object value, int startIx, int endIx) {
         String res = super.accept(ctx, value, startIx, endIx);
         if (res != null)
            return res;

         ParentParseNode pn = (ParentParseNode) value;
         Object tagValue = pn == null || pn.children == null ? null : pn.children.get(1);

         String strValue = tagValue == null ? "" : tagValue.toString();

         TagStackSemanticContext sctx = (TagStackSemanticContext) ctx;

         switch (tagType) {
            case Start:
               if (startIx != -1)
                  sctx.addEntry(strValue, startIx, endIx);
               break;
            case End:
               // Currently not doing the tag name stack during generate
               if (startIx == -1)
                  return null;

               TagStackSemanticContext hctx = ((TagStackSemanticContext) ctx);
               if (hctx.allowAnyCloseTag) // In diagnostic mode - need to just parse the close tag for an error
                  return null;
               String dollarIdent = hctx.getCurrentTagName();
               if (dollarIdent == null)
                  dollarIdent = ""; // $$
               if (!dollarIdent.equals(strValue))
                  return "Mismatching $ident$ name: " + value + " does not match open: " + dollarIdent;
               hctx.popTagName(startIx);
               break;
         }
         return null;
      }
   }

   class DollarTagBody extends Sequence {
      DollarTagBody() {
         super(NOT | LOOKAHEAD, dollarTagId, dollarSymbol);
      }

      public Object parse(Parser parser) {
         return super.parse(parser);
      }

      protected String accept(SemanticContext ctx, Object value, int startIx, int endIx) {
         String res = super.accept(ctx, value, startIx, endIx);
         if (res != null)
            return res;

         ParentParseNode pn = (ParentParseNode) value;
         Object tagValue = pn.children.get(0);

         // This will have the $ appended even though it's a different parselet because of the optimization so more logic to do the comparison below
         String strValue = tagValue == null ? "" : tagValue.toString();

         TagStackSemanticContext sctx = (TagStackSemanticContext) ctx;

          String dollarIdent = sctx.getCurrentTagName();
          if (dollarIdent.length() == 0) {
             if (strValue.length() != 0)
                return "Mismatching empty $ident$ ";
             return null;
          }
          if (!strValue.startsWith(dollarIdent) || !strValue.endsWith("$") || strValue.length() -1 != dollarIdent.length())
             return "Mismatching $ident$ name: " + value + " does not match open: " + dollarIdent;
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
