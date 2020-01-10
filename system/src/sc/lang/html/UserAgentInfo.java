package sc.lang.html;

import sc.lang.pattern.Pattern;
import sc.lang.pattern.URLPatternLanguage;
import sc.parser.Parselet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UserAgentInfo implements Cloneable {
   private String pattern;
   private Pattern patternImpl;
   private Parselet patternParselet;

   public String platform;
   public ArrayList<UserAgentExtension> extensions;

   public boolean isBrowser = false;
   public boolean isRobot = false;

   public void init() {
      patternImpl = Pattern.initURLPattern(UserAgentInfo.class, pattern);
      patternParselet = patternImpl.getParselet(URLPatternLanguage.getURLPatternLanguage(), UserAgentInfo.class);
   }

   public static List<String> mobilePlatforms = new ArrayList<String>();
   {
      mobilePlatforms.add("iPhone");
      mobilePlatforms.add("Android");
      mobilePlatforms.add("Mobile");
   }

   public static List<UserAgentInfo> userAgentInfos = new ArrayList<UserAgentInfo>();
   static {
      // Any browsers that don't use Mozilla/5.0 now?
      UserAgentInfo browserInfo = new UserAgentInfo();
      browserInfo.pattern = "Mozilla/5.0{whiteSpace}({platform=userAgentComment}){whiteSpace}{extensions=userAgentExts}";
      browserInfo.init();
      browserInfo.isBrowser = true;
      userAgentInfos.add(browserInfo);

      // TODO: this is a catchall for now but it would be nice to get some type of data model from the robot
      // traffic as part of the management UI options
      browserInfo = new UserAgentInfo();
      browserInfo.pattern = "{platform=userAgentName}[/{whiteSpace}{versionString}]{whiteSpace}{extensions=userAgentExts}";
      browserInfo.init();
      browserInfo.isRobot = true;
      userAgentInfos.add(browserInfo);
   }

   final static Map<String,UserAgentInfo> userAgentInfoCache = new HashMap<String,UserAgentInfo>();

   public static UserAgentInfo getUserAgent(String userAgentStr) {
      if (userAgentStr == null)
         return null;
      UserAgentInfo retInfo = null;
      synchronized (userAgentInfoCache) {
         UserAgentInfo cachedInfo = userAgentInfoCache.get(userAgentStr);
         if (cachedInfo == null) {
            URLPatternLanguage lang = URLPatternLanguage.getURLPatternLanguage();
            for (UserAgentInfo curInfo:userAgentInfos) {
               // TODO: this doesn't support some of the parselets we use in the user agent parsing but only
               // happens on the server so it's ok to use the parselet: curInfo.patternImpl.matchString(userAgentStr)
               if (lang.matchString(userAgentStr, curInfo.patternParselet)) {
                  retInfo = curInfo.clone();
                  //curInfo.patternImpl.updateInstance(userAgentStr, retInfo);
                  lang.parseIntoInstance(userAgentStr, curInfo.patternParselet, retInfo);
               }
            }
         }
      }
      if (retInfo == null)
         retInfo = new UserAgentInfo();
      userAgentInfoCache.put(userAgentStr, retInfo);
      return retInfo;
   }

   public UserAgentInfo clone() {
      try {
         return (UserAgentInfo) super.clone();
      }
      catch (CloneNotSupportedException exc) {
         throw new UnsupportedOperationException();
      }
   }


   public static boolean listItemInString(List<String> list, String toFind) {
      for (String elem:list)
         if (toFind.contains(elem))
            return true;
      return false;
   }

   public boolean isMobile() {
      if (mobilePlatforms.contains(platform))
         return true;
      if (extensions != null) {
         for (UserAgentExtension ext:extensions)
            if (mobilePlatforms.contains(ext.name) || (ext.comment != null && listItemInString(mobilePlatforms, ext.comment)))
               return true;
      }
      return false;
   }

   public int getDefaultInnerWidth() {
      return isMobile() ? 800 : 1000;
   }

   public int getDefaultInnerHeight() {
      return 1024;
   }

   public double getDefaultDevicePixelRatio() {
      return isMobile() ? 2.0 : 1.0;
   }
}
