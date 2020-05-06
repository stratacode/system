package sc.lang.sql;

import sc.lang.ISemanticNode;
import sc.lang.SQLLanguage;
import sc.lang.java.JavaModel;
import sc.lang.java.JavaSemanticNode;
import sc.parser.ParseUtil;

import java.util.List;
import java.util.Set;

public abstract class SQLCommand extends JavaSemanticNode {
   private transient String comment;

   public int getChildNestingDepth() {
      return 1;
   }

   abstract void addTableReferences(Set<String> refTableNames);

   public SQLCommand getDropCommand() {
      return null;
   }

   public void init() {
      if (comment == null) {
         JavaModel model = getJavaModel();
         if (model.parseNode != null)
         comment = ParseUtil.getCommentsBefore(model.parseNode, this, SQLLanguage.getSQLLanguage().spacing);
      }
   }

   public abstract String getIdentifier();

   public abstract boolean hasReferenceTo(SQLCommand other);

   public void alterTo(SQLFileModel resModel, SQLCommand newCmd, List<SchemaChangeDetail> notUpgradeable) {
      SQLCommand dropCmd = getDropCommand();
      if (dropCmd != null) {
         resModel.addCommand(dropCmd);
         resModel.addCommand((SQLCommand) newCmd.deepCopy(ISemanticNode.CopyNormal | ISemanticNode.CopyParseNode, null));
      }
      else
         throw new UnsupportedOperationException("Unable to alter DB entity type: " + getClass());
   }

   public void setComment(String comment) {
      this.comment = comment;
   }

   public String getDefaultComment() {
      return comment;
   }
}
