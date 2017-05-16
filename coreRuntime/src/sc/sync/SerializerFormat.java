/*
 * Copyright (c) 2017. Jeffrey Vroom. All Rights Reserved.
 */

package sc.sync;

import sc.dyn.DynUtil;
import sc.obj.Sync;
import sc.obj.SyncMode;

import java.util.TreeMap;

/**
 * A registry of formats and hook point for creating new serialization formats for serializing data between processes
 * The language option is a hook for translating from one format to another.  For example, we translate 'stratacode' to
 * 'js' so you can just evaluate the javascript to apply the changes in the layer.
 */
@sc.js.JSSettings(jsModuleFile="js/sync.js", prefixAlias="sc_")
@Sync(syncMode= SyncMode.Disabled)
public class SerializerFormat {
   public String language;

   public static TreeMap<String,SerializerFormat> formats = new TreeMap<String,SerializerFormat>();
   static {
      formats.put("stratacode", new SerializerFormat("stratacode"));
      formats.put("js", new SerializerFormat("js"));
      formats.put("json", new JSONFormat());
   }

   public SerializerFormat(String lang) {
      language = lang;
   }

   public static SerializerFormat getFormat(String lang) {
      return formats.get(lang);
   }

   public SyncSerializer createSerializer(SyncManager mgr) {
      return new SyncSerializer(this, mgr);
   }

   public void applySyncLayer(String destName, String scopeName, String layerDef, boolean isReset, boolean allowCodeEval) {
      DynUtil.applySyncLayer(language, destName, scopeName, layerDef, isReset, allowCodeEval);
   }
}
