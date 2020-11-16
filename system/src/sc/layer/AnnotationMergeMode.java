package sc.layer;

public enum AnnotationMergeMode {
   // Last one wins
   Replace,
   // Append "a,b" and "c,d" to be "a,b,c,d"
   AppendCommaString,
   // Append {"a", "b"}, and {"c","d"} to be {"a","b","c","d"}
   Append
}
