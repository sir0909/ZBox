import com.zbox.ai.core.CompositionPolicy;
public class CompositionPolicyCheck {
 static int n=0;static void check(boolean v,String name){if(!v)throw new AssertionError(name);n++;}
 public static void main(String[] args){
  for(int mask=0;mask<512;mask++){
   boolean[] b=new boolean[9];for(int i=0;i<9;i++)b[i]=(mask&(1<<i))!=0;
   boolean expected=b[0]&&!b[1]&&b[2]&&b[3]&&b[4]&&!b[5]&&b[6]&&b[7]&&b[8];
   check(CompositionPolicy.canAnalyze(b[0],b[1],b[2],b[3],b[4],b[5],b[6],b[7],b[8])==expected,"manual gate "+mask);
  }
  check(CompositionPolicy.box(0,0,1,1)!=null,"full frame");
  check(CompositionPolicy.box(.1,.2,.8,.9)!=null,"ordinary crop");
  check(CompositionPolicy.box(-.1,0,1,1)==null,"negative");
  check(CompositionPolicy.box(0,0,1.1,1)==null,"outside");
  check(CompositionPolicy.box(.8,0,.2,1)==null,"reversed");
  check(CompositionPolicy.box(0,.9,1,.1)==null,"reversed y");
  check(CompositionPolicy.box(0,0,0,1)==null,"zero width");
  check(CompositionPolicy.box(0,0,1,0)==null,"zero height");
  check(CompositionPolicy.box(0,0,.01,1)==null,"tiny width");
  check(CompositionPolicy.box(0,0,1,.01)==null,"tiny height");
  check(CompositionPolicy.box(0,0,.1,.1)==null,"tiny area");
  check(CompositionPolicy.box(Double.NaN,0,1,1)==null,"nan");
  check(CompositionPolicy.box(0,0,Double.POSITIVE_INFINITY,1)==null,"infinite");
  check(CompositionPolicy.cleanText(" hi\nthere\u202e ").equals("hi there"),"controls removed");
  check(CompositionPolicy.cleanText(new String(new char[300]).replace('\0','x')).length()==160,"text bounded");
  System.out.println("RESULT "+n+" composition checks passed");
 }
}
