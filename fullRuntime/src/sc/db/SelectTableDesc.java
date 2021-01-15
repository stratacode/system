package sc.db;

import java.util.ArrayList;
import java.util.List;

/**
 * A given database SelectQuery is composed of one SelectTableDesc for each table in the query. The SelectTableDesc stores
 * info about the properties/columns to select from this table. If the table is an association table, it stores the reference property
 * being selected.
 */
class SelectTableDesc {
   TableDescriptor table;
   List<DBPropertyDescriptor> props;

   /** List of columns selected with the columns in this table but that refer to reverse relationships to the current type */
   List<DBPropertyDescriptor> revColumns;
   /** For each revColumn, the property reference in the parent object that points to that column value */
   List<DBPropertyDescriptor> revProps;

   // Only set if this table corresponds to a reference property of the parent type
   DBPropertyDescriptor refProp;

   /** The table or refProperty that joinsTo this table */
   SelectTableDesc joinedFrom;

   /** The list of tables or refProperties from this part of the query that are joined for this value (i.e. eagerly selected 1-1 relationships) */
   List<SelectTableDesc> joinedTo;

   // Only set if this table is used more than once
   String alias;

   boolean outerJoin = true;

   boolean selectDynColumn = false;

   SelectTableDesc(TableDescriptor table, List<DBPropertyDescriptor> props) {
      this.table = table;
      this.props = props;
   }

   SelectTableDesc copyForRef(DBPropertyDescriptor refProp) {
      SelectTableDesc res = new SelectTableDesc(table, new ArrayList<DBPropertyDescriptor>(props));
      // Always put the id and typeId property for references in the select list for references even if they are not there for
      // the normal 'select property' query when you have the id already
      /*
      res.props = new ArrayList<DBPropertyDescriptor>(table.idColumns);
      DBPropertyDescriptor typeIdProp = table.dbTypeDesc.getTypeIdProperty();
      if (typeIdProp != null)
         res.props.add(typeIdProp);
      */
      res.refProp = refProp;
      res.alias = alias;
      res.joinedFrom = joinedFrom;
      res.selectDynColumn = selectDynColumn;
      if (joinedTo != null)
         res.joinedTo = new ArrayList<SelectTableDesc>(joinedTo);
      return res;
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append(table == null ? "<null-select-table>" : alias == null ? table.tableName : table.tableName + " AS " + alias);
      if (props != null) {
         sb.append(" (");
         for (int i = 0; i < props.size(); i++) {
            DBPropertyDescriptor prop = props.get(i);
            if (i != 0)
               sb.append(", ");
            if (prop.dynColumn)
               sb.append("dyn:");
            sb.append(prop);
         }
         sb.append(") ");
      }
      if (revProps != null) {
         sb.append(" reverse to: " + revProps + "." + revColumns);
      }
      return sb.toString();
   }

   public SelectTableDesc clone() {
      SelectTableDesc res = new SelectTableDesc(table, new ArrayList<DBPropertyDescriptor>(props));
      if (res.revProps != null)
         res.revProps = new ArrayList<DBPropertyDescriptor>(revProps);
      if (res.revColumns != null)
         res.revColumns = new ArrayList<DBPropertyDescriptor>(revColumns);
      res.refProp = refProp;
      res.alias = alias;
      res.joinedFrom = joinedFrom;
      res.selectDynColumn = selectDynColumn;
      if (joinedTo != null)
         res.joinedTo = new ArrayList<SelectTableDesc>(joinedTo);
      return res;
   }

   public String getTableAlias() {
      if (alias != null)
         return alias;
      return table.tableName;
   }

   public String getTableDecl() {
      if (alias == null)
         return table.tableName;
      return table.tableName + " " + alias;
   }

   public boolean containsProperty(DBPropertyDescriptor pdesc) {
      if (props.contains(pdesc))
         return true;
      if (revProps != null && revProps.contains(pdesc))
         return true;
      if (refProp != null && refProp.equals(pdesc))
         return true;
      return false;
   }

   // TODO: For this property reference, are we eagerly selecting the referenced value? Right now, this only happens
   // from the main select query - we don't also eagerly select references from a reference (though theoretically we could
   // select some fixed small-number of levels of a graph)
   public boolean hasJoinTableForRef(DBPropertyDescriptor refPropDesc) {
      if (joinedFrom == null)
         return true;
      return false;
   }
}
