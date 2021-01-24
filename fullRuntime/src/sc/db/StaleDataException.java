/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.db;

/**
 * Thrown when an update fails because the version has changed or the row was removed
 * that the update needed.
 *
 * TODO: would it be convenient to have a way to refresh the item, move
 * the state in the delete/update into this object and have a method to
 * reapply one or all properties? Would it be helpful to do a query to get
 * the new version and new properties at the time this error occurs or
 * should the caller, fix the transaction by doing a refresh and just re-update?
 */
public class StaleDataException extends RuntimeException {
   public StaleDataException(DBObject dbObject, long version) {
      super("StaleDataException for: " + dbObject + " id: " + version);
   }
}
