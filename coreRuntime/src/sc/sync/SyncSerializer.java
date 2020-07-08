/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.sync;

import sc.bind.Bind;
import sc.bind.BindingContext;
import sc.dyn.DynUtil;
import sc.obj.Sync;
import sc.obj.SyncMode;
import sc.type.CTypeUtil;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static sc.sync.SyncLayer.GLOBAL_TYPE_NAME;

/**
 * Manages the serialization of a sync layer, converting it into a format that can be transferred and applied on the other side of a client/server connection
 */
@sc.js.JSSettings(jsModuleFile="js/sync.js", prefixAlias="sc_")
@Sync(syncMode= SyncMode.Disabled)
public class SyncSerializer {
   public StringBuilder sb;
   public SerializerFormat format;

   public SyncManager syncManager;
   SyncDestination dest;

   public SyncSerializer(SerializerFormat format, SyncManager mgr) {
      this.format = format;
      syncManager = mgr;
      sb = new StringBuilder();
      this.dest = mgr.syncDestination;
   }

   public void append(char c) {
      sb.append(c);
   }

   public void append(CharSequence seq) {
      sb.append(seq);
   }

   /**
    * Creates a serializer you can use to create chunks of serialized output of the same format.
    * Useful to help sequence the order of nested and dependent items during the code generation
    */
   public SyncSerializer createTempSerializer(boolean fragment, int indent) {
      return format.createSerializer(syncManager);
   }

   public void setIndent(int indent) {
   }

   public void appendSerializer(SyncSerializer sub) {
      sb.append(sub.sb);
   }

   public void appendEvalSC(CharSequence code, int indent) {
      sb.append(code);
   }

   public StringBuilder getDebugOutput() {
      return sb;
   }

   public StringBuilder getOutput() {
      if (sb.length() > 0)
         return dest.translateSyncLayer(sb.toString());
      return sb;
   }

   public void pushCurrentObject(String objName, int i) {
      if (i > 0)
         sb.append(Bind.indent(i));
      sb.append(objName);
      sb.append(" {\n");
   }

   public void popCurrentObject(int ix) {
      if (ix > 0)
         sb.append(Bind.indent(ix));
      sb.append("}\n");
   }


   public void changePackage(String newPkg) {
      sb.append("package ");
      if (newPkg != null)
         sb.append(newPkg);
      sb.append(";\n\n");
   }

   public NewObjResult appendNewObj(Object obj, String objName, String objTypeName, Object[] newArgs, ArrayList<String> newObjNames, String newLastPackageName,
                                    SyncHandler syncHandler, SyncManager.SyncContext parentContext, SyncLayer syncLayer, ArrayList<SyncLayer.SyncChange> depChanges,
                                    boolean isPropChange) {
      NewObjResult newObjRes = null;

      int indentSize = newObjNames.size();
      sb.append(Bind.indent(indentSize));
      if (newArgs == null || newArgs.length == 0) {
         sb.append("object ");
         String objBaseName = CTypeUtil.getClassName(objName);
         sb.append(objBaseName);
         newObjNames.add(objName);
         sb.append(" extends ");
         sb.append(objTypeName);
         sb.append(" {\n");
         newObjRes = new NewObjResult();
         newObjRes.newSBPushed = true;
      }
      else {
         // When there are parameters passed to the addSyncInst call, those are the constructor parameters.
         // This means we can't use the object tag... instead we define a field with the objName in the context
         // of the parent object (so that's accessible when we call the new).   For top-level new X calls we need
         // to make them static since they are not using the outer object.
         if (DynUtil.getNumInnerObjectLevels(obj) == 0)
            sb.append("static ");
         sb.append(objTypeName);
         sb.append(" ");
         String newVarName = CTypeUtil.getClassName(syncHandler.getObjectBaseName(depChanges, syncLayer));
         sb.append(newVarName);
         sb.append(" = new ");
         sb.append(objTypeName);
         sb.append("(");
         boolean first = true;
         SyncSerializer preBlockCode = createTempSerializer(false, indentSize);
         SyncSerializer postBlockCode = createTempSerializer(false, indentSize);
         for (Object newArg:newArgs) {
            if (!first)
               sb.append(", ");
            else
               first = false;
            parentContext.formatExpression(this, sb, newArg, newObjNames, newLastPackageName, preBlockCode, postBlockCode, newVarName, false, "", depChanges, syncLayer);
         }
         // For property types like ArrayList and Map which have to execute statements at the block level to support their value.
         sb.append(postBlockCode);
         sb.append(");\n");

         if (preBlockCode.sb.length() > 0) {
            SyncSerializer preBuffer = createTempSerializer(false, indentSize);
            preBuffer.appendSerializer(preBlockCode);
            preBuffer.appendSerializer(this);
            newObjRes = new NewObjResult();
            newObjRes.newSB = preBuffer;
         }

         if (isPropChange) {
            sb.append(Bind.indent(indentSize));
            sb.append(newVarName);
            sb.append(" {\n");
            if (newObjRes == null)
               newObjRes = new NewObjResult();
            newObjRes.startObjNames = (ArrayList<String>) newObjNames.clone();
            newObjNames.add(newVarName);
         }
      }
      return newObjRes;
   }

   public void appendProp(Object changedObj, String propName, Object propValue, ArrayList<String> newObjNames, String newLastPackageName, SyncManager.SyncContext parentContext, SyncLayer syncLayer, ArrayList<SyncLayer.SyncChange> depChanges) {
      int indentSize = newObjNames.size();
      SyncSerializer preBlockCode = createTempSerializer(false, indentSize);
      SyncSerializer postBlockCode = createTempSerializer(false, indentSize);
      SyncSerializer statement = createTempSerializer(false, indentSize);

      statement.sb.append(Bind.indent(indentSize));

      if (propValue != null) {
         SyncHandler valSyncHandler = parentContext.getSyncHandler(propValue);
         valSyncHandler.appendPropertyUpdateCode(statement, changedObj, propName, propValue, parentContext.getPreviousValue(changedObj, propName), newObjNames, newLastPackageName, preBlockCode, postBlockCode, depChanges, syncLayer);
      }
      else {
         statement.sb.append(propName);
         statement.sb.append(" = null;\n");
      }
      statement.sb.append(";\n");

      sb.append(preBlockCode);
      appendSerializer(statement);
      sb.append(postBlockCode);
   }

   public void formatNullValue(StringBuilder sb) {
      sb.append("null");
   }

   public void appendNullValue() {
      formatNullValue(sb);
   }

   public void appendPropertyAssignment(SyncManager.SyncContext syncContext, Object changedObj, String propName, Object propValue, Object previousValue, ArrayList<String> currentObjNames, String currentPackageName, SyncSerializer preBlockCode, SyncSerializer postBlockCode, List<SyncLayer.SyncChange> depChanges, SyncLayer syncLayer) {
      try {
         sb.append(propName);
         sb.append(" = ");
         syncContext.formatExpression(this, sb, propValue, currentObjNames, currentPackageName, preBlockCode, postBlockCode, propName, false, "", depChanges, syncLayer);
      }
      catch (UnsupportedOperationException exc) {
         System.err.println("*** Error serializing property: " + propName);
         exc.printStackTrace();
      }
      catch (RuntimeException exc) {
         System.err.println("*** Runtime error in expressionToString for: " + propName);
         exc.printStackTrace();
         throw exc;
      }

   }

   public void formatReference(StringBuilder out, String objName, String currentPackageName) {
      if (objName == null)
         formatNullValue(out);
      else if (currentPackageName != null && objName.startsWith(currentPackageName) && currentPackageName.length() > 0) {
         out.append(objName.substring(currentPackageName.length() + 1));
      }
      else
         out.append(objName);
   }

   public void formatNewArrayDef(StringBuilder out, SyncManager.SyncContext syncContext, Object changedObj, String typeName, ArrayList<String> currentObjNames, String currentPackageName,
                                 SyncSerializer preBlockCode, SyncSerializer postBlockCode, boolean inBlock, String uniqueId, List<SyncLayer.SyncChange> depChanges, SyncLayer syncLayer) {
      int sz = DynUtil.getArrayLength(changedObj);
      int numLevels = currentObjNames.size();

      // During serialization, we encountered a reference to an array value that's not yet defined on the remote side so we need to
      // define it's value, and can't refer to it by name.  We'll define the array value in a statement that's evaluated just before
      // this expression which is the best we can do.
      // TODO: It's possible side-effects in the parts of the expression could make this not accurate.  Not sure why this is not done by just
      // having a DynUtil.createArray(typeName, val1, val2, val3) method call?
      StringBuilder preBlock = preBlockCode.sb;
      preBlock.append(Bind.indent(numLevels));
      preBlock.append(typeName);
      preBlock.append(" ");
      preBlock.append("_lt");
      preBlock.append(uniqueId);
      preBlock.append(" = ");
      preBlock.append("new ");
      preBlock.append(typeName);
      preBlock.append("();\n");
      if (!inBlock) {
         preBlock.append(Bind.indent(numLevels));
         preBlock.append("{\n");
      }
      int numCodeLevels = numLevels + 1;

      for (int i = 0; i < sz; i++) {
         Object val = DynUtil.getArrayElement(changedObj, i);
         preBlock.append(Bind.indent(numCodeLevels));
         preBlock.append("_lt");
         preBlock.append(uniqueId);
         preBlock.append(".add(");
         syncContext.formatExpression(preBlockCode, preBlock, val, currentObjNames, currentPackageName, preBlockCode, postBlockCode, null, true, uniqueId + "_" + i, depChanges, syncLayer);
         preBlock.append(");\n");
      }
      if (!inBlock) {
         preBlock.append(Bind.indent(numLevels));
         preBlock.append("}\n");
      }

      sb.append("_lt");
      sb.append(uniqueId);
   }

   public void formatArrayExpression(StringBuilder out, SyncManager.SyncContext syncContext, Object changedObj, ArrayList<String> currentObjNames, String currentPackageName, SyncSerializer preBlockCode,
                                     SyncSerializer postBlockCode, String varName, boolean inBlock, String uniqueId, List<SyncLayer.SyncChange> depChanges, SyncLayer syncLayer) {
      int sz = DynUtil.getArrayLength(changedObj);
      out.append("{");
      for (int i = 0; i < sz; i++) {
         Object val = DynUtil.getArrayElement(changedObj, i);
         if (i != 0)
            out.append(", ");
         syncContext.formatExpression(this, out, val, currentObjNames, currentPackageName, preBlockCode, postBlockCode, varName, inBlock, uniqueId + "_" + i, depChanges, syncLayer);
      }
      out.append("}");
   }

   public void appendMethodCall(SyncManager.SyncContext syncContext, SyncLayer.SyncMethodCall smc, ArrayList<String> newObjNames, String newLastPackageName, ArrayList<SyncLayer.SyncChange> depChanges, SyncLayer syncLayer) {
      int indentSize = newObjNames.size();
      SyncSerializer preBlockCode = createTempSerializer(false, indentSize);
      SyncSerializer postBlockCode = createTempSerializer(false, indentSize);
      SyncSerializer statement = createTempSerializer(false, indentSize);

      StringBuilder statementSB = statement.sb;

      statementSB.append(Bind.indent(indentSize));

      statementSB.append("Object ");
      statementSB.append(smc.callId);
      statementSB.append(" = ");
      statementSB.append(smc.methName);
      statementSB.append("(");
      if (smc.args != null) {
         int argIx = 0;
         for (Object arg:smc.args) {
            if (argIx > 0)
               statementSB.append(", ");
            syncContext.formatExpression(statement, statementSB, arg, newObjNames, newLastPackageName, preBlockCode, postBlockCode, null, false, String.valueOf(argIx), depChanges, syncLayer);
            argIx++;
         }
      }
      statementSB.append(");\n");
      /*
      if (smc.returnType == null) { // void type method - the JS method will not return anything so we can't use that as the result.  ModelStream does not allow just an expression so there's some duplication here
         statementSB.append(Bind.indent(indentSize));
         statementSB.append("Object ");
         statementSB.append(smc.callId);
         statementSB.append(" = ");
         statementSB.append("null;");
      }
      */

      sb.append(preBlockCode);
      appendSerializer(statement);
      sb.append(postBlockCode);
   }

   public void appendMethodResult(SyncManager.SyncContext parentContext, SyncLayer.SyncMethodResult mres, ArrayList<String> newObjNames, String newLastPackageName, ArrayList<SyncLayer.SyncChange> depChanges, SyncLayer syncLayer) {
      int indentSize = newObjNames.size();

      SyncSerializer preBlockCode = createTempSerializer(false, indentSize);
      SyncSerializer postBlockCode = createTempSerializer(false, indentSize);
      SyncSerializer statement = createTempSerializer(false, indentSize);
      StringBuilder statementSB = statement.sb;

      statementSB.append(Bind.indent(indentSize + 1));
      statementSB.append("sc.sync.SyncManager.processMethodReturn(");
      statementSB.append("null, ");
      statementSB.append("\"");
      statementSB.append(CTypeUtil.escapeJavaString(mres.callId, '"', false));
      statementSB.append("\", ");
      parentContext.formatExpression(statement, statementSB, mres.retValue, newObjNames, newLastPackageName, preBlockCode, postBlockCode, null, true, "", depChanges, syncLayer);
      statementSB.append(", ");
      if (mres.exceptionStr == null)
         statementSB.append("null");
      else {
         statementSB.append("\"");
         statementSB.append(CTypeUtil.escapeJavaString(mres.exceptionStr, '"', false));
         statementSB.append("\"");
      }
      statementSB.append(");\n");

      sb.append(Bind.indent(indentSize));
      sb.append("{\n");
      sb.append(preBlockCode);
      appendSerializer(statement);
      sb.append(postBlockCode);
      sb.append(Bind.indent(indentSize));
      sb.append("}\n");
   }

   public String toString() {
      return sb.toString();
   }

   public void appendFetchProperty(String propName, int indentSize) {
      sb.append(Bind.indent(indentSize));
      // TODO: should we set the @sc.obj.Sync annotation here or is this the only reason we'd ever use override is to fetch?
      sb.append("override ");
      sb.append(propName);
      sb.append(";\n");
   }

   public void formatMap(StringBuilder out, SyncManager.SyncContext syncContext, Map changedMap, String typeName, ArrayList<String> currentObjNames, String currentPackageName, SyncSerializer preBlockCode, SyncSerializer postBlockCode,
                         String varName, boolean inBlock, String uniqueId, List<SyncLayer.SyncChange> depChanges, SyncLayer syncLayer) {
      int numLevels = currentObjNames.size();
      out.append("new ");
      out.append(typeName);
      out.append("();\n");
      if (changedMap.size() > 0) {
         if (!inBlock) {
            postBlockCode.sb.append(Bind.indent(numLevels));
            postBlockCode.sb.append("{\n");
         }
         int ct = 0;

         StringBuilder mb = new StringBuilder();
         for (Object mapEntObj: changedMap.entrySet()) {
            SyncSerializer mapPreBlockCode = createTempSerializer(false, numLevels + 1);
            SyncSerializer mapPostBlockCode = createTempSerializer(false, numLevels + 1);
            Map.Entry mapEnt = (Map.Entry) mapEntObj;
            SyncSerializer newExpr = createTempSerializer(false, numLevels+1);
            newExpr.sb.append(Bind.indent(numLevels + 1));
            // If we are the initializer for a variable, just use that name.
            if (varName != null)
               newExpr.sb.append(varName);
               // Otherwise, this object needs to have a global name we can use to refer to it with.
            else {
               String objName = syncContext.getObjectName(changedMap, null, true, true, depChanges, syncLayer);
               if (objName != null)
                  newExpr.sb.append(objName);
            }
            String subUniqueId = uniqueId + "_" + ct;
            newExpr.sb.append(".put(");
            syncContext.formatExpression(newExpr, newExpr.sb, mapEnt.getKey(), currentObjNames, currentPackageName, mapPreBlockCode, mapPostBlockCode, null, true, subUniqueId, depChanges, syncLayer);
            newExpr.sb.append(", ");

            syncContext.formatExpression(newExpr, newExpr.sb, mapEnt.getValue(), currentObjNames, currentPackageName, mapPreBlockCode, mapPostBlockCode, null, true, subUniqueId, depChanges, syncLayer);
            newExpr.sb.append(");\n");

            mb.append(mapPreBlockCode);
            mb.append(newExpr);
            mb.append(mapPostBlockCode);
            ct++;
         }

         postBlockCode.sb.append(mb);
         if (!inBlock) {
            postBlockCode.sb.append(Bind.indent(numLevels));
            postBlockCode.sb.append("}\n");
         }
      }
   }

   public void formatBoolean(StringBuilder sb, boolean val) {
      sb.append(val ? "true" : "false");
   }

   public void formatByte(StringBuilder sb, Byte val) {
      sb.append(val.toString());
   }

   public void formatShort(StringBuilder sb, Short val) {
      sb.append(val.toString());
   }

   public void formatInt(StringBuilder sb, Integer val) {
      sb.append(val.toString());
   }

   public void formatLong(StringBuilder sb, Long val) {
      sb.append(val.toString());
      sb.append('l');
   }

   public void formatFloat(StringBuilder sb, Float val) {
      sb.append(val.toString());
      sb.append('f');
   }

   // Here for javascript
   public void formatNumber(StringBuilder sb, Number val) {
      sb.append(val.toString());
   }

   public void formatDate(StringBuilder sb, Date val) {
      sb.append('"');
      sb.append(DynUtil.formatDate(val));
      sb.append('"');
   }

   public void formatDouble(StringBuilder sb, Double val) {
      sb.append(val.toString());
      sb.append('d');
   }

   public void formatDefault(StringBuilder sb, Object val) {
      String str = DynUtil.getTypeName(DynUtil.getType(val), true) + ": " + val.toString();
      sb.append('"');
      sb.append(str);
      sb.append('"');
   }

   public void formatChar(StringBuilder sb, String val) {
      sb.append("'");
      sb.append(CTypeUtil.escapeJavaString(val, '\'', false));
      sb.append("'");
   }

   public void formatString(StringBuilder sb, String val) {
      sb.append('"');
      sb.append(CTypeUtil.escapeJavaString(val, '"', false));
      sb.append('"');
   }

   void appendRemoteChanges(boolean remoteChange, boolean topLevel, int indent) {
      StringBuilder sb = new StringBuilder();
      if (topLevel) {
         sb.append(GLOBAL_TYPE_NAME);
         sb.append(" { ");
      }
      // TODO: import these things to make the strings smaller
      sb.append("{ sc.sync.SyncManager.setSyncState(");
      if (remoteChange)
         sb.append("sc.sync.SyncManager.SyncState.InitializingLocal");
      else
         sb.append("sc.sync.SyncManager.SyncState.Initializing");
      sb.append("); }");
      if (topLevel)
         sb.append("}");
      sb.append("\n");
      appendEvalSC(sb, indent);
   }

   public boolean needsObjectForTopLevelNew() {
      return true;
   }
}
