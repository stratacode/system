package sc.db;

import sc.dyn.INameContext;

public class DBSyncNameContext implements INameContext {

   public Object resolveName(String name, boolean create, boolean returnTypes) {
      int ix = name.indexOf(DBObject.ObjectIdSeparator);
      if (ix != -1) {
         String typeName = name.substring(0, ix);
         DBTypeDescriptor desc = DBTypeDescriptor.getByName(typeName, true);
         if (desc != null) {
            String idPart = name.substring(ix+DBObject.ObjectIdSeparator.length());
            boolean isTransient = false;
            if (idPart.endsWith(DBObject.TransientSuffix)) {
               idPart = idPart.substring(0, idPart.length()-1);
               isTransient = true;
            }
            try {
               long id = Long.parseLong(idPart);

               if (isTransient) {
                  if (id > desc.transientIdCount)
                     desc.transientIdCount = id + 1;

                  IDBObject tinst = desc.createInstance();
                  ((DBObject) tinst.getDBObject()).setObjectId(name);

                  // Passing onDemand=false here because we need to init the sync for any reference received from
                  // the other side since it's already referenced. Other references are onDemand so that we init them
                  // the first time a reference gets serialized.
                  desc.initSyncForInst(tinst, false, true);

                  return tinst; // Just create it and register under the id provided
               }
               IDBObject inst = desc.findById(id);
               if (inst == null) {
                  DBUtil.error("Unable to resolve remote db instance: " + name);
                  return null;
               }
            }
            catch (NumberFormatException exc) {
               DBUtil.error("Error parsing object id: " + idPart);
            }
         }
      }
      return null;
   }
}
