/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import java.util.Set;

/**
 * The merge mode is used for three different settings, all involved in merging tags when this tag has a
 * matching tag in a previous template and the tagMerge mode is "merge" (the default).  The result is a flexible, declarative
 * system you can use to insert, replace, or modify content in an existing template.
 * <p>
 * For most template tags, you can use the default tagMerge mode of "merge".  With "merge", a given tag's definition is merged
 * with a matching tag in the previous template.  In this case, the order and position of the tag itself in the previous document is not changed.
 * The tags body is merged with the previous tag's body.  Any unmatching tags or content in the new template are appended to the content from the
 * previous template.
 * <p>
 * For the replace tagMerge mode, this tag replaces the matching tag in the previous document.  So if you replace one document with a copy of itself, the result would be unchanged.
 * <p>
 * For the append tagMerge mode, the matched tag is itself not changed.  The new tag creates a new object with a new name that is appended to the body of the previous tag's body.
 * (? Question: should this append to the parent's body after the last matched tag?  Same with prepend)
 * </p>
 * For the prepend tagMerge mode, the matched dynamic tag is also not changed.  The new tag creates a new object with a new name that is prepended to the body of the previous tag's body.
 * </p>
 * When you've chosen the tagMerge mode of Merge, by default the dynamic tags in the body of this tag will be merged with any matching tags in the previous matched tag's body.  Commonly, this default
 * is what you want.  But in some cases you may want to put this tag's body in front of or after the previous tag's body.  You also might want to keep the tag's attributes but replace the tag's body.
 * In these cases you can set the bodyMerge attribute to "append", "prepend", or "replace" to override the default.
 * <p>
 * Almost all of the time, if you are merging a dynamic tag, you want to merge the sub-tags of that tag as well.  In the rare case where you might want to append, prepend, or replace those sub-tags, you can
 * set the subTagMerge attribute to one of these values.
 * </p>
 * Some examples of how this can be used.
 *
 * Without setting tagMode at all:
 * <p>To define an HTML widget, just put it in its own shell of a document with html and body tags surrounding your content.  This will merge naturally with the default page template
 * for your application so that your tags are in the body.  Also you load your widget separately.</p>
 * <p>A template can define empty div tags with ids to mark specific locations that sub-templates can patch in by simply defining one or more subregion.  In this case, the order of the div tags in the sub-template is not used.  Only the order of the div tags in the base document matter.</p>
 *
 * <p>With tagMerge="replace":
 * Define a div tag with tagMerge="replace" where the id of the tag or it's location in the document matches a div tag in the parent document.</p>
 * <p>With tagMode="append": Append one or more tags to the content in the previous document.</p>
 *
 * <p>With subTagMerge="append", even when this tag is merged, all of the body content for the children will be appended to the parent, not merged if they happen to match.
 * If for example, you need to append to a base document where there is a div tag with no id and you want to add div tags without ids.
 * Ordinarily, the div tags will match by tag name and modify each other.</p>
 *
 */
public enum MergeMode {
   Merge, Append, Replace, Prepend;

   public static MergeMode fromString(String value) {
      for (MergeMode m:values())
         if (m.name().equalsIgnoreCase(value))
            return m;
      return null;
   }

   public static void addMatchingModes(String prefix, Set<String> candidates, int max) {
      for (MergeMode m:values()) {
         String n = m.name().toLowerCase();
         if (n.startsWith(prefix.toLowerCase())) {
            candidates.add(n);
            if (candidates.size() >= max)
               return;
         }
      }
   }
}
