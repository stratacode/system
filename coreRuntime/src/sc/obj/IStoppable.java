package sc.obj;

@sc.js.JSSettings(jsLibFiles="js/scdyn.js", prefixAlias="sc_")
public interface IStoppable {
   /** You can optionally implement this method to receive a stop hook when your object or component is disposed */
   public void stop();
}