/*
 * Copyright (c) 2016. Jeffrey Vroom. All Rights Reserved.
 */

package sc.repos.mvn;

import sc.lang.html.Element;
import sc.lang.xml.XMLFileFormat;
import sc.util.IMessageHandler;

public class MvnMetadataFile extends XMLFileFormat {

   public static final MvnMetadataFile NULL_SENTINEL = new MvnMetadataFile(null, null);

   public MvnMetadataFile(String fileName, IMessageHandler msg) {
      super(fileName, msg);
   }

   public String getSnapshotVersion() {
      if (!parse())
         return null;
      // TODO: perhaps a utility that gets the Element with a path "versioning.snapshot" - also some errors or info messages here would be nice
      Element root = getRootElement();
      if (root != null) {
         Element versioning = root.getSingleChildTag("versioning");
         if (versioning != null) {
            Element snapshot = versioning.getSingleChildTag("snapshot");
            if (snapshot != null) {
               String timestamp = snapshot.getSimpleChildValue("timestamp");
               String buildNumber = snapshot.getSimpleChildValue("buildNumber");
               if (timestamp != null && buildNumber != null)
                  return timestamp + "-" + buildNumber;
            }
         }
      }
      return null;
   }
}
