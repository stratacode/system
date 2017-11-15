/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.sync;

import sc.bind.Bind;
import sc.dyn.DynUtil;
import sc.obj.Sync;
import sc.obj.SyncMode;
import sc.type.CTypeUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import sc.sync.JSONFormat.Commands;
import sc.sync.JSONFormat.ExprPrefixes;
import sc.sync.JSONFormat.MethodArgs;

@sc.js.JSSettings(jsModuleFile="js/sync.js", prefixAlias="sc_")
@Sync(syncMode= SyncMode.Disabled)
public class JSONSerializer extends SyncSerializer {
   private boolean fragment = false;  // Is this an internal fragment serializer or a top-level one which stores complete commands
   private boolean skipPreComma = false; // When concatenating this serializer, don't insert a comma separator

   // The top-level command options - what are the basic types of updates
   enum RootCommands {
      sync
   }

   public JSONSerializer(SerializerFormat format, SyncManager mgr) {
      super(format, mgr);
   }

   private boolean isFirst = true;
   private int indent = 1;
   private final static int baseIndent = 1;

   private void appendCommaSep() {
      if (!isFirst)
         append(",");
      isFirst = false;
   }

   private void appendIndent(int indent) {
      sb.append('\n');
      sb.append(Bind.indent(indent+baseIndent));
   }

   private void appendObjStart(int indent) {
      appendCommaSep();
      appendIndent(indent);
      append("{");
      isFirst = true;
   }

   private void appendObjEnd() {
      append("}");
      isFirst = false;
   }

   private void appendCommandStart(Commands name, CharSequence value, int ix) {
      appendNameStart(name.cmd, ix);
      if (value != null)
         appendString(value.toString());
      else
         appendNullValue();
   }

   private void appendSimpleCommand(Commands name, CharSequence value, int ix) {
      appendCommandStart(name, value, ix);
      if (ix == 0)
         appendObjEnd();
   }

   private void appendNameIndent(String name, int indent) {
      appendCommaSep();
      appendIndent(indent);
      formatString(sb, name);
      append(':');
   }

   // Used for names which have normal indenting rules
   private void appendName(String name) {
      appendCommaSep();
      append(' ');
      formatString(sb, name);
      append(':');
   }

   // Use this for expressions which have embedded commands
   private void formatName(StringBuilder sb, String name, boolean first) {
      if (!first)
         sb.append(", ");
      formatString(sb, name);
      sb.append(':');
   }

   private void formatJSONArray(StringBuilder sb, CharSequence arrayVal) {
      sb.append("[ ");
      sb.append(arrayVal);
      sb.append(" ]");
   }

   public void appendEvalSC(CharSequence code, int ix) {
      appendSimpleCommand(Commands.eval, code, ix);
   }

   public StringBuilder getDebugOutput() {
      return sb;
   }

   public StringBuilder getOutput() {
      if (sb.length() > 0) {
         StringBuilder resSB = new StringBuilder();
         resSB.append("{");
         formatString(resSB, RootCommands.sync.toString());
         resSB.append(":[");
         resSB.append(sb);
         resSB.append("\n]}\n");
         return resSB;
      }
      return sb;
   }

   public void changePackage(String newPkg) {
      appendSimpleCommand(Commands.pkg, newPkg == null ? "" : newPkg, 0);
   }

   private void appendNameStart(String name, int ix) {
      if (ix == 0) {
         appendObjStart(ix); // For top level objects, we do { "objName": { ... }} but for objects inside of other objects it's just "objName" : { .... }
         appendName(name);
      }
      else {
         appendNameIndent(name, ix);
      }
   }

   public void pushCurrentObject(String objName, int ix) {
      appendNameStart(objName, ix);
      append("{");
      isFirst = true;
      indent++;
   }

   public void popCurrentObject(int ix) {
      // If the first thing in this serializer is a close tag, to concat it with another serializer, don't add the extra ,
      if (sb.length() == 0)
         skipPreComma = true;
      sb.append("\n");
      sb.append(Bind.indent(ix+1));
      sb.append("}");
      if (ix == 0)
         appendObjEnd();
      indent--;
      isFirst = false;
   }

   public NewObjResult appendNewObj(Object obj, String objName, String objTypeName, Object[] newArgs, ArrayList<String> newObjNames, String newLastPackageName,
                                    SyncHandler syncHandler, SyncManager.SyncContext parentContext, SyncLayer syncLayer, ArrayList<SyncLayer.SyncChange> depChanges,
                                    boolean isPropChange) {
      NewObjResult newObjRes = null;

      String newVarName = CTypeUtil.getClassName(syncHandler.getObjectBaseName(depChanges, syncLayer));

      StringBuilder newArgsJson = null;
      if (newArgs != null && newArgs.length > 0) {
         newArgsJson = new StringBuilder();
         boolean first = true;
         for (Object newArg : newArgs) {
            if (!first)
               newArgsJson.append(", ");
            else
               first = false;
            parentContext.formatExpression(this, newArgsJson, newArg, newObjNames, newLastPackageName, null, null, newVarName, false, "", depChanges, syncLayer);
         }
      }

      // { "obj": name, "ext": objTypeName, "sub": {  ... } }
      //appendCommandStart(Commands.newCmd, newVarName, newObjNames.size());
      int indent = newObjNames.size();
      appendNameStart(Commands.newCmd.cmd, indent);
      append('[');
      appendString(newVarName);
      append(',');
      appendString(objTypeName);
      append(',');
      if (newArgsJson != null) {
         formatJSONArray(sb, newArgsJson);
      }
      else
         appendNullValue();
      append(']');
      if (indent == 0)
         appendObjEnd();

      if (isPropChange) {
         pushCurrentObject(newVarName, newObjNames.size());
         newObjRes = new NewObjResult();
         newObjRes.startObjNames = (ArrayList<String>) newObjNames.clone();
         newObjNames.add(newVarName);
      }

      return newObjRes;
   }

   public void appendProp(Object changedObj, String propName, Object propValue, ArrayList<String> newObjNames, String newLastPackageName, SyncManager.SyncContext parentContext, SyncLayer syncLayer, ArrayList<SyncLayer.SyncChange> depChanges) {
      if (propValue != null) {
         // Which sync context to use for managing synchronization of on-demand references for properties of this component?   When a session scoped component extends or refers to a global scoped component - e.g. EditorFrame's editorModel, typeTreeModel, etc.
         // how do we keep everything in sync?  Global scoped contexts keep the list of session contexts which are mapping them.  When session scoped extends global, it becomes session - so editorModel/typeTreeModel should be session.  But LayeredSystem,
         // Layer's etc. should be global - shared.  Information replicated into the sessionSyncContext as needed.
         // Change listeners - global or session scoped level:
         //   1) do them at the global level to synchronize event propagation to the session level using the back-event notification.  Theoretically you could generate a single serialized sync layer and broadcast it out to all listeners.  they are
         //    synchronized and seeing the same thing.  they can ignore any data they get pushed which does not match their criteria.
         //   2) NO: adding/removing/delivering events is complicated.  We need to sync the add listener with the get intitial sync value.  It's rare that one exact layer applies to everyone so that optimization won't work. - it's likely that application logic has customized it so different users see different things.  It's a security violation to
         //    violate application logic and broadcast all data to all users.
         //   ?? What about the property value listener?   That too should be done at the session level.
         //    simplify by doing event listeners, event propagation at the session scoped level always.  register instances, names etc. globally so those are all shared.
         //  Need to propagate both contexts - session and global through the createOnDemand calls
         //  Use global "new names" - never commit to registeredNewNames for global.  Instead put them into session so we keep track of what's registered where.
         //
         // Problem with making the global objects full managed at the session level - if there are any changes at the global level which depend on the global object, it's change also must be tracked at the global level.  The depChanges takes
         // care of that now but currently we are registering the names at the session level which causes the problem.
         // What changes must be made at the global level?  Any initSyncInsts which are not on-demand and are on global scoped components.  We don't have a session scope then.
         SyncHandler valSyncHandler = parentContext.getSyncHandler(propValue);
         valSyncHandler.appendPropertyUpdateCode(this, changedObj, propName, propValue, parentContext.getPreviousValue(changedObj, propName), newObjNames, newLastPackageName, null, null, depChanges, syncLayer);
      }
      else {
         appendNameIndent(propName, newObjNames.size());
         appendNullValue();
      }
   }

   public void appendPropertyAssignment(SyncManager.SyncContext syncContext, Object changedObj, String propName, Object propValue, Object previousValue, ArrayList<String> currentObjNames, String currentPackageName, SyncSerializer preBlockCode, SyncSerializer postBlockCode, List<SyncLayer.SyncChange> depChanges, SyncLayer syncLayer) {
      try {
         appendNameIndent(propName, currentObjNames.size());
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

   private void formatRef(StringBuilder out, String name) {
      out.append('"');
      out.append(ExprPrefixes.ref.name());
      out.append(':');
      out.append(name);
      out.append('"');
   }

   public void formatReference(StringBuilder out, String objName, String currentPackageName) {
      if (objName == null)
         formatNullValue(out);
      else if (currentPackageName != null && objName.startsWith(currentPackageName) && currentPackageName.length() > 0) {
         formatRef(out, objName.substring(currentPackageName.length() + 1));
      }
      else
         formatRef(out, objName);
   }

   // This variants also takes the typeName of the array elements (as required in Java - new type[] { ...}, but for JSON we currently don't convey that type, but instead just use a JSON array
   public void formatNewArrayDef(StringBuilder out, SyncManager.SyncContext syncContext, Object changedObj, String typeName, ArrayList<String> currentObjNames, String currentPackageName,
                                 SyncSerializer preBlockCode, SyncSerializer postBlockCode, boolean inBlock, String uniqueId, List<SyncLayer.SyncChange> depChanges, SyncLayer syncLayer) {
      formatArrayExpression(out, syncContext, changedObj, currentObjNames, currentPackageName, preBlockCode, postBlockCode, inBlock, uniqueId, depChanges, syncLayer);
   }

   public void formatArrayExpression(StringBuilder out, SyncManager.SyncContext syncContext, Object changedObj, ArrayList<String> currentObjNames, String currentPackageName, SyncSerializer preBlockCode,
                                     SyncSerializer postBlockCode, boolean inBlock, String uniqueId, List<SyncLayer.SyncChange> depChanges, SyncLayer syncLayer) {
      out.append("[");
      int sz = DynUtil.getArrayLength(changedObj);
      for (int i = 0; i < sz; i++) {
         if (i != 0)
            out.append(",");
         Object val = DynUtil.getArrayElement(changedObj, i);
         syncContext.formatExpression(this, out, val, currentObjNames, currentPackageName, preBlockCode, postBlockCode, null, true, uniqueId + "_" + i, depChanges, syncLayer);
      }
      out.append("]");
   }

   // { "meth": "methName", "callId": "callId", "args": [ .... ] }
   public void appendMethodCall(SyncManager.SyncContext syncContext, SyncLayer.SyncMethodCall smc, ArrayList<String> newObjNames, String newLastPackageName, ArrayList<SyncLayer.SyncChange> depChanges, SyncLayer syncLayer) {
      int ix = newObjNames.size();
      appendCommandStart(Commands.meth, smc.methName, ix);

      appendName(MethodArgs.typeSig.toString());
      appendString(smc.paramSig);

      appendName(MethodArgs.callId.toString());
      appendString(smc.callId);

      appendName(MethodArgs.args.toString());
      formatArrayExpression(sb, syncContext, smc.args, newObjNames, newLastPackageName, null, null, false, null, depChanges, syncLayer);
      if (ix == 0)
         appendObjEnd();
   }

   enum MethodReturnArgs {
      callId, retType
   }

   public final static String MethodReturnExceptionType = "<exc>";

   public void appendMethodResult(SyncManager.SyncContext parentContext, SyncLayer.SyncMethodResult mres, ArrayList<String> newObjNames, String newLastPackageName, ArrayList<SyncLayer.SyncChange> depChanges, SyncLayer syncLayer) {
      int ix = newObjNames.size();
      appendNameStart(Commands.methReturn.cmd, ix);
      String exc = mres.exceptionStr;
      Object retVal = exc == null ? mres.retValue : exc;
      parentContext.formatExpression(this, sb, retVal, newObjNames, newLastPackageName, null, null, null, true, "", depChanges, syncLayer);
      appendName(MethodReturnArgs.callId.name());
      appendString(mres.callId);
      appendName(MethodReturnArgs.retType.name());
      if (exc != null)
         appendString(MethodReturnExceptionType);
      else if (mres.retValue == null)
         appendNullValue();
      else
         appendString(DynUtil.getTypeName(DynUtil.getType(mres.retValue), true));
      if (ix == 0)
         appendObjEnd();
   }

   public String toString() {
      return sb.toString();
   }

   public void appendFetchProperty(String propName, int indentSize) {
      appendSimpleCommand(Commands.get, propName, indentSize);
   }

   public void formatMap(StringBuilder out, SyncManager.SyncContext syncContext, Map changedMap, String typeName, ArrayList<String> currentObjNames, String currentPackageName, SyncSerializer preBlockCode, SyncSerializer postBlockCode,
                         String varName, boolean inBlock, String uniqueId, List<SyncLayer.SyncChange> depChanges, SyncLayer syncLayer) {
      out.append("{");
      // TODO: do we need to insert the type in here somehow or can we just infer that from the property type when deserializing it, or just create a HashMap
      if (changedMap.size() > 0) {
         boolean isFirst = true;
         for (Object mapEntObj: changedMap.entrySet()) {
            Map.Entry mapEnt = (Map.Entry) mapEntObj;
            if (!isFirst)
               sb.append(",");
            else
               isFirst = false;
            syncContext.formatExpression(this, sb, mapEnt.getKey(), currentObjNames, currentPackageName, null, null, null, true, null, depChanges, syncLayer);
            sb.append(":");
            syncContext.formatExpression(this, sb, mapEnt.getValue(), currentObjNames, currentPackageName, null, null, null, true, null, depChanges, syncLayer);
         }
      }
      out.append("}");
   }


   public void formatLong(StringBuilder out, Long val) {
      out.append(val.toString());
   }

   public void formatFloat(StringBuilder out, Float val) {
      out.append(val.toString());
   }

   public void formatNumber(StringBuilder out, Number val) {
      out.append(val.toString());
   }

   public void formatDouble(StringBuilder out, Double val) {
      out.append(val.toString());
   }

   public void appendString(String val) {
      formatString(sb, val);
   }

   public void formatString(StringBuilder sb, String val) {
      // An edge case - we use ref: to denote references in values so if this happens to be in the string we are formatting
      // we need to escape it
      if (val.startsWith(ExprPrefixes.ref.name()))
         val = '\\' + val;
      super.formatString(sb, val);
   }

   public void appendSerializer(SyncSerializer subSer) {
      JSONSerializer sub = (JSONSerializer) subSer;
      int subLen = sub.sb.length();
      boolean isFragment = sub.fragment;
      // Temp serializers have all of the formatting built-in, but when concatenating two top-level serializers, we need
      // insert the , that separates elements of the 'sync' array unless there is no separator required (isFirst = true)
      if (!isFragment && !sub.skipPreComma && sb.length() > 0 && subLen > 0 && !isFirst) {
         sb.append(",");
      }
      if (subLen > 0) {
         sb.append(sub.sb);
         if (!isFragment) // Now it's the sub serializers state which is current here
            isFirst = sub.isFirst;
      }
   }

   public SyncSerializer createTempSerializer(boolean fragment, int indent) {
      JSONSerializer res = (JSONSerializer) super.createTempSerializer(fragment, indent);
      res.fragment = fragment;
      res.indent = indent;
      return res;
   }

   public void setIndent(int indent) {
      this.indent = indent;
   }

   // TODO: the serialized format for changing sync states is kind of verbose now, so it's easier to read/understand but
   // we could optimize this to be a short prefix on the next property rather than a definition if for some reason we
   // end up swapping back and forth too often.
   void appendRemoteChanges(boolean remoteChange, boolean topLevel, int indent) {
      String cmd;
      if (remoteChange)
         cmd = "initLocal";
      else
         cmd = "init";
      if (topLevel || indent == 0) {
         appendObjStart(indent);
         appendName(Commands.syncState.cmd);
      }
      else {
         appendNameIndent(Commands.syncState.cmd, indent);
      }
      appendString(cmd);
      isFirst = false;
      if (topLevel || indent == 0)
         appendObjEnd();
   }

   public boolean needsObjectForTopLevelNew() {
      return false;
   }
}
