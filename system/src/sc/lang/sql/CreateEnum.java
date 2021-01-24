/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

import sc.db.ColumnInfo;
import sc.db.DBUtil;
import sc.db.TableInfo;
import sc.dyn.DynUtil;
import sc.lang.ISemanticNode;
import sc.lang.SemanticNode;
import sc.lang.SemanticNodeList;
import sc.parser.IString;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class CreateEnum extends BaseCreateType {
   public List<QuotedStringLiteral> enumDefs;

   public String toDeclarationString() {
      return "enum " + typeName;
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("CREATE TYPE ");
      sb.append(typeName);
      sb.append(" AS ENUM (");
      if (enumDefs != null) {
         boolean first = true;
         for (QuotedStringLiteral ec:enumDefs) {
            if (!first)
               sb.append(", ");
            else
               first = false;
            sb.append(ec);
         }
      }
      sb.append(")");
      return sb.toString();
   }
}
