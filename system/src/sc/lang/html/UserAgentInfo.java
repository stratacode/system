package sc.lang.html;

import sc.lang.pattern.Pattern;
import sc.lang.pattern.URLPatternLanguage;
import sc.parser.Parselet;
import sc.type.PTypeUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UserAgentInfo implements Cloneable {
   public String userAgent;
   public String platform;
   public ArrayList<UserAgentExtension> extensions;

   public boolean isBrowser = false;
   public boolean isTablet = false;
   public boolean isRobot = false;

   public String browser;
   public String browserVersion;
   public String osName;

   public static List<String> mobilePlatforms = new ArrayList<String>();
   {
      mobilePlatforms.add("iPhone");
      mobilePlatforms.add("Android");
      mobilePlatforms.add("Mobile");
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
            for (UserAgentPattern curPattern:UserAgentPattern.userAgentPatterns) {
               // TODO: this doesn't support some of the parselets we use in the user agent parsing but only
               // happens on the server so it's ok to use the parselet: curInfo.patternImpl.matchString(userAgentStr)
               if (lang.matchString(userAgentStr, curPattern.patternParselet)) {
                  retInfo = curPattern.defaultInfo.clone();
                  retInfo.userAgent = userAgentStr;
                  lang.parseIntoInstance(userAgentStr, curPattern.patternParselet, retInfo);

                  if (!PTypeUtil.testMode)
                     System.out.println("UserAgent matched: " + userAgentStr + " pattern: " + curPattern.pattern + " isRobot: " + retInfo.isRobot + " platform: " + retInfo.platform);

                  retInfo.initValues();
                  break;
               }
            }
         }
         else
            return cachedInfo;
      }
      if (retInfo == null) {
         retInfo = new UserAgentInfo();
         retInfo.userAgent = userAgentStr;
      }
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

   private void initValues() {
      if (platform == null) {
         System.err.println("UserAgent - no platform found for: " + userAgent);
         return;
      }
      if (platform.contains("Android")) {
         if (!hasExtension("Mobile"))
            isTablet = true;
      }
      else if (platform.contains("iPad"))
         isTablet = true;

      if (platform.contains("Linux"))
         osName = "Linux";
      else if (platform.contains("Windows"))
         osName = "Windows";
      else if (platform.contains("Macintosh"))
         osName = "MacOS";
      else if (platform.contains("iPhone OS"))
         osName = "iPhone";
      else
         osName = platform;

      if (!isRobot) {
         String vers = getExtensionVersion("Chrome");
         if (vers != null) {
            browser = "Chrome";
            browserVersion = vers;
         }
         else if ((vers = getExtensionVersion("Safari")) != null) {
            browser = "Safari";
            browserVersion = vers;
         }
         else if ((vers = getExtensionVersion("Firefox")) != null) {
            browser = "Firefox";
            browserVersion = vers;
         }
         else if ((vers = getExtensionVersion("OPR")) != null) {
            browser = "Opera";
            browserVersion = vers;
         }
         else {
            browser = platform;
            browserVersion = null;
         }
      }
      else
         browser = platform;
   }

   public boolean hasExtension(String extName) {
      if (extensions == null)
         return false;
      for (int i = 0; i < extensions.size(); i++) {
         UserAgentExtension uae = extensions.get(i);
         if (uae.name.contains(extName))
            return true;
      }
      return false;
   }

   public String getExtensionVersion(String extName) {
      if (extensions == null)
         return null;
      for (int i = 0; i < extensions.size(); i++) {
         UserAgentExtension uae = extensions.get(i);
         if (uae.name.contains(extName))
            return uae.version;
      }
      return null;
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
