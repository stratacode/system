/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.obj;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Specifies synchronization is to be performed on the type or property (either a field or a getX method). */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
public @interface Sync {
   /** Specifies the mode of operation for this property or type.  It can be enabled, or disabled or marked for one-way operation */
   SyncMode syncMode() default SyncMode.Enabled;
   /** Settable only at the type level right now.  For advanced uses only, you can partition properties into different sync groups, that are committed together */
   String groupName() default ""; 
   /** To be implemented - override the destinations to use for synchronization  Usually this is defined at the framework level globally.  */
   String[] destinations() default {};

   /**
    * TODO: do we need to implement this includeChildren flag?
    * What about export and import attributes so we can export Sync from a downstream to an upstream
    * and/or import so we can selectively inherit Sync from downstream layers?
    */
   boolean includeChildren() default true;

   /** When you set @Sync(includeSuper=true) properties inherited from all base-types are also synchronized. */
   boolean includeSuper() default false;
   /** 
    * Controls whether the synchronization for the type or property is eager or on-demand.  When a type is on-demand, it's only sent to the client
    * when it is first referenced by another object that's synchronized.  When a type is not on-demand, it's added to the set of objects to be 
    * synchronized when it's first created.  When a property has onDemand=false, it's synchronized when it's type is synchronized.  When it has
    * onDemand=true, it's synchronized when you use SyncManager.startSync.  If you call that on the server, it pushes the value of the property to the client
    * on the next sync.  If you call that on the client, it fetches the property's value on the next sync.
    */
   boolean onDemand() default false;
   /** 
    * When an object is first synchronized, the initial values for the properties are sometimes already known on the remote side.  For example, if you initialize the
    * property in code which is shared by the client and the server.  If the initial value is not known on the other side, a change is sent for that property when
    * the object is created.  You use initDefault=true to send the initial value even if it's initialized in both runtimes.
    * You can set initDefault=false when the initial value is already known on the remote side even if it's not initialized.
    * By default, the initializers of both runtimes are compared.  If they are the same initDefault is false.
    */
   boolean initDefault() default false;
   /** 
    * You can set this to to true on a property which is synchronized but not modified after the initial value is sent.  No property change listeners are injected,
    * but the initial value of the property is still synchronized. 
    */
   boolean constant() default false;

   /**
    * Currently this is only set in serialized layers, not in source code.  It signals a switch to marking content as originating from the remote side (the client),
    * so that it's returned to the server on a session reset.   Ordinarily the client's changes are marked as 'remote changes' automatically without any need to
    * mark them, but during a refresh, the server sets this flag to separate those client-originated changes from the ones that are really coming from the server.
    */
   boolean remoteChange() default false;
}
