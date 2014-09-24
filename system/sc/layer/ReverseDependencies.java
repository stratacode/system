/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.layer;

import sc.util.FileUtil;

import java.io.*;
import java.util.*;

/** For a given type, stores the references to the set of types which require bindable or dynamic behavior from this type */
public class ReverseDependencies implements Serializable {
   public static final String REVERSE_DEPENDENCIES_EXTENSION = "rdps";
   int typeCount = 0;
   /** Stores the type names as the key, the type count id as the value.  Designed so multiple references from the same types are efficiently stored. */
   HashMap<String,Integer> typeRegistry = new HashMap<String,Integer>();
   public HashMap<Integer,String> typeIndex = new HashMap<Integer,String>();

   /** Property name to type index code mapping for bindable properties */
   public HashMap<String,PropertyDep[]> bindableDeps = new HashMap<String,PropertyDep[]>();

   /** Stores the list of methods which need to be available to the dynamic runtime.  The external type index will code-generation the 'invoke('name')' method dispatch logic */
   public HashMap<MethodKey,int[]> dynMethods = new HashMap<MethodKey,int[]>();

   /**
    * Only created for files like web.xml which depend on the members of a given type group.  When new members are added or
    * removed, we need to regenerate the file
    */
   public HashMap<String, ArrayList<String>> typeGroupDeps;

   private static final int NEW_ENTRY = 1;
   transient HashMap<String,Boolean> changedState = new HashMap<String, Boolean>();
   /** Has this set of dependencies been modified since being read? */
   transient boolean changed = false;

   /** The build layer these deps were read from */
   public transient Layer layer;

   private int getTypeIndex(String fromType) {
      Integer ct = typeRegistry.get(fromType);
      if (ct == null) {
         typeRegistry.put(fromType, ct = typeCount++);
         typeIndex.put(ct, fromType);
      }
      if (changedState == null)
         changedState = new HashMap<String, Boolean>();
      changedState.put(fromType, Boolean.TRUE);

      return ct;
   }

   public static boolean needsBindable(PropertyDep[] pdeps) {
      // All of them have to be reference for it to not need bindable
      for (PropertyDep pdep:pdeps)
         if (!pdep.refOnly)
            return true;
      return false;
   }

   public static class PropertyDep implements Serializable {
      int typeIndex;
      boolean refOnly;
   }

   public void addBindDependency(String fromType, String property, boolean referenceOnly) {
      changed = true;
      int ct = getTypeIndex(fromType);

      PropertyDep[] currentDeps = bindableDeps.get(property);
      PropertyDep cpd;

      int newPos;
      if (currentDeps == null) {
         newPos = 0;
         currentDeps = new PropertyDep[1];
      }
      else {
         // Already there?
         for (int i = 0; i < currentDeps.length; i++) {
            cpd = currentDeps[i];
            if (cpd.typeIndex == ct) {
               cpd.refOnly = cpd.refOnly && referenceOnly;
               return;
            }
         }

         newPos = currentDeps.length;
         PropertyDep[] newDeps = new PropertyDep[newPos+1];
         System.arraycopy(currentDeps, 0, newDeps, 0, newPos);
         currentDeps = newDeps;
      }
      cpd = new PropertyDep();
      cpd.typeIndex = ct;
      cpd.refOnly = referenceOnly;
      currentDeps[newPos] = cpd;
      bindableDeps.put(property, currentDeps);
   }

   public void addDynMethod(String fromType, String methodName, String paramSig) {
      changed = true;
      if (dynMethods == null)
         dynMethods = new HashMap<MethodKey,int[]>();

      int ct = getTypeIndex(fromType);

      MethodKey key = new MethodKey(methodName, paramSig);

      int[] currentDeps = dynMethods.get(key);

      int newPos;
      if (currentDeps == null) {
         newPos = 0;
         currentDeps = new int[1];
      }
      else {
         // Already there?
         for (int i = 0; i < currentDeps.length; i++)
            if (currentDeps[i] == ct)
               return;

         newPos = currentDeps.length;
         int[] newDeps = new int[newPos+1];
         System.arraycopy(currentDeps, 0, newDeps, 0, newPos);
         currentDeps = newDeps;
      }
      currentDeps[newPos] = ct;
      dynMethods.put(key, currentDeps);
   }

   /**
    * This removes any dependencies from types in our registry which were changed but did not add a new entry.
    * TODO: does this handle the case where we remove a file and do an incremental build?
    */
   public void cleanStaleEntries(HashMap<String,IFileProcessorResult> changedModels) {
      HashMap<Integer,Boolean> tocull = new HashMap<Integer,Boolean>();
      for (String typeName:typeRegistry.keySet()) {
         if (changedState == null)
            changedState = new HashMap<String, Boolean>();
         if (changedModels.get(typeName) != null && changedState.get(typeName) == null)
            tocull.put(typeRegistry.get(typeName), Boolean.TRUE);
      }
      if (bindableDeps != null) {
         for (Iterator<Map.Entry<String,PropertyDep[]>> it = bindableDeps.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String,PropertyDep[]> ent = it.next();
            PropertyDep[] pdeps = ent.getValue();
            int toRemove = 0;
            for (int k = 0; k < pdeps.length; k++) {
               if (tocull.get(pdeps[k].typeIndex) != null)
                  toRemove++;
            }
            if (toRemove > 0) {
               if (pdeps.length == toRemove)
                  it.remove();
               else {
                  PropertyDep[] newValues = new PropertyDep[pdeps.length - toRemove];
                  int j = 0;
                  for (int k = 0; k < pdeps.length; k++) {
                     if (tocull.get(pdeps[k].typeIndex) == null)
                        newValues[j++] = pdeps[k];
                  }
                  ent.setValue(newValues);
               }
            }
         }
      }
      if (dynMethods != null) {
         for (Iterator<Map.Entry<MethodKey,int[]>> it = dynMethods.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<MethodKey,int[]> ent = it.next();
            int[] values = ent.getValue();
            int toRemove = 0;
            for (int k = 0; k < values.length; k++) {
               if (tocull.get(values[k]) != null)
                  toRemove++;
            }
            if (toRemove > 0) {
               if (values.length == toRemove)
                  it.remove();
               else {
                  int[] newValues = new int[values.length - toRemove];
                  int j = 0;
                  for (int k = 0; k < values.length; k++) {
                     if (tocull.get(values[k]) == null)
                        newValues[j++] = values[k];
                  }
                  ent.setValue(newValues);
               }
            }
         }
      }
   }

   public void addDeps(ReverseDependencies deps) {
      if (deps == null)
         return;
      for (Map.Entry<String,PropertyDep[]> ent:deps.bindableDeps.entrySet()) {
         String propName = ent.getKey();
         PropertyDep[] pdeps = ent.getValue();
         for (int i = 0; i < pdeps.length; i++) {
            PropertyDep pdep = pdeps[i];
            addBindDependency(deps.typeIndex.get(pdep.typeIndex), propName, pdep.refOnly);
         }
      }
      if (deps.dynMethods != null) {
         if (dynMethods == null)
            dynMethods = new HashMap<MethodKey, int[]>();
         for (Map.Entry<MethodKey,int[]> ent:deps.dynMethods.entrySet()) {
            MethodKey key = ent.getKey();
            int[] vals = ent.getValue();
            for (int i = 0; i < vals.length; i++) {
               addDynMethod(deps.typeIndex.get(vals[i]), key.methodName, key.paramSig);
            }
         }
      }
      if (deps.typeGroupDeps != null) {
         if (typeGroupDeps == null)
            typeGroupDeps = new HashMap<String,ArrayList<String>>();
         for (Map.Entry<String,ArrayList<String>> depEnt:deps.typeGroupDeps.entrySet()) {
            // Replacing the entry for now... I don't think we need to merge them as they've already been merged
            typeGroupDeps.put(depEnt.getKey(), depEnt.getValue());
         }
      }
   }

   public static void saveReverseDeps(ReverseDependencies reverseDeps, String revFileName) {
      File revFile = new File(revFileName);

      File revDir = new File(FileUtil.getParentPath(revFileName));
      if (!revDir.isDirectory()) {
         if (!revDir.mkdirs()) {
            System.err.println("*** Can't make parent dir for reverse index: " + revDir.getPath());
            return;
         }
      }
      if (reverseDeps == null) {
         if (revFile.canRead())
            revFile.delete();
         return;
      }

      try {
         ObjectOutputStream os = new ObjectOutputStream(new FileOutputStream(revFile));
         os.writeObject(reverseDeps);
      }
      catch (IOException exc) {
         System.out.println("*** can't write build srcFile: " + exc);
      }


   }

   public static ReverseDependencies readReverseDeps(String revDepsFileName, ReverseDependencies reverseDeps) {
      File revDepsFile = new File(revDepsFileName);
      if (revDepsFile.canRead()) {
         try {
            ObjectInputStream ios = new ObjectInputStream(new FileInputStream(revDepsFile));
            ReverseDependencies res = (ReverseDependencies) ios.readObject();
            if (res != null) {
               if (reverseDeps != null) {
                  reverseDeps.addDeps(res);
               }
               else
                  reverseDeps = res;
            }
            else {
               reverseDeps = null;
            }
         }
         catch (InvalidClassException exc) {
            System.out.println("reverse dependencies - version changed: " + revDepsFileName);
            revDepsFile.delete();
         }
         catch (IOException exc) {
            System.out.println("*** can't read build reverse deps: " + exc);
         }
         catch (ClassNotFoundException exc) {
            System.out.println("*** can't read build reverse deps: " + exc);
         }
      }
      return reverseDeps;
   }

   public boolean hasChanged() {
      return changed;
   }
}
