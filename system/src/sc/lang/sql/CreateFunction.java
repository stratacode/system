/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

import sc.db.DBTypeDescriptor;
import sc.db.DBUtil;
import sc.db.NamedQueryDescriptor;
import sc.lang.ISemanticNode;
import sc.lang.SemanticNodeList;
import sc.lang.sql.funcOpt.FuncOpt;
import sc.lang.sql.funcOpt.FuncReturn;
import sc.lang.sql.funcOpt.ReturnTable;
import sc.lang.sql.funcOpt.ReturnType;
import sc.layer.LayeredSystem;
import sc.type.CTypeUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class CreateFunction extends SQLCommand {
   public boolean orReplace;
   public SemanticNodeList<FuncArg> argList;
   public SQLIdentifier funcName;
   public FuncReturn funcReturn;
   public SemanticNodeList<FuncOpt> funcOptions;

   void addTableReferences(Set<String> refTableNames) {
      // TODO: look for references in the return type and parameter list
      String tableName = getRetTableName();
      if (tableName != null)
         refTableNames.add(tableName);
   }

   public boolean hasReferenceTo(SQLCommand cmd) {
      if (!(cmd instanceof CreateTable))
         return false;
      return true;
      // TODO: also would look need to look for references in the parameter list and funcOps[..].FuncDef.funcBody property
      // tht we don't parse. For now, assume that all create functions go after all create tables and it will be good.
      /*
      String retTableName = getRetTableName();
      if (retTableName != null) {
         CreateTable ct = (CreateTable) cmd;
         String createTableName = ct.tableName.getIdentifier();
         if (createTableName.equals(retTableName))
            return true;
      }
      return false;
      */
   }

   private String getRetTableName() {
      if (funcReturn instanceof ReturnType) {
         ReturnType retType = (ReturnType) funcReturn;
         if (retType.dataType instanceof QualifiedIdentifier) {
            return ((QualifiedIdentifier) retType.dataType).getIdentifier();
         }
      }
      return null;
   }

   public String toDeclarationString() {
      return "function " + funcName;
   }

   public SQLCommand getDropCommand() {
      DropFunction dt = new DropFunction();
      dt.funcNames = new SemanticNodeList<SQLIdentifier>();
      dt.funcNames.add((SQLIdentifier) funcName.deepCopy(ISemanticNode.CopyNormal, null));
      return dt;
   }

   public String getIdentifier() {
      return funcName.getIdentifier();
   }

   public NamedQueryDescriptor convertToNamedQuery(LayeredSystem sys) {
      int numArgs = argList == null ? 0 : argList.size();
      List<String> paramNames = new ArrayList<String>(numArgs);
      List<String> paramDBTypeNames= new ArrayList<String>(numArgs);
      for (int i = 0; i < numArgs; i++) {
         FuncArg arg = argList.get(i);
         if (arg.dataType != null) {
            String sqlTypeName = arg.dataType.getIdentifier();
            paramDBTypeNames.add(sqlTypeName);
         }
         paramNames.add(arg.argName == null ? null : arg.argName.getIdentifier());
      }
      String retTypeName;
      String retDBTypeName;
      boolean multiRow;
      if (funcReturn == null) {
         retTypeName = retDBTypeName = null;
         multiRow = false;
      }
      else if (funcReturn instanceof ReturnTable) {
         retTypeName = "java.util.HashMap";
         retDBTypeName = null;
         multiRow = true;
      }
      else {
         ReturnType retType = (ReturnType) funcReturn;
         retDBTypeName = retType.dataType.getIdentifier();
         if (retDBTypeName != null) {
            retTypeName = SQLUtil.convertSQLToJavaTypeName(sys, retDBTypeName);
         }
         else
            retTypeName = null;
         multiRow = retType.setOf;
      }
      String sqlQueryName = funcName.getIdentifier();
      return new NamedQueryDescriptor(CTypeUtil.decapitalizePropertyName(DBUtil.getJavaName(sqlQueryName)), sqlQueryName,
                                       paramNames, paramDBTypeNames, multiRow, funcReturn == null ? null : retTypeName, retDBTypeName);
   }
}
