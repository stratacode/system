package sc.lang.html;

import sc.lang.pattern.Pattern;
import sc.lang.pattern.URLPatternLanguage;
import sc.parser.Parselet;

import java.util.ArrayList;
import java.util.List;

public class UserAgentPattern {
   String pattern;
   Pattern patternImpl;
   Parselet patternParselet;

   UserAgentInfo defaultInfo = new UserAgentInfo();

   public void init() {
      patternImpl = Pattern.initURLPattern(UserAgentInfo.class, pattern);
      patternParselet = patternImpl.getParselet(URLPatternLanguage.getURLPatternLanguage(), UserAgentInfo.class);
   }

   public static List<UserAgentPattern> userAgentPatterns = new ArrayList<UserAgentPattern>();
   static {
      // Any browsers that don't use Mozilla/5.0 now?
      UserAgentPattern browserPattern = new UserAgentPattern();
      browserPattern.pattern = "Mozilla/5.0{whiteSpace}{platform=userAgentComment}{whiteSpace}{extensions=userAgentExts}";
      browserPattern.init();
      browserPattern.defaultInfo.isBrowser = true;
      userAgentPatterns.add(browserPattern);

      UserAgentPattern extensionsPattern = new UserAgentPattern();
      extensionsPattern.pattern = "Mozilla/5.0{whiteSpace}{extensions=userAgentExts}";
      extensionsPattern.init();
      userAgentPatterns.add(extensionsPattern);

      UserAgentPattern robotPattern = new UserAgentPattern();
      robotPattern.pattern = "{platform=userAgentName}[/{whiteSpace}{versionString}]{whiteSpace}{extensions=userAgentExts}";
      robotPattern.init();
      robotPattern.defaultInfo.isRobot = true;
      userAgentPatterns.add(robotPattern);

      // Just the comment string - e.g. '(compatible;PetalBot;+https://aspiegel.com/petalbot)'
      UserAgentPattern robotPattern2 = new UserAgentPattern();
      robotPattern2.pattern = "{platform=userAgentComment}";
      robotPattern2.init();
      robotPattern2.defaultInfo.isRobot = true;
      userAgentPatterns.add(robotPattern2);
   }

}
