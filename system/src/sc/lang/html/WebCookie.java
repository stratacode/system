package sc.lang.html;

public class WebCookie {
   public String name;
   public String value;

   public WebCookie(String name, String value) {
      this.name = name;
      this.value = value;
   }

   public boolean secure;
   public String domain;
   public String path;
   public int maxAgeSecs;
}
