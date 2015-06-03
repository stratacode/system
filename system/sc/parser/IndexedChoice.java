/*
 * Copyright (c) 2014. Jeffrey Vroom. All Rights Reserved.
 */

package sc.parser;

import java.util.*;

public class IndexedChoice extends OrderedChoice {
   SymbolChoice indexedKeys = new SymbolChoice(LOOKAHEAD | SKIP | NOERROR);
   Map<CharSequence,OrderedChoice.MatchResult> indexedParselets = new HashMap<CharSequence,OrderedChoice.MatchResult>();
   OrderedChoice.MatchResult defaultParselets = new OrderedChoice.MatchResult();

   public IndexedChoice() { super(); }

   public IndexedChoice(int options) {
      super(options);
   }

   public IndexedChoice(String id, int options) {
      super(id, options);
   }

   public IndexedChoice(Parselet... toAdd) {
      super(toAdd);
   }

   public IndexedChoice(String id, Parselet... toAdd) {
      super(id, toAdd);
   }

   public IndexedChoice(String id, int options, Parselet... toAdd) {
      super(id, options, toAdd);
   }

   public IndexedChoice(int options, Parselet... toAdd) {
      super(options, toAdd);
   }

   public void initialize() {
      if (!initialized) {
         super.initialize();
         indexedKeys.initialize();
         for (List<Parselet> l: indexedParselets.values()) {
            for (Parselet p:l) {
               p.setLanguage(getLanguage());
               p.initialize();
            }
         }
      }
   }

   public void start() {
      if (!started) {
         super.start();
         indexedKeys.start();
         for (List<Parselet> l: indexedParselets.values())
            for (Parselet p:l)
               p.start();
      }
   }

   /**
    *  Adds an entry for an indexed parselet.  You can use this if you have
    *  a long list of choices where the first token in each choice is a simple string.
    *  Instead of testing each rule (and doing a string compare for each rule), this will do an 
    *  indexed lookup to select the proper rule.  Internally we use the efficient SymbolChoice
    *  to peek ahead into the stream to see if we have a token which matches the input stream.
    *
    *  If you call this more than once, the order in which you register parselets for the given key
    *  is used to determine the order in which they are tested.  internally, for each key, we build
    *  a list of the parselet indexes required so we can rapidly select the right choices.
    */
   public void put(String key, Parselet parselet) {
      // Adding the same parselet against more than one key is ok but don't add that twice to the global list.
      int slotIx = parselets.indexOf(parselet);
      if (slotIx == -1) {
         slotIx = parselets.size();
         parselets.add(parselet);
      }

      OrderedChoice.MatchResult l;

      if ((l = indexedParselets.get(key)) == null) {
         indexedKeys.add(key);
         l = new OrderedChoice.MatchResult();
         l.add(parselet);
         l.addAll(defaultParselets); // Need to check the default parselets if the indexed choice fails
         l.slotIndexes.push(slotIx);
         for (int i = 0; i < defaultParselets.size(); i++)
            l.slotIndexes.push(defaultParselets.slotIndexes.get(i));
         indexedParselets.put(key, l);
      }
      else {
         // Put this just before the first default parselet so we preserve the
         // original "put" order in the testing.
         if (defaultParselets.size() == 0)
            l.add(parselet);
         else {
            int i;
            for (i = 0; i < l.size(); i++)
               if (l.get(i) == defaultParselets.get(0))
                  break;
            l.add(i, parselet);
         }
      }
   }

   /**
    * When inheriting a grammar from a base language, you may need to replace one parselet for another one.
    * This method is useful if you have a new child parselet for the same indexed key as the old one.
    */
   public void replace(Parselet oldParselet, Parselet newParselet) {
      int oldIx;
      for (oldIx = 0; oldIx < parselets.size(); oldIx++) {
         if (parselets.get(oldIx) == oldParselet)
            break;
      }
      if (oldIx == parselets.size())
         System.err.println("*** Replace parselet in IndexedChoice failed to find parselet to replace: " + oldParselet + " language: " + getLanguage());
      else {
         parselets.set(oldIx, newParselet);
      }
   }


   public void addDefault(Parselet... toAdd)
   {
      int slotIx = parselets.size();
      super.add(toAdd);
      
      for (Parselet p:toAdd) {
         defaultParselets.add(p);
         defaultParselets.slotIndexes.push(slotIx);
         for (OrderedChoice.MatchResult l: indexedParselets.values()) {
            l.add(p);
            l.slotIndexes.push(slotIx);
         }
         slotIx++;
      }
   }

   protected List<Parselet> getMatchingParselets(Parser parser) {
      // Peek ahead into the input stream and see if we have an index which matches
      Object indexedMatchObj = parser.parseNext(indexedKeys);

      // No match of an indexed entry - defer to all default parselets
      if (indexedMatchObj == null || indexedMatchObj instanceof ParseError)
         return defaultParselets;

      // Return the matched list (one item only)
      return indexedParselets.get((CharSequence)indexedMatchObj);
   }

   protected void clear()
   {
      super.clear();
      indexedKeys.clear();
      indexedParselets.clear();
      defaultParselets.clear();
   }
}
