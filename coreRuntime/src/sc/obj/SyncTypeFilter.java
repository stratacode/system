package sc.obj;

/** 
 * Used to include additional type names in the "syncTypeFilter", used
 * by servers to determine which types each client can access.
 */
public @interface SyncTypeFilter {
   String[] typeNames();
}
