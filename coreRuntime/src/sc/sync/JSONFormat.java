/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.sync;

import sc.dyn.DynUtil;
import sc.obj.Sync;
import sc.obj.SyncMode;

import java.util.ArrayList;
import java.util.List;

import static sc.sync.JSONParser.eqs;

/**
 * A sync format which uses JSON as the primary data format for serialization.
 *
 * TODO: should this still extends SerializerFormat (and same with JSONSerializer)?  Maybe that becomes an abstract base class and there's an SCSerializerFormat
 * which has the implements now in SerializerFormat?  Originally I thought that because we support code snippets embedded in
 * the JSON that it would be helpful to share logic, communicating rules and expressions as part
 * of the format so it's feature complete with the native language serializer.  That's why it extends
 * the core SerializerFormat rather than being an independent implementation, but it turns out so far that this
 * has not been needed as we override all methods and (I think) do not call down to SerializerFormat
 */
@sc.js.JSSettings(jsModuleFile="js/sync.js", prefixAlias="sc_")
@Sync(syncMode= SyncMode.Disabled)
public class JSONFormat extends SerializerFormat {
   public JSONFormat() {
      super("json");
   }

   public JSONSerializer createSerializer(SyncManager mgr) {
      return new JSONSerializer(this, mgr);
   }

   public void applySyncLayer(String destName, String scopeName, String layerDef, boolean isReset, boolean allowCodeEval) {
      JSONDeserializer dser = new JSONDeserializer(destName, scopeName, layerDef, isReset, allowCodeEval);
      dser.apply();
   }

   // These are the individual commands found in the sync array
   //
   // The apply method here is overridden to handle the command specific parsing of the arguments to the commands during
   // deserialization.
   //
   // Look in JSONSerializer for the logic to format the commands
   //
   // WARNING: if you add a command, you also need to update the switch statement so we can efficiently look up the name
   enum Commands {
      pkg {
         // { "pkg": "pkgName" }
         public void apply(JSONDeserializer dser, boolean topLevel) {
            CharSequence pkg = dser.parser.parseString(true);
            dser.currentPackage = pkg == null ? null : pkg.toString();
         }
      },
      newCmd("new") {
         // { "new": "objName", "ext": "typeName", "sub": [ props + subObjs ]
         public void apply(JSONDeserializer dser, boolean topLevel) {
            List res = dser.parser.parseArray();
            if (res != null && res.size() == 3) {
               String objName = dser.acceptString(res.get(0), false);
               String extTypeName = dser.acceptString(res.get(1), false);
               Object[] args = dser.acceptArray(res.get(2), true);
               dser.createNewObj(objName, extTypeName, args);
            }
            else
               throw new IllegalArgumentException("Expecting array with three elements for new command: " + dser.parser);
         }
      },
      // TODO: remove this?  It's referenced but we override the method it's referenced from from JSON
      eval {
         public void apply(JSONDeserializer dser, boolean topLevel) {
            throw new UnsupportedOperationException();
            /*
            CharSequence code = dser.parser.parseString(false);
            if (code != null) {
               if (dser.allowCodeEval) {
                  DynUtil.evalScript(code.toString());
               }
               else {
                  throw new IllegalArgumentException("Security warning: attempt to eval code on destination with allowCodeEval = false: " + dser.parser);
               }
            }
            */
         }
      },
      meth {
         // { "meth": "methName", "callId": "callId", "args": [ .... ] }
         public void apply(JSONDeserializer dser, boolean topLevel) {
            CharSequence methName = dser.parseMethName();
            dser.parser.expectNextName(MethodArgs.typeSig.name());
            CharSequence typeSig = dser.parser.parseString(true);
            dser.parser.expectNextName(MethodArgs.callId.name());
            CharSequence callIdVal = dser.parser.parseString(false);
            dser.parser.expectNextName(MethodArgs.args.name());
            List args = dser.parser.parseArray();
            if (methName != null && callIdVal != null) {
               dser.invokeMethod(methName, typeSig, args, callIdVal);
            }
            else
               throw new IllegalArgumentException("Invalid remote method call in JSON: " + dser.parser);
         }
      },
      methReturn {
         public void apply(JSONDeserializer dser, boolean topLevel) {
            // Method return value comes first
            Object returnValue = dser.parser.parseJSONValue();
            dser.parser.expectNextName(JSONSerializer.MethodReturnArgs.callId.name());
            CharSequence callIdVal = dser.parser.parseString(false);
            dser.parser.expectNextName(JSONSerializer.MethodReturnArgs.retType.name());
            CharSequence retTypeName = dser.parser.parseString(true);
            String exceptionStr = null;
            if (retTypeName != null && retTypeName.equals(JSONSerializer.MethodReturnExceptionType)) {
               exceptionStr = returnValue.toString();
               returnValue = null;
            }
            if (callIdVal != null) {
               dser.applyMethodResult(callIdVal.toString(), returnValue, retTypeName == null ? null : DynUtil.findType(retTypeName.toString()), exceptionStr);
            }
            else
               throw new IllegalArgumentException("Invalid remote method result in JSON: " + dser.parser);
         }
      },
      get {
         public void apply(JSONDeserializer dser, boolean topLevel) {
            CharSequence propName = dser.parser.parseString(false);
            if (propName != null) {
               dser.fetchProperty(propName.toString());
            }
            else
               throw new IllegalArgumentException("Invalid fetch property in JSON: " + dser.parser);
         }
      },
      syncState {
         public void apply(JSONDeserializer dser, boolean topLevel) {
            CharSequence stateName = dser.parser.parseString(false);
            if (stateName != null) {
               SyncManager.SyncState state;
               if (stateName.equals("init"))
                  state = SyncManager.SyncState.Initializing;
               else if (stateName.equals("initLocal"))
                  state = SyncManager.SyncState.InitializingLocal;
               else
                  throw new IllegalArgumentException("Invalid syncState: " + stateName);
               SyncManager.setSyncState(state);
            }
         }
      };

      public static Commands get(CharSequence seq) {
         int len = seq.length();
         if (len < 2)
            return null;
         // TODO: do we need to validate here?  The goal is speed here!
         switch (seq.charAt(1)) {
            case 'p':
               return pkg;
            case 'e':
               return eval;
            case 'm':
               return len == 5 ? meth : methReturn;
            case 'g':
               return get;
            case 's':
               return syncState;
            case 'n':
               return newCmd;
         }
         return null;
      }

      public String cmd;

      Commands() {
         cmd = '$' + this.name();
      }

      Commands(String str) {
         cmd = '$' + str;
      }

      /** The method called when this command is encountered in the JSON document */
      public abstract void apply(JSONDeserializer dser, boolean topLevel);

      public String toString() {
         return cmd;
      }
   }

   enum ExprPrefixes {
      // WARNING: if you change this, change the code in the isRefPrefix method
      ref;

      // Hard-coded for speed
      static boolean isRefPrefix(CharSequence val, int off) {
         return val.charAt(off) == 'r' && val.charAt(1+off) == 'e' && val.charAt(2+off) == 'f' && val.charAt(3+off) == ':';
      }
   }

   enum MethodArgs {
      callId, args, typeSig
   }

}
