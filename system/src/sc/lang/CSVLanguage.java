/*
 * Copyright (c) 2020. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang;

import sc.layer.Layer;
import sc.parser.*;

/** 
  */
public class CSVLanguage extends BaseLanguage {
   public final static CSVLanguage INSTANCE = new CSVLanguage();

   public CSVLanguage() {
      this(null);
   }

   public CSVLanguage(Layer layer) {
      super(layer);
      setStartParselet(csvFileModel);
      addToSemanticValueClassPath("sc.lang.csv");
      languageName = "SCCsv";
      defaultExtension = "sccsv";
   }

   public static CSVLanguage getCSVLanguage() {
      INSTANCE.initialize();
      return INSTANCE;
   }

   public SymbolChoice csvField = new SymbolChoice(NOT | REPEAT, ",", "\r\n", "\n", "\r", EOF);
   public SymbolChoice csvSeparator = new SymbolChoice(",");
   public Sequence middleField = new Sequence("MiddleField(fieldValue,)", OPTIONAL | REPEAT, csvField, csvSeparator);
   public Sequence lastField = new Sequence("LastField(fieldValue,)", csvField, lineTerminator);
   public Sequence rowFieldList = new Sequence("([],[])", middleField, lastField);

   public Sequence csvRows = new Sequence("CSVRow(fields)", REPEAT, rowFieldList);
   Symbol optEOF = new Symbol(OPTIONAL, EOF);
   public Sequence csvFileModel = new Sequence("CSVFileModel(rows,)", csvRows, optEOF);
}
