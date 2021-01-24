/*
 * Copyright (c) 2021.  Jeffrey Vroom. All Rights Reserved.
 */

package sc.lang.html;

import sc.lang.pattern.Pattern;
import sc.lang.pattern.URLPatternLanguage;
import sc.parser.Parselet;
import sc.type.PTypeUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides information about a particular http user-agent - obtained by parsing the user-agent header.
 * A small number of patterns are registered that use the UserAgentInfo class as the target. The patterns can
 * set the properties of this class directly - e.g. platform, and extensions. Once we've organized the information,
 * we look for patterns in the platform and extensions using a normal method and cache the results.
 * Based on the user-agent, we can provide a default screen width/height to use for the tag objects in rendering the
 * initial HTML.
 */
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

   public static int CacheSizeLimit = 10000;

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
            int ix = 0;
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
               ix++;
            }
         }
         else
            return cachedInfo;
      }
      if (retInfo == null) {
         retInfo = new UserAgentInfo();
         retInfo.userAgent = userAgentStr;
         retInfo.platform = userAgentStr;
         System.err.println("*** Unrecognized format for user-agent string: " + userAgentStr);
         retInfo.isRobot = true;
      }
      if (userAgentInfoCache.size() < CacheSizeLimit)
         userAgentInfoCache.put(userAgentStr, retInfo);
      else
         System.err.println("*** User-agent cache exceeded limit: " + CacheSizeLimit + " not caching user-agent: " + userAgentStr);
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

   String getExtensionsString() {
      if (extensions == null || extensions.size() == 0)
         return null;
      StringBuilder sb = new StringBuilder();
      int ix = 0;
      for (UserAgentExtension ext:extensions) {
         if (ix != 0)
            sb.append(" ");
         sb.append(ext.name);
         sb.append("/");
         sb.append(ext.version);
         if (ext.comment != null) {
            sb.append(" (");
            sb.append(ext.comment);
            sb.append(")");
         }
         ix++;
      }
      return sb.toString();
   }

   private void initValues() {
      if (platform == null) {
         if (extensions == null || extensions.size() == 0) {
            System.err.println("UserAgent - no platform or extensions found for: " + userAgent);
            return;
         }
         else {
            // There's no primary platform so we'll put everything into the platform string
            platform = getExtensionsString();
         }
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

      // Do a robot detection first because some robots will have a normal user agent and put the
      // bot extension part at the end.
      if (!isRobot) {
         if (platform.contains("AhrefsBot")) {
            browser = "AhrefsBot";
            browserVersion = null;
            isRobot = true;
            isBrowser = false;
         }
         else {
            String vers = getExtensionVersion("YandexBot") ;
            if (vers != null) {
               browser = "YandexBot";
               browserVersion = vers;
               isRobot = true;
               isBrowser = false;
            }
            else if ((vers = getExtensionVersion("zgrab")) != null) {
               browser = "zgrab";
               browserVersion = vers;
               isRobot = true;
               isBrowser = false;
            }
            else {
               browser = platform;
               browserVersion = null;
            }
         }
         // Everything is set now but sometimes bots pose as browsers so to catch those, look for some more patterns
         String botVers = getExtensionVersion("Googlebot");

         // specific bots we might want to log separately
         if (botVers != null || platform.contains("Googlebot")) {
            isRobot = true;
            isBrowser = false;
            browser = "Googlebot";
            browserVersion = botVers;
         }
         // generic bots and other catch-all patterns
         else if (platform.contains("bot") || platform.contains("Bot") || platform.contains("pider") || platform.contains("rawler")) {
            isRobot = true;
            isBrowser = false;
            browser = platform;
            browserVersion = null;
         }
      }

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
      else // already a bot based on the pattern
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
      for (String mobilePlatform:mobilePlatforms)
         if (platform.contains(mobilePlatform))
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

   public static void main(String[] args) {
      if (args.length != 1)
         System.out.println("Supply a user agent string as the only argument");
      else
         System.out.println(UserAgentInfo.getUserAgent(args[0]));
   }

   public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("- UserAgent: " + userAgent + "\n");
      sb.append("  isBrowser: " + isBrowser + "\n");
      sb.append("  isRobot: " + isRobot + "\n");
      sb.append("  isTablet: " + isTablet + "\n");
      sb.append("  isMobile: " + isMobile() + "\n");
      sb.append("  platform: " + platform + "\n");
      sb.append("  browser: " + browser + "\n");
      sb.append("  browserVers: " + browserVersion + "\n");
      sb.append("  os: " + osName + "\n---\n");
      return sb.toString();
   }
}
