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
      browserPattern.pattern = "Mozilla/5.0{whiteSpace}\\({platform=userAgentComment}\\){whiteSpace}{extensions=userAgentExts}";
      browserPattern.init();
      browserPattern.defaultInfo.isBrowser = true;
      userAgentPatterns.add(browserPattern);

      // TODO: this is a catchall for now but it would be nice to get some type of data model from the robot
      // traffic as part of the management UI options
      UserAgentPattern robotPattern = new UserAgentPattern();
      robotPattern.pattern = "{platform=userAgentName}[/{whiteSpace}{versionString}]{whiteSpace}{extensions=userAgentExts}";
      robotPattern.init();
      robotPattern.defaultInfo.isRobot = true;
      userAgentPatterns.add(robotPattern);
   }

}
