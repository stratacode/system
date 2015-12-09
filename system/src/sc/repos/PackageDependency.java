package sc.repos;

public class PackageDependency {
   public RepositoryPackage pkg;
   public DependencyContext ctx;

   public PackageDependency(RepositoryPackage pkg, DependencyContext ctx) {
      this.pkg = pkg;
      this.ctx = ctx;
   }

   public boolean equals(Object other) {
      if (!(other instanceof PackageDependency))
         return false;
      return pkg.equals(((PackageDependency) other).pkg);
   }

   public int hashCode() {
      return pkg.hashCode();
   }

   public String toString() {
      return pkg.toString();
   }
}
