/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.sql;

import sc.db.DBTypeDescriptor;
import sc.db.DBUtil;
import sc.db.NamedQueryDescriptor;
import sc.lang.ISemanticNode;
import sc.lang.SemanticNodeList;
import sc.layer.LayeredSystem;
import sc.type.CTypeUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class CreateTrigger extends SQLCommand {
   public boolean constraint;
   public SQLIdentifier triggerName;
   public String whenOption; // before, after, instead of
   // keyword: insert, delete, truncate or an UpdateEvent 
   public List<Object> triggerEvents; 
   public SQLIdentifier tableName;
   public TriggerOptions triggerForOpts;
   public SQLExpression whenCondition;
   public FunctionCall funcCall;

   void addTableReferences(Set<String> refTableNames) {
      refTableNames.add(tableName.getIdentifier());
   }

   public boolean hasReferenceTo(SQLCommand cmd) {
      return false;
   }

   public String toDeclarationString() {
      return "trigger " + triggerName + " on " + tableName;
   }

   public SQLCommand getDropCommand() {
      DropTrigger dt = new DropTrigger();
      dt.triggerName = triggerName;
      dt.tableName = tableName;
      return dt;
   }

   public String getIdentifier() {
      // TODO: should this be tableName + identifier since triggers in pgsql 
      // are local to tables?
      return triggerName.getIdentifier();
   }
}
