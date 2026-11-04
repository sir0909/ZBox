import com.zbox.ai.core.*;
import java.util.*;

public class ReleaseUpdatePolicyCheck {
    static int n;
    static void ok(boolean v,String name){if(!v)throw new AssertionError(name);n++;System.out.println("PASS "+name);}
    public static void main(String[] args){
        ReleaseUpdatePolicy p=ReleaseUpdatePolicy.INSTANCE;
        String good="https://github.com/sir0909/ZBox/releases/download/v1.0.1/Zbox-1.0.1.apk";
        ReleaseApk a=new ReleaseApk("Zbox-1.0.1.apk",good,true,18000000L);
        List<ReleaseApk> assets=Arrays.asList(a);
        ok(p.isNewer("v1.0.1","1.0.0"),"higher patch prompts");
        ok(!p.isNewer("v1.0.0","1.0.0"),"same version name never prompts");
        ok(!p.isNewer("0.9.9","1.0.0"),"older version ignored");
        ok(p.isNewer("1.10.0","1.9.99"),"numeric not lexical ordering");
        ok(p.isNewer("V2.0.0","1.0.0"),"upper case tag prefix");
        ok(!p.isNewer("1.0.0.0","1.0.0"),"zero trailing component equals");
        ok(p.isNewer("1.7","1.0.0"),"short numeric version");
        ok(!p.isNewer("1.0.1-beta","1.0.0"),"prerelease suffix rejected");
        ok(!p.isNewer("latest","1.0.0"),"non version tag rejected");
        ok(!p.isNewer("999999999999.0","1.0.0"),"oversized component rejected");
        ok(!p.isNewer("1.0.0","broken"),"invalid installed name safe");
        ok(p.trustedApk(good),"own repository APK allowed");
        ok(!p.trustedApk(good.replace("https:","http:")),"cleartext APK rejected");
        ok(!p.trustedApk(good.replace("github.com","github.com.evil.example")),"lookalike host rejected");
        ok(!p.trustedApk(good.replace("sir0909","another-owner")),"foreign repository rejected");
        ok(!p.trustedApk(good+"?redirect=evil"),"query redirect rejected");
        ok(!p.trustedApk(good+"#fragment"),"fragment rejected");
        ok(!p.trustedApk(good.replace("github.com/","user:pass@github.com/")),"credential URL rejected");
        ok(!p.trustedApk(good.replace("/v1.0.1/","/../")),"path traversal rejected");
        ok(!p.trustedApk(good.replace("/v1.0.1/","/%2e%2e/")),"encoded traversal rejected");
        ok(!p.trustedApk(good.replace(".apk",".html")),"non APK asset rejected");
        ok(p.candidate("v1.0.1",false,false,assets,"1.0.0")!=null,"new release with uploaded APK accepted");
        ok(p.candidate("v1.0.0",false,false,assets,"1.0.0")==null,"same name regardless build code ignored");
        ok(p.candidate("v1.0.1",true,false,assets,"1.0.0")==null,"draft ignored");
        ok(p.candidate("v1.0.1",false,true,assets,"1.0.0")==null,"prerelease ignored");
        ok(p.candidate("v1.0.1",false,false,Collections.emptyList(),"1.0.0")==null,"missing APK ignored");
        ok(p.candidate("v1.0.1",false,false,Arrays.asList(new ReleaseApk("app.apk",good,false,1)),"1.0.0")==null,"incomplete upload ignored");
        ok(p.candidate("v1.0.1",false,false,Arrays.asList(new ReleaseApk("app.apk",good,true,0)),"1.0.0")==null,"empty asset ignored");
        ok(p.startupWindowAllows(0,true,false),"cold startup permits prompt");
        ok(p.startupWindowAllows(8000,true,false),"bounded startup window inclusive");
        ok(!p.startupWindowAllows(8001,true,false),"late response cannot prompt");
        ok(!p.startupWindowAllows(-1,true,false),"invalid elapsed time rejected");
        ok(!p.startupWindowAllows(100,false,false),"background cannot prompt");
        ok(!p.startupWindowAllows(100,true,true),"camera operation cannot be interrupted");
        StartupUpdateGate gate=new StartupUpdateGate();
        ok(gate.claim(),"first process startup claims check");
        ok(!gate.claim()&&!gate.claim(),"dismiss return and recreation cannot reclaim");
        System.out.println("RESULT "+n+" checks passed");
    }
}
